package com.qingyu.ime;

import android.content.Context;
import android.app.DownloadManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.LruCache;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
    private static final String[] GLOSS_LANGUAGES = {"en", "ja", "fr", "de", "ru", "es"};
    private static final String[] LANGUAGE_NAMES = {"英语", "日语", "法语", "德语", "俄语", "西班牙语"};
    private static final String[] NATIVE_NAMES = {"English", "日本語", "Français", "Deutsch", "Русский", "Español"};
    enum State { CHECKING, MISSING, DOWNLOADING, DELETING, READY, FAILED, UNSUPPORTED }
    interface Callback { void onResult(String translation, String note); }
    interface StateCallback { void onState(State state); }
    interface SizeCallback { void onSize(long bytes); }
    interface ProgressCallback { void onProgress(ModelProgress progress); }
    static final class ModelProgress {
        final State state;
        final long downloadedBytes, totalBytes;
        final String message;
        ModelProgress(State state, long downloaded, long total, String message) {
            this.state=state;downloadedBytes=downloaded;totalBytes=total;this.message=message;
        }
        int percent() { return totalBytes > 0 ? (int)Math.min(100, downloadedBytes*100/totalBytes) : -1; }
    }

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
    private final ConcurrentHashMap<String, String> modelErrors = new ConcurrentHashMap<>();
    private final Set<String> downloading = new HashSet<>();
    private final Map<String,Boolean> downloadMetered = new HashMap<>();
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
        for(String language:GLOSS_LANGUAGES){String error=this.context.getSharedPreferences("qingyu",Context.MODE_PRIVATE).getString("model_error_"+language,"");if(!error.isEmpty()){modelErrors.put(language,error);states.put(language,State.FAILED);}}
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
            if (source.equals("zh") && target.equals("en")) {
                try {
                    String details = english.lookupDetails(text);
                    if (!details.isEmpty()) { result(callback, details, "CC-CEDICT · 英文词典释义"); return; }
                } catch (RuntimeException ignored) { /* Model translation remains independent. */ }
            }
            if (source.equals("en") && target.equals("zh") && englishLookup != null) {
                try {String details = englishLookup.apply(text);
                    if (details != null && !details.isEmpty()) { result(callback, details, "本地词典 · 中文释义"); return; }
                } catch(RuntimeException ignored) { /* The independent model can still provide details. */ }
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
            if (phrase != null && target.equals("en")) return conciseTranslation(phrase[1]);
            if (target.equals("en")) return conciseTranslation(english.lookup(text));
        }
        Function<String, String> lookup = englishLookup;
        if (source.equals("en") && target.equals("zh") && lookup != null) {
            String value = lookup.apply(text);
            return conciseTranslation(value);
        }
        return "";
    }

    /** Call from any thread. Empty results include a readable status, never invented translations. */
    void translate(String text, String source, String target, Callback callback) {
        translate(text,source,target,callback,0);
    }
    private void translate(String text, String source, String target, Callback callback, int busyRetries) {
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
            if (state != State.READY) { result(callback, "", modelStatusText(language)); return; }
            String cached = cache.get(key);
            if (cached != null) { result(callback, cached, "Google Translate · 端侧翻译"); return; }
            ArrayList<Callback> listeners = pending.get(key);
            if (listeners != null) { if (listeners.size() < 8) listeners.add(callback); else retryBusy(text,source,target,callback,busyRetries); return; }
            // ponytail: cap in-flight inference at eight; add priority cancellation only if measured contention warrants it.
            if (pending.size() >= 8) { retryBusy(text,source,target,callback,busyRetries); return; }
            listeners = new ArrayList<>(); listeners.add(callback); pending.put(key, listeners);
            long generation=modelGeneration;
            try {
                client(source, target).translate(text)
                    .addOnSuccessListener(executor, value -> {if(generation==modelGeneration)complete(key, value == null ? "" : value.trim(), value == null || value.trim().isEmpty() ? "没有查到译文" : "Google Translate · 端侧翻译");})
                    .addOnFailureListener(executor, failure -> {if(generation==modelGeneration)complete(key, "", failureText(failure));});
            } catch (RuntimeException | LinkageError failure) { if(generation==modelGeneration)complete(key, "", failureText(failure)); }
        });
    }
    private void retryBusy(String text,String source,String target,Callback callback,int attempts) {
        if(attempts>=3){result(callback,"","翻译处理中，请稍后再试");return;}
        long generation=modelGeneration;
        worker.postDelayed(()->{if(closed)return;if(generation!=modelGeneration){result(callback,"","翻译模型已更改，请重新查看");return;}translate(text,source,target,callback,attempts+1);},350);
    }

    State status(String language) { return modelPlatformSupported && supported(language) ? states.getOrDefault(modelKey(language), State.CHECKING) : State.UNSUPPORTED; }
    String modelStatusText(String language) {
        if(!supported(language))return stateText(State.UNSUPPORTED);
        String error=modelErrors.get(modelKey(language));
        return status(language)==State.FAILED && error!=null ? error : stateText(status(language));
    }
    static boolean modelPlatformSupported(boolean process64,String abi,long pageSize) {
        // The current official ML Kit ARM64 binary has a non-16K-aligned RELRO end.
        // Keep dictionary/input paths usable rather than loading that JNI on affected devices.
        return !(process64 && "arm64-v8a".equals(abi) && pageSize > 4096);
    }

    /** Checking existing files never downloads a model. */
    void refreshModels(String language, StateCallback callback) {
        worker.post(() -> {
            if (closed) return;
            if (!modelPlatformSupported || !supported(language)) { stateResult(callback, State.UNSUPPORTED); return; }
            String key = modelKey(language);
            if (status(key) == State.DELETING) { stateResult(callback, status(key)); return; }
            long generation=modelGeneration;
            try {
                RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class)
                    .addOnSuccessListener(executor, models -> {
                        if(closed||generation!=modelGeneration)return;
                        Set<String> downloaded = new HashSet<>();
                        for (TranslateRemoteModel model : models) downloaded.add(model.getLanguage());
                        for (String target : GLOSS_LANGUAGES) {
                            if (downloading.contains(target) || status(target) == State.DELETING) continue;
                            // English is the built-in pivot; other language files translate to/from English.
                            if (downloaded.contains("zh") && (target.equals("en") || downloaded.contains(target))) {
                                boolean changed=status(target)==State.DOWNLOADING;
                                states.put(target,State.READY);clearModelError(target);
                                if(changed)modelsChanged();
                            } else {
                                ModelProgress progress=readModelProgress(target);
                                if(progress.state==State.DOWNLOADING) states.put(target,State.DOWNLOADING);
                                else if(status(target)!=State.FAILED) states.put(target,State.MISSING);
                            }
                        }
                        stateResult(callback, status(key));
                    })
                    .addOnFailureListener(executor, failure -> { if(closed||generation!=modelGeneration)return;for(String target:GLOSS_LANGUAGES)if(status(target)==State.CHECKING)modelFailed(target,failure);modelFailed(key,failure); stateResult(callback, State.FAILED); });
            } catch (RuntimeException | LinkageError failure) { for(String target:GLOSS_LANGUAGES)if(status(target)==State.CHECKING)modelFailed(target,failure);modelFailed(key,failure); stateResult(callback, State.FAILED); }
        });
    }

    /** User-initiated. Metered downloads require the model manager's explicit choice. */
    void ensureModels(String language, StateCallback callback) {
        ensureModels(language,context.getSharedPreferences("qingyu",Context.MODE_PRIVATE).getBoolean("model_metered",false),callback);
    }
    void ensureModels(String language, boolean allowMetered, StateCallback callback) {
        worker.post(() -> {
            if (closed) return;
            if (!modelPlatformSupported || !supported(language)) { stateResult(callback, State.UNSUPPORTED); return; }
            String key = modelKey(language);
            if (downloading.contains(key) || status(key) == State.DELETING) { stateResult(callback, status(key)); return; }
            if(status(key)==State.READY){stateResult(callback,State.READY);return;}
            if(!hasNetwork()) { modelFailed(key,"没有网络连接 · 连接后可重试下载");stateResult(callback,State.FAILED);return; }
            if(!allowMetered&&!hasWifi()){modelFailed(key,"当前不是 Wi-Fi 网络 · 可在模型管理选择使用流量下载");stateResult(callback,State.FAILED);return;}
            clearModelError(key);downloading.add(key);downloadMetered.put(key,allowMetered);
            states.put(key, State.DOWNLOADING); stateResult(callback, State.DOWNLOADING);
            try {
                DownloadConditions.Builder conditions=new DownloadConditions.Builder();
                if(!allowMetered)conditions.requireWifi();
                client("zh", key).downloadModelIfNeeded(conditions.build())
                    .addOnSuccessListener(executor, unused -> { if(closed)return;downloading.remove(key);states.put(key, State.READY);modelsChanged();stateResult(callback, State.READY); refreshModels(key, callback); })
                    .addOnFailureListener(executor, failure -> { if(closed)return;downloading.remove(key);modelFailed(key,failure); stateResult(callback, State.FAILED); });
            } catch (RuntimeException | LinkageError failure) { downloading.remove(key);modelFailed(key,failure); stateResult(callback, State.FAILED); }
        });
    }

    /** Read-only observation. Unknown SDK ids/totals stay indeterminate rather than inventing progress. */
    void modelProgress(String language, ProgressCallback callback) {
        if(callback==null)return;
        worker.post(()->{if(closed)return;ModelProgress progress=readModelProgress(language);main.post(()->{if(!closed)callback.onProgress(progress);});});
    }
    private ModelProgress readModelProgress(String language) {
        if(!modelPlatformSupported||!supported(language))return new ModelProgress(State.UNSUPPORTED,0,-1,stateText(State.UNSUPPORTED));
        String key=modelKey(language);
        long received=0,total=0;boolean found=false,known=true,active=false;String message="正在准备模型下载";
        try {
            DownloadManager manager=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);
            android.content.SharedPreferences sdk=context.getSharedPreferences("com.google.mlkit.internal",Context.MODE_PRIVATE);
            // ponytail: pinned ML Kit 17.0.3 exposes no public byte-progress API. Read only its
            // persisted DownloadManager ids; if this SDK format changes, fall back to a spinner.
            Set<Long> ids=new HashSet<>();
            for(String part:key.equals("en")?new String[]{"zh"}:new String[]{"zh",key}) {
                TranslateRemoteModel model=new TranslateRemoteModel.Builder(part).build();
                long id=sdk.getLong("downloading_model_id_"+model.getUniqueModelNameForPersist(),-1);
                if(id<=0||manager==null||!ids.add(id))continue;
                try(Cursor cursor=manager.query(new DownloadManager.Query().setFilterById(id))) {
                    if(cursor==null||!cursor.moveToFirst())continue;
                    found=true;
                    long bytes=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long expected=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    received+=Math.max(0,bytes);if(expected>0)total+=expected;else known=false;
                    int downloadState=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    int reason=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    if(downloadState==DownloadManager.STATUS_FAILED) {
                        String error=downloadFailureText(reason);modelFailed(key,error);
                        return new ModelProgress(State.FAILED,received,known?total:-1,error);
                    }
                    if(downloadState!=DownloadManager.STATUS_SUCCESSFUL)active=true;
                    if(downloadState==DownloadManager.STATUS_PAUSED)message=downloadPauseText(reason);
                    else if(downloadState==DownloadManager.STATUS_RUNNING)message="正在下载模型";
                    else if(downloadState==DownloadManager.STATUS_SUCCESSFUL&&!active)message="下载完成，正在校验与安装";
                }
            }
        } catch(RuntimeException ignored) { known=false; }
        State state=status(key);
        if(found&&active){state=State.DOWNLOADING;states.put(key,state);}
        else if(state==State.DOWNLOADING&&!found&&!downloading.contains(key)){state=State.MISSING;states.put(key,state);}
        if(state==State.DOWNLOADING) {
            if(!hasNetwork())message="等待网络连接 · 恢复网络后继续";
            else if(!downloadMetered.getOrDefault(key,context.getSharedPreferences("qingyu",Context.MODE_PRIVATE).getBoolean("model_metered",false))&&!hasWifi())message="等待 Wi-Fi · 恢复 Wi-Fi 后继续";
        } else message=modelStatusText(key);
        return new ModelProgress(state,received,found&&known?total:-1,message);
    }
    boolean hasWifi() { return networkHas(NetworkCapabilities.TRANSPORT_WIFI); }
    private boolean hasNetwork() { return networkHas(-1); }
    private boolean networkHas(int transport) {
        try {ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);NetworkCapabilities capabilities=manager==null?null:manager.getNetworkCapabilities(manager.getActiveNetwork());return capabilities!=null&&(transport<0?capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET):capabilities.hasTransport(transport));}
        catch(RuntimeException ignored){return false;}
    }
    static String downloadPauseText(int reason) {
        if(reason==DownloadManager.PAUSED_WAITING_FOR_NETWORK)return "等待网络连接 · 恢复网络后继续";
        if(reason==DownloadManager.PAUSED_QUEUED_FOR_WIFI)return "系统等待 Wi-Fi · 恢复 Wi-Fi 后继续";
        if(reason==DownloadManager.PAUSED_WAITING_TO_RETRY)return "网络中断 · 系统正在自动重试";
        return "系统已暂停下载 · 请检查网络后重试";
    }
    static String downloadFailureText(int reason) {
        if(reason==DownloadManager.ERROR_INSUFFICIENT_SPACE)return "存储空间不足 · 腾出空间后重试";
        if(reason==DownloadManager.ERROR_HTTP_DATA_ERROR)return "下载连接中断 · 请检查网络后重试";
        if(reason==DownloadManager.ERROR_TOO_MANY_REDIRECTS||reason==DownloadManager.ERROR_UNHANDLED_HTTP_CODE||reason>=400&&reason<600)return "模型服务器暂不可达（"+reason+"）· 更换可用网络后重试";
        if(reason==DownloadManager.ERROR_FILE_ERROR||reason==DownloadManager.ERROR_DEVICE_NOT_FOUND)return "无法写入模型文件 · 检查存储后重试";
        return "模型下载失败（"+reason+"）· 检查网络后重试";
    }
    static String failureText(Throwable failure) {
        Throwable cause=failure;
        for(int depth=0;depth<5&&cause.getCause()!=null;depth++)cause=cause.getCause();
        if(failure instanceof MlKitException) {
            int code=((MlKitException)failure).getErrorCode();
            if(code==MlKitException.NOT_ENOUGH_SPACE||code==MlKitException.RESOURCE_EXHAUSTED)return "存储空间不足 · 腾出空间后重试";
            if(code==MlKitException.MODEL_HASH_MISMATCH||code==MlKitException.DATA_LOSS)return "模型校验失败 · 请重新下载";
            if(code==MlKitException.NETWORK_ISSUE||code==MlKitException.UNAVAILABLE||code==MlKitException.DEADLINE_EXCEEDED)return "模型服务器连接失败 · 请检查可访问 Google 的网络后重试";
            if(code==MlKitException.UNSUPPORTED||code==MlKitException.MODEL_INCOMPATIBLE_WITH_TFLITE)return "当前设备暂不支持此翻译模型 · 中文输入照常可用";
            return "模型操作失败（SDK "+code+"）· 请重试";
        }
        if(cause instanceof java.net.UnknownHostException||cause instanceof java.net.SocketTimeoutException||cause instanceof javax.net.ssl.SSLException)return "模型服务器连接失败 · 请检查可访问 Google 的网络后重试";
        if(failure instanceof LinkageError||cause instanceof LinkageError)return "模型组件无法加载 · 当前设备暂不支持，中文输入照常可用";
        return "端侧模型暂不可用 · 检查网络与存储后重试";
    }
    private void modelFailed(String language,Throwable failure){modelFailed(language,failureText(failure));}
    private void modelFailed(String language,String message){states.put(language,State.FAILED);modelErrors.put(language,message);context.getSharedPreferences("qingyu",Context.MODE_PRIVATE).edit().putString("model_error_"+language,message).apply();}
    private void clearModelError(String language){if(modelErrors.remove(language)!=null)context.getSharedPreferences("qingyu",Context.MODE_PRIVATE).edit().remove("model_error_"+language).apply();}

    /** Commit one concise sense; the unabridged dictionary entry remains available in describe(). */
    static String conciseTranslation(String value) {
        if(value==null)return "";
        String text=value.trim();
        for(char separator:new char[]{';','；','\n'}){int index=text.indexOf(separator);if(index>=0)text=text.substring(0,index).trim();}
        return text.replaceFirst("^(?:n|v|vt|vi|adj|adv|prep|pron|conj|aux|int)\\.\\s*", "").replaceFirst("^(?:名词|动词|形容词|副词|介词|代词|连词)[：:]\\s*", "").trim();
    }

    /** SDK-managed deletion; removing English's shared Chinese model disables all model pairs. */
    void deleteModels(String language,StateCallback callback){
        worker.post(()->{
            if(closed)return;if(!modelPlatformSupported||!supported(language)){stateResult(callback,State.UNSUPPORTED);return;}
            String key=modelKey(language);
            for(State state:states.values())if(state==State.DOWNLOADING||state==State.DELETING){stateResult(callback,status(key));return;}
            modelGeneration++;invalidateModelClients();states.put(key,State.DELETING);stateResult(callback,State.DELETING);
            String fileLanguage=key.equals("en")?"zh":key;
            try{RemoteModelManager.getInstance().deleteDownloadedModel(new TranslateRemoteModel.Builder(fileLanguage).build())
                .addOnSuccessListener(executor,unused->{
                    if(closed)return;
                    // ML Kit 17.0.3 deletes its flat dictionaries but leaves the unpacked
                    // translate_* neural-model directories. Remove only this language pair.
                    try{deleteModelTree(modelFolder(key,false));deleteModelTree(modelFolder(key,true));}
                    catch(IOException|RuntimeException failure){modelFailed(key,failure);modelsChanged();stateResult(callback,State.FAILED);return;}
                    states.put(key,State.MISSING);clearModelError(key);if(key.equals("en"))for(String target:GLOSS_LANGUAGES)states.put(target,State.MISSING);modelsChanged();refreshModels(key,callback);
                })
                .addOnFailureListener(executor,failure->{if(closed)return;modelFailed(key,failure);stateResult(callback,State.FAILED);});
            }catch(RuntimeException|LinkageError failure){modelFailed(key,failure);stateResult(callback,State.FAILED);}
        });
    }
    void refreshAfterModelChange(String language,StateCallback callback){worker.post(()->{if(closed)return;modelGeneration++;invalidateModelClients();states.clear();for(String activeDownload:downloading)states.put(activeDownload,State.DOWNLOADING);refreshModels(language,callback);});}
    private void invalidateModelClients(){for(ArrayList<Callback> callbacks:pending.values())for(Callback callback:callbacks)result(callback,"","翻译模型已更改，请重新查看");pending.clear();cache.evictAll();for(Translator translator:clients.values())closeClient(translator);clients.clear();}
    private void modelsChanged(){cache.evictAll();android.content.SharedPreferences prefs=context.getSharedPreferences("qingyu",Context.MODE_PRIVATE);prefs.edit().putLong("models_revision",prefs.getLong("models_revision",0)+1).apply();}
    /** Actual installed/staged file bytes, not a download estimate; unreadable SDK paths report -1. */
    void modelSize(String language,SizeCallback callback){
        if(callback==null)return;
        worker.post(()->{if(closed)return;long bytes=-1;
            try{if(!supported(language)){main.post(()->{if(!closed)callback.onSize(-1);});return;}
                File folder=modelFolder(language,false),temporary=modelFolder(language,true);
                long installed=folder.isDirectory()?folderBytes(folder):0,staged=temporary.isDirectory()?folderBytes(temporary):0;
                bytes=installed<0||staged<0?-1:folder.isDirectory()||temporary.isDirectory()?installed+staged:status(language)==State.READY?-1:0;
            }catch(IOException|RuntimeException ignored){}
            long measured=bytes;main.post(()->{if(!closed)callback.onSize(measured);});
        });
    }
    private File modelFolder(String language,boolean temporary)throws IOException{
        String key=modelKey(language),fileLanguage=key.equals("en")?"zh":key;
        String name=new TranslateRemoteModel.Builder(fileLanguage).build().getModelNameForBackend();
        if(!name.matches("[a-z]{2}_[a-z]{2}"))throw new IOException("Unknown model directory");
        File root=new File(context.getNoBackupFilesDir().getCanonicalFile(),"com.google.mlkit.translate.models"),parent=temporary?new File(root,"temp"):root,folder=new File(parent,name);
        if(!root.getCanonicalFile().equals(root.getAbsoluteFile())||!parent.getCanonicalFile().equals(parent.getAbsoluteFile())||!folder.getCanonicalFile().equals(folder.getAbsoluteFile()))throw new IOException("Model directory escapes app storage");
        return folder;
    }
    private static void deleteModelTree(File file)throws IOException{
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return;
        if(file.isDirectory()&&!Files.isSymbolicLink(file.toPath())){File[] children=file.listFiles();if(children==null)throw new IOException("Cannot read model directory");for(File child:children)deleteModelTree(child);}
        Files.delete(file.toPath());
    }
    private static long folderBytes(File file){if(file.isFile())return file.length();File[] children=file.listFiles();if(children==null)return -1;long bytes=0;for(File child:children){long size=folderBytes(child);if(size<0)return -1;bytes+=size;}return bytes;}

    static String[] glossLanguages() { return GLOSS_LANGUAGES.clone(); }
    static boolean isGlossLanguage(String language) { return languageIndex(language) >= 0; }
    static String languageName(String language) { int index=languageIndex(language);return "zh".equals(language)?"中文":index<0?"英语":LANGUAGE_NAMES[index]; }
    static String languageLabel(String language) { int index=languageIndex(language);return index<0?languageName(language):LANGUAGE_NAMES[index]+" · "+NATIVE_NAMES[index]; }
    private static int languageIndex(String language) { for(int i=0;i<GLOSS_LANGUAGES.length;i++)if(GLOSS_LANGUAGES[i].equals(language))return i;return -1; }
    static String stateText(State state) {
        switch (state) {
            case READY: return "模型已就绪 · 可离线翻译";
            case DOWNLOADING: return "正在准备或下载模型 · 进度见模型管理";
            case DELETING: return "正在删除模型";
            case FAILED: return "模型不可用 · 请检查网络后重试下载";
            case MISSING: return "模型未下载 · 中文输入照常可用";
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
    private static boolean supported(String value) { return "zh".equals(value) || isGlossLanguage(value); }
    private static String modelKey(String language) { return "zh".equals(language) ? "en" : language; }
    private void complete(String key, String value, String note) {
        ArrayList<Callback> callbacks = pending.remove(key);
        if (closed || callbacks == null) return;
        if (!value.trim().isEmpty()) cache.put(key, value);
        for (Callback callback : callbacks) result(callback, value, note);
    }
    private void result(Callback callback, String value, String note) { main.post(() -> { if (!closed) callback.onResult(value, note); }); }
    private void stateResult(StateCallback callback, State state) { if (callback != null) main.post(() -> { if (!closed) callback.onState(state); }); }
    private void assertWorker() { if (Looper.myLooper() != worker.getLooper()) throw new IllegalStateException("Translation lookup requires its worker"); }
    private static void closeClient(Translator translator){try{translator.close();}catch(RuntimeException|LinkageError ignored){}}
    @Override public void close() {
        closed = true;
        worker.post(() -> {
            pending.clear(); cache.evictAll(); phrases.clear(); english.close();
            for (Translator translator : clients.values()) closeClient(translator);
            clients.clear();
            if (ownedThread != null) ownedThread.quitSafely();
        });
    }
}
