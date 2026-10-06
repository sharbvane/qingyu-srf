package com.qingyu.ime;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.LruCache;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** Local lookups and model work stay off the IME thread. Downloads require an explicit action. */
final class TranslationRepository implements AutoCloseable {
    enum State { CHECKING, MISSING, DOWNLOADING, DELETING, READY, FAILED, UNSUPPORTED }
    interface Callback { void onResult(String translation, String note); }
    interface StateCallback { void onState(State state); }
    interface SizeCallback { void onSize(long bytes); }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler worker;
    private final HandlerThread ownedThread;
    private final Executor executor;
    private final LocalEnglishProvider english;
    private final Map<String, String[]> phrases = new HashMap<>();
    private final Map<String, Translator> clients = new HashMap<>();
    private final Map<String, ArrayList<Callback>> pending = new HashMap<>();
    private final LruCache<String, String> cache = new LruCache<>(256);
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private volatile Function<String, String> englishLookup;
    private volatile boolean closed;
    private final boolean modelPlatformSupported;
    private long modelGeneration;

    TranslationRepository(Context context) { this(context, null); }
    TranslationRepository(Context context, Handler existingWorker) {
        this.context = context.getApplicationContext();
        modelPlatformSupported = modelPlatformSupported(android.os.Process.is64Bit(),
            android.os.Build.SUPPORTED_ABIS.length==0 ? "" : android.os.Build.SUPPORTED_ABIS[0],
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE));
        english = new LocalEnglishProvider(context);
        if (existingWorker == null) {
            ownedThread = new HandlerThread("qingyu-translation", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            ownedThread.start();
            worker = new Handler(ownedThread.getLooper());
        } else { ownedThread = null; worker = existingWorker; }
        executor = runnable -> worker.post(runnable);
        refreshModels("en", null);
    }

    /** Call once on the supplied worker, before localGloss. No model/network work here. */
    void open() throws Exception {
        assertWorker();
        // A failed dictionary install must not disable the independent phrase table or models.
        Exception dictionaryFailure = null;
        try { english.open(); } catch (Exception failure) { dictionaryFailure = failure; }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("translation/phrase-gloss.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length == 2 && !fields[0].trim().isEmpty()) phrases.put(fields[0], fields);
            }
        } catch (Exception phraseFailure) {
            if (dictionaryFailure != null) throw dictionaryFailure;
        }
    }

    void setEnglishLookup(Function<String, String> lookup) { englishLookup = lookup; }

    /** Definition text is for reading only; never use it as the committed translation. */
    void describe(String text, String source, String target, Callback callback) {
        if (callback == null) return;
        worker.post(() -> {
            if (closed) return;
            if (text == null || text.trim().isEmpty() || text.length() > 4096 || !supported(source) || !supported(target)) {
                result(callback, "", "没有可查看的内容"); return;
            }
            if (source.equals("zh")) {
                try {
                    String details = english.lookupDetails(text);
                    if (!details.isEmpty()) { result(callback, details, "CC-CEDICT · 英文词典释义"); return; }
                } catch (RuntimeException ignored) { /* Model translation remains independent. */ }
            }
            translate(text, source, target, callback);
        });
    }

    String localGloss(String text, String source, String target) {
        assertWorker();
        if (closed || text == null || text.trim().isEmpty()) return "";
        if (source.equals(target)) return text;
        if (source.equals("zh")) {
            String[] phrase = phrases.get(text);
            if (phrase != null && target.equals("en")) return phrase[1];
            if (target.equals("en")) return english.lookup(text);
        }
        Function<String, String> lookup = englishLookup;
        if (source.equals("en") && target.equals("zh") && lookup != null) {
            String value = lookup.apply(text);
            return value == null ? "" : value;
        }
        return "";
    }

    /** Call from any thread. Empty results include a readable status, never invented translations. */
    void translate(String text, String source, String target, Callback callback) {
        if (callback == null) return;
        worker.post(() -> {
            if (closed) return;
            if (text == null || text.trim().isEmpty() || text.length() > 4096 || !supported(source) || !supported(target)) {
                result(callback, "", "没有可翻译的内容"); return;
            }
            String local;
            try { local = localGloss(text, source, target); }
            catch (RuntimeException ignored) { local = ""; }
            if (!local.isEmpty()) {
                result(callback, local, phrases.containsKey(text) ? "本地短语" : "本地词典"); return;
            }
            String key = source + "\n" + target + "\n" + text;
            String language = target.equals("zh") ? source : target;
            State state = status(language);
            if (state != State.READY) { result(callback, "", stateText(state)); return; }
            String cached = cache.get(key);
            if (cached != null) { result(callback, cached, "Google Translate · 端侧翻译"); return; }
            ArrayList<Callback> listeners = pending.get(key);
            if (listeners != null) { if (listeners.size() < 8) listeners.add(callback); else result(callback, "", "翻译处理中"); return; }
            // ponytail: cap in-flight inference at eight; add priority cancellation only if measured contention warrants it.
            if (pending.size() >= 8) { result(callback, "", "翻译处理中，请稍后再试"); return; }
            listeners = new ArrayList<>(); listeners.add(callback); pending.put(key, listeners);
            long generation=modelGeneration;
            try {
                client(source, target).translate(text)
                    .addOnSuccessListener(executor, value -> {if(generation==modelGeneration)complete(key, value == null ? "" : value.trim(), value == null || value.trim().isEmpty() ? "没有查到译文" : "Google Translate · 端侧翻译");})
                    .addOnFailureListener(executor, failure -> {if(generation==modelGeneration)complete(key, "", "端侧翻译暂不可用，请稍后重试");});
            } catch (RuntimeException failure) { if(generation==modelGeneration)complete(key, "", "端侧翻译暂不可用，请稍后重试"); }
        });
    }

    State status(String language) { return modelPlatformSupported ? states.getOrDefault(modelKey(language), State.CHECKING) : State.UNSUPPORTED; }
    static boolean modelPlatformSupported(boolean process64,String abi,long pageSize) {
        // The current official ML Kit ARM64 binary has a non-16K-aligned RELRO end.
        // Keep dictionary/input paths usable rather than loading that JNI on affected devices.
        return !(process64 && "arm64-v8a".equals(abi) && pageSize > 4096);
    }

    /** Checking existing files never downloads a model. */
    void refreshModels(String language, StateCallback callback) {
        worker.post(() -> {
            if (closed) return;
            if (!modelPlatformSupported) { stateResult(callback, State.UNSUPPORTED); return; }
            String key = modelKey(language);
            if (status(key) == State.DOWNLOADING || status(key) == State.DELETING) { stateResult(callback, status(key)); return; }
            long generation=modelGeneration;
            try {
                RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class)
                    .addOnSuccessListener(executor, models -> {
                        if(closed||generation!=modelGeneration)return;
                        Set<String> downloaded = new HashSet<>();
                        for (TranslateRemoteModel model : models) downloaded.add(model.getLanguage());
                        for (String target : new String[]{"en", "ja", "fr"}) {
                            if (status(target) == State.DOWNLOADING || status(target) == State.DELETING) continue;
                            // English is the built-in pivot; other language files translate to/from English.
                            states.put(target, downloaded.contains("zh") && (target.equals("en") || downloaded.contains(target)) ? State.READY : State.MISSING);
                        }
                        stateResult(callback, status(key));
                    })
                    .addOnFailureListener(executor, failure -> { if(closed||generation!=modelGeneration)return;states.put(key, State.FAILED); stateResult(callback, State.FAILED); });
            } catch (RuntimeException failure) { states.put(key, State.FAILED); stateResult(callback, State.FAILED); }
        });
    }

    /** User-initiated, Wi-Fi-only. Input text is never part of a download request. */
    void ensureModels(String language, StateCallback callback) {
        worker.post(() -> {
            if (closed) return;
            if (!modelPlatformSupported) { stateResult(callback, State.UNSUPPORTED); return; }
            String key = modelKey(language);
            if (status(key) == State.DOWNLOADING || status(key) == State.DELETING) { stateResult(callback, status(key)); return; }
            states.put(key, State.DOWNLOADING); stateResult(callback, State.DOWNLOADING);
            long generation=modelGeneration;
            try {
                client("zh", key).downloadModelIfNeeded(new DownloadConditions.Builder().requireWifi().build())
                    .addOnSuccessListener(executor, unused -> { if(closed||generation!=modelGeneration)return;states.put(key, State.READY);modelsChanged();stateResult(callback, State.READY); refreshModels(key, callback); })
                    .addOnFailureListener(executor, failure -> { if(closed||generation!=modelGeneration)return;states.put(key, State.FAILED); stateResult(callback, State.FAILED); });
            } catch (RuntimeException failure) { states.put(key, State.FAILED); stateResult(callback, State.FAILED); }
        });
    }

    /** SDK-managed deletion; removing English's shared Chinese model disables all model pairs. */
    void deleteModels(String language,StateCallback callback){
        worker.post(()->{
            if(closed)return;if(!modelPlatformSupported){stateResult(callback,State.UNSUPPORTED);return;}
            String key=modelKey(language);
            for(State state:states.values())if(state==State.DOWNLOADING||state==State.DELETING){stateResult(callback,status(key));return;}
            modelGeneration++;invalidateModelClients();states.put(key,State.DELETING);stateResult(callback,State.DELETING);
            String fileLanguage=key.equals("en")?"zh":key;
            try{RemoteModelManager.getInstance().deleteDownloadedModel(new TranslateRemoteModel.Builder(fileLanguage).build())
                .addOnSuccessListener(executor,unused->{if(closed)return;states.put(key,State.MISSING);if(key.equals("en"))for(String target:new String[]{"ja","fr"})states.put(target,State.MISSING);modelsChanged();refreshModels(key,callback);})
                .addOnFailureListener(executor,failure->{if(closed)return;states.put(key,State.FAILED);stateResult(callback,State.FAILED);});
            }catch(RuntimeException failure){states.put(key,State.FAILED);stateResult(callback,State.FAILED);}
        });
    }
    void refreshAfterModelChange(String language,StateCallback callback){worker.post(()->{if(closed)return;modelGeneration++;invalidateModelClients();states.clear();refreshModels(language,callback);});}
    private void invalidateModelClients(){for(ArrayList<Callback> callbacks:pending.values())for(Callback callback:callbacks)result(callback,"","翻译模型已更改，请重新查看");pending.clear();cache.evictAll();for(Translator translator:clients.values())translator.close();clients.clear();}
    private void modelsChanged(){cache.evictAll();android.content.SharedPreferences prefs=context.getSharedPreferences("qingyu",Context.MODE_PRIVATE);prefs.edit().putLong("models_revision",prefs.getLong("models_revision",0)+1).apply();}
    /** Installed file bytes, not a download estimate. SDK layout changes produce unknown (-1). */
    void modelSize(String language,SizeCallback callback){
        worker.post(()->{if(closed)return;long bytes=-1;
            try{String fileLanguage=modelKey(language).equals("en")?"zh":modelKey(language);TranslateRemoteModel model=new TranslateRemoteModel.Builder(fileLanguage).build();
                File folder=new File(new File(context.getNoBackupFilesDir(),"com.google.mlkit.translate.models"),model.getModelNameForBackend());
                bytes=folder.isDirectory()?folderBytes(folder):status(language)==State.MISSING?0:-1;
            }catch(RuntimeException ignored){}
            long measured=bytes;main.post(()->{if(!closed)callback.onSize(measured);});
        });
    }
    private static long folderBytes(File file){if(file.isFile())return file.length();File[] children=file.listFiles();if(children==null)return -1;long bytes=0;for(File child:children){long size=folderBytes(child);if(size<0)return -1;bytes+=size;}return bytes;}

    static String languageName(String language) { return "ja".equals(language) ? "日语" : "fr".equals(language) ? "法语" : "英语"; }
    static String stateText(State state) {
        switch (state) {
            case READY: return "模型已就绪 · 可离线翻译";
            case DOWNLOADING: return "等待 Wi-Fi 或正在下载模型";
            case DELETING: return "正在删除模型";
            case FAILED: return "模型不可用 · 请检查网络后重试下载";
            case MISSING: return "模型未下载 · 本地词典仍可使用";
            case UNSUPPORTED: return "此 16KB ARM64 设备暂不支持模型翻译 · 本地释义仍可使用";
            default: return "正在检查本地模型";
        }
    }

    private Translator client(String source, String target) {
        String key = source + "-" + target;
        Translator translator = clients.get(key);
        if (translator == null) {
            translator = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build());
            clients.put(key, translator);
        }
        return translator;
    }
    private static boolean supported(String value) { return value != null && (value.equals("zh") || value.equals("en") || value.equals("ja") || value.equals("fr")); }
    private static String modelKey(String language) { return "ja".equals(language) || "fr".equals(language) ? language : "en"; }
    private void complete(String key, String value, String note) {
        ArrayList<Callback> callbacks = pending.remove(key);
        if (closed || callbacks == null) return;
        if (!value.trim().isEmpty()) cache.put(key, value);
        for (Callback callback : callbacks) result(callback, value, note);
    }
    private void result(Callback callback, String value, String note) { main.post(() -> { if (!closed) callback.onResult(value, note); }); }
    private void stateResult(StateCallback callback, State state) { if (callback != null) main.post(() -> { if (!closed) callback.onState(state); }); }
    private void assertWorker() { if (Looper.myLooper() != worker.getLooper()) throw new IllegalStateException("Translation lookup requires its worker"); }
    @Override public void close() {
        closed = true;
        worker.post(() -> {
            pending.clear(); cache.evictAll(); phrases.clear(); english.close();
            for (Translator translator : clients.values()) translator.close();
            clients.clear();
            if (ownedThread != null) ownedThread.quitSafely();
        });
    }
}
