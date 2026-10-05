package com.qingyu.ime;

import android.content.Intent;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import com.qingyu.core.Candidate;
import com.qingyu.core.EngineSnapshot;
import com.qingyu.core.PinyinEngine;
import com.qingyu.core.translation.TranslationBatch;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Main: touch/paint/InputConnection. Decoder and gloss each have independent serial workers. */
public final class QingyuImeService extends InputMethodService implements KeyboardSurface.Listener, CandidateSurface.Listener {
    private final Handler main=new Handler(Looper.getMainLooper());
    private HandlerThread decoderThread,translationThread;
    private Handler decoder,translation;
    private final PinyinEngine engine=new PinyinEngine();
    private LocalEnglishProvider provider;
    private ImePreferences prefs;
    private KeyboardSurface keyboard;
    private CandidateSurface candidates;
    private LinearLayout root;
    private EngineSnapshot visible=EngineSnapshot.empty();
    private String preview="", workerRaw="";
    private boolean english, numeric, sensitive, engineOpen, workerPrivate, destroyed;
    private volatile boolean translationReady;
    private final AtomicLong session=new AtomicLong();
    private long revision, visibleRevision;
    private Runnable pendingTranslation;
    private String engineError="";
    private File dictFile;
    private String editorIdentity="";

    @Override public void onCreate() {
        super.onCreate();prefs=new ImePreferences(this);
        decoderThread=new HandlerThread("qingyu-pinyin",android.os.Process.THREAD_PRIORITY_DISPLAY);
        decoderThread.start();decoder=new Handler(decoderThread.getLooper());
        translationThread=new HandlerThread("qingyu-gloss",android.os.Process.THREAD_PRIORITY_BACKGROUND);
        translationThread.start();translation=new Handler(translationThread.getLooper());
        decoder.post(()->{
            try {
                dictFile=new File(getFilesDir(),"pinyin-v1.dat");
                if(!dictFile.exists())copyAsset("pinyin/dict_pinyin.dat",dictFile);
                openEngine(!prefs.learning());
            }catch(Exception|LinkageError e){main.post(()->{engineError="中文词库暂不可用，可继续英文输入";render();});}
        });
        translation.post(()->{
            provider=new LocalEnglishProvider(this);
            try{provider.open();translationReady=true;main.post(this::requestGlosses);}
            catch(Exception e){translationReady=false;}
        });
    }
    private void copyAsset(String path,File destination)throws Exception{
        File temp=new File(destination.getPath()+".tmp");
        try(InputStream in=getAssets().open(path);FileOutputStream out=new FileOutputStream(temp)){
            byte[] data=new byte[32768];int n;while((n=in.read(data))!=-1)out.write(data,0,n);out.getFD().sync();
        }
        if(!temp.renameTo(destination))throw new java.io.IOException("Asset install failed");
    }
    private void openEngine(boolean privacy)throws Exception{
        if(engineOpen){engine.close();engineOpen=false;}
        File user=new File(getFilesDir(),"user-pinyin.dat");
        engine.open(dictFile.getPath(),user.getPath());engine.setLearningEnabled(!privacy);engineOpen=true;workerPrivate=privacy;workerRaw="";
    }
    @Override public View onCreateInputView(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(new Palette(prefs.dark(this)).background);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int bottom=insets.getSystemWindowInsetBottom(),left=0,right=0;
            if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.navigationBars());bottom=bars.bottom;left=bars.left;right=bars.right;}
            v.setPadding(left,0,right,bottom);return insets;
        });
        candidates=new CandidateSurface(this,prefs,this);keyboard=new KeyboardSurface(this,prefs,this);
        root.addView(candidates,new LinearLayout.LayoutParams(-1,-2));root.addView(keyboard,new LinearLayout.LayoutParams(-1,-2));
        configureKeyboard();render();return root;
    }
    @Override public boolean onEvaluateFullscreenMode(){return false;}
    @Override public void onStartInput(EditorInfo info,boolean restarting){
        super.onStartInput(info,restarting);
        String identity=info.packageName+":"+info.fieldId+":"+info.inputType+":"+info.imeOptions;
        InputConnection connection=getCurrentInputConnection();
        CharSequence preceding=connection==null||preview.isEmpty()?null:connection.getTextBeforeCursor(preview.length(),0);
        if(restarting&&identity.equals(editorIdentity)&&!preview.isEmpty()&&preceding!=null&&preceding.toString().equals(preview)){
            restoreComposingSpan();applyLearningPreference(info);configureKeyboard();render();return;
        }
        editorIdentity=identity;
        final long token=session.incrementAndGet();revision++;visibleRevision=revision;
        visible=EngineSnapshot.empty();preview="";
        int type=info.inputType, cls=type&InputType.TYPE_MASK_CLASS,variation=type&InputType.TYPE_MASK_VARIATION;
        numeric=cls==InputType.TYPE_CLASS_NUMBER||cls==InputType.TYPE_CLASS_PHONE||cls==InputType.TYPE_CLASS_DATETIME;
        sensitive=cls==InputType.TYPE_CLASS_TEXT && (variation==InputType.TYPE_TEXT_VARIATION_PASSWORD || variation==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            || cls==InputType.TYPE_CLASS_NUMBER && variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        boolean uri=cls==InputType.TYPE_CLASS_TEXT && (variation==InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS||variation==InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS||variation==InputType.TYPE_TEXT_VARIATION_URI);
        english=sensitive||uri||prefs.store.getBoolean("english",false);
        boolean privacy=sensitive||!prefs.learning()||(info.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0;
        decoder.post(()->{
            if(token!=session.get())return;
            try{
                if(!engineOpen)openEngine(privacy);
                else {engine.reset();engine.setLearningEnabled(!privacy);workerPrivate=privacy;}
                workerRaw="";
            }catch(Exception|LinkageError e){main.post(()->{engineError="中文词库暂不可用，可继续英文输入";render();});}
        });
        if(keyboard!=null)keyboard.resetModes();collapse();configureKeyboard();render();
    }
    @Override public void onStartInputView(EditorInfo info,boolean restarting){super.onStartInputView(info,restarting);restoreComposingSpan();applyLearningPreference(info);configureKeyboard();render();}
    private void applyLearningPreference(EditorInfo info){
        boolean privacy=sensitive||!prefs.learning()||(info.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0;
        long token=session.get();decoder.post(()->{if(token==session.get()&&engineOpen){engine.setLearningEnabled(!privacy);workerPrivate=privacy;}});
    }
    private void restoreComposingSpan(){
        InputConnection ic=getCurrentInputConnection();if(ic==null||preview.isEmpty())return;
        CharSequence before=ic.getTextBeforeCursor(preview.length(),0);
        if(before==null||!before.toString().equals(preview))return;
        android.view.inputmethod.ExtractedTextRequest request=new android.view.inputmethod.ExtractedTextRequest();request.hintMaxChars=128;
        android.view.inputmethod.ExtractedText text=ic.getExtractedText(request,0);
        if(text!=null&&text.selectionStart==text.selectionEnd){int end=text.startOffset+text.selectionEnd;if(end>=preview.length())ic.setComposingRegion(end-preview.length(),end);}
    }
    private void configureKeyboard(){
        if(keyboard==null)return;
        EditorInfo e=getCurrentInputEditorInfo();String action="↵";
        if(e!=null && (e.imeOptions&EditorInfo.IME_FLAG_NO_ENTER_ACTION)==0){switch(e.imeOptions&EditorInfo.IME_MASK_ACTION){
            case EditorInfo.IME_ACTION_GO:action="前往";break;case EditorInfo.IME_ACTION_SEARCH:action="搜索";break;
            case EditorInfo.IME_ACTION_SEND:action="发送";break;case EditorInfo.IME_ACTION_NEXT:action="下一项";break;
            case EditorInfo.IME_ACTION_DONE:action="完成";break;
        }}
        keyboard.configure(english,numeric,action,sensitive);keyboard.setHapticFeedbackEnabled(prefs.haptic());
        if(root!=null)root.setBackgroundColor(new Palette(prefs.dark(this)).background);
        Window window=getWindow().getWindow();if(window!=null){window.setNavigationBarColor(new Palette(prefs.dark(this)).background);window.getDecorView().setSystemUiVisibility(prefs.dark(this)?0:View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);}
    }
    private void render(){
        if(candidates==null)return;
        List<String> words=new ArrayList<>();for(Candidate c:visible.candidates)words.add(c.text);
        String status=engineError;
        if(sensitive)status="安全输入";
        candidates.update(preview,words,prefs.translation()&&!sensitive,status);
        if(words.isEmpty() && candidates.expanded())collapse();
        requestGlosses();
    }
    private void requestGlosses(){
        if(candidates==null||destroyed||!translationReady||!prefs.translation()||sensitive||visible.candidates.isEmpty())return;
        if(pendingTranslation!=null)translation.removeCallbacks(pendingTranslation);
        long token=session.get(), generation=revision;List<String> words=new ArrayList<>(candidates.words());
        pendingTranslation=()->{
            if(token!=session.get())return;
            Map<String,String> result=TranslationBatch.lookup(provider,words);
            main.post(()->{if(!destroyed&&token==session.get()&&generation==revision&&prefs.translation()&&!sensitive&&candidates!=null)candidates.glosses(result);});
        };
        translation.post(pendingTranslation);
    }
    private interface DecoderAction{EngineSnapshot run()throws Exception;}
    private void submit(DecoderAction action,String suffix){
        long token=session.get(),generation=++revision;
        decoder.post(()->{
            if(token!=session.get())return;
            EngineSnapshot result;String committed=suffix;
            try{result=action.run();committed=result.committedText+suffix;workerRaw=result.rawPinyin;}
            catch(Exception|LinkageError e){result=new EngineSnapshot(workerRaw,workerRaw,Collections.emptyList(),"");}
            EngineSnapshot output=result;String commit=committed;
            main.post(()->{
                if(destroyed||token!=session.get())return;
                InputConnection ic=getCurrentInputConnection();if(ic==null)return;
                if(!commit.isEmpty())ic.commitText(commit,1);
                if(generation==revision){visible=output;visibleRevision=generation;preview=output.composing;ic.setComposingText(preview,1);if(preview.isEmpty())ic.finishComposingText();render();}
            });
        });
    }
    /** All edits share event order with decoding, including direct English and committed deletion. */
    private void editorAction(Runnable action){
        long token=session.get();decoder.post(()->main.post(()->{if(!destroyed&&token==session.get()&&getCurrentInputConnection()!=null)action.run();}));
    }
    private void directCommit(String text){submit(()->workerRaw.isEmpty()?EngineSnapshot.empty():complete(),text);}
    private void showImmediate(){
        InputConnection ic=getCurrentInputConnection();if(ic!=null){ic.setComposingText(preview,1);if(preview.isEmpty())ic.finishComposingText();}
        visible=EngineSnapshot.empty();render();
    }
    private EngineSnapshot complete(){
        if(!engineOpen){String raw=workerRaw;workerRaw="";return new EngineSnapshot("","",Collections.emptyList(),raw);}
        EngineSnapshot state=engine.search(workerRaw);
        if(state.candidates.isEmpty()){String raw=state.composing;engine.reset();return new EngineSnapshot("","",Collections.emptyList(),raw);}
        for(int i=0;i<64 && state.committedText.isEmpty()&&!state.candidates.isEmpty();i++)state=engine.select(state.candidates.get(0).id);
        if(state.committedText.isEmpty()&&state.candidates.isEmpty()){
            String raw=state.composing;engine.reset();return new EngineSnapshot("","",Collections.emptyList(),raw);
        }
        return state;
    }
    @Override public void key(String value){
        if(getCurrentInputConnection()==null)return;
        switch(value){
            case "SHIFT":keyboard.shift();return;
            case "?123":case "ABC":keyboard.toggleSymbols();if(numeric){numeric=false;english=true;configureKeyboard();}return;
            case "LANG":switchLanguage();return;
            case "SPACE":
                preview="";visible=EngineSnapshot.empty();render();collapse();
                submit(()->workerRaw.isEmpty()?new EngineSnapshot("","",Collections.emptyList()," "):complete(),"");return;
            case "ENTER":enter();return;
            case "⌫":backspace();return;
            default:
                boolean letter=value.length()==1 && value.charAt(0)>='a' && value.charAt(0)<='z';
                if((letter&&!keyboard.isSymbols()||value.equals("'")&&!preview.isEmpty())&&!english&&!sensitive&&!numeric){
                    preview=preview.length()>=PinyinEngine.MAX_PINYIN_LENGTH?value:preview+value;showImmediate();
                    submit(()->{
                        String committed="";if(workerRaw.length()>=PinyinEngine.MAX_PINYIN_LENGTH){committed=complete().committedText;workerRaw="";}
                        workerRaw+=value;
                        EngineSnapshot state=engineOpen?engine.search(workerRaw):new EngineSnapshot(workerRaw,workerRaw,Collections.emptyList(),"");
                        return committed.isEmpty()?state:new EngineSnapshot(state.composing,state.rawPinyin,state.candidates,committed);
                    },"");
                }else{
                    String text=letter&&keyboard.uppercase()?value.toUpperCase(java.util.Locale.ROOT):value;
                    preview="";directCommit(text);collapse();
                    if(letter)keyboard.consumedLetter();
                }
        }
    }
    private void switchLanguage(){
        if(sensitive)return;
        preview="";visible=EngineSnapshot.empty();
        submit(()->{String raw=engineOpen?engine.search(workerRaw).composing:workerRaw;if(engineOpen)engine.reset();workerRaw="";return new EngineSnapshot("","",Collections.emptyList(),raw);},"");
        english=!english;if(!sensitive)prefs.store.edit().putBoolean("english",english).apply();keyboard.resetModes();collapse();configureKeyboard();render();
    }
    private void enter(){
        EditorInfo info=getCurrentInputEditorInfo();InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        int action=info==null?EditorInfo.IME_ACTION_NONE:(info.imeOptions&EditorInfo.IME_MASK_ACTION);
        long token=session.get();preview="";collapse();
        submit(()->{
            if(!workerRaw.isEmpty())return complete();
            if(info!=null && (info.imeOptions&EditorInfo.IME_FLAG_NO_ENTER_ACTION)==0 && action!=EditorInfo.IME_ACTION_NONE && action!=EditorInfo.IME_ACTION_UNSPECIFIED){main.post(()->{if(token==session.get()&&getCurrentInputConnection()!=null)getCurrentInputConnection().performEditorAction(action);});return EngineSnapshot.empty();}
            return new EngineSnapshot("","",Collections.emptyList(),"\n");
        },"");
    }
    private void backspace(){
        if(!preview.isEmpty()){preview=preview.substring(0,preview.offsetByCodePoints(preview.length(),-1));showImmediate();}
        long token=session.get();
        submit(()->{
            if(!workerRaw.isEmpty()){
                if(engineOpen)return engine.backspace();workerRaw=workerRaw.substring(0,workerRaw.length()-1);return new EngineSnapshot(workerRaw,workerRaw,Collections.emptyList(),"");
            }
            main.post(()->{
                if(token!=session.get())return;
                InputConnection ic=getCurrentInputConnection();if(ic==null)return;
                CharSequence selected=ic.getSelectedText(0);
                if(selected!=null&&selected.length()>0)ic.commitText("",1);
                else ic.deleteSurroundingTextInCodePoints(1,0);
            });
            return EngineSnapshot.empty();
        },"");
    }
    @Override public void choose(int index){
        if(visibleRevision!=revision||index<0||index>=visible.candidates.size())return;
        int id=visible.candidates.get(index).id;
        preview="";
        submit(()->engineOpen?engine.select(id):EngineSnapshot.empty(),"");
        collapse();
    }
    @Override public void expand(){if(candidates==null)return;boolean expanded=!candidates.expanded()&&!visible.candidates.isEmpty();candidates.expanded(expanded);keyboard.setVisibility(expanded?View.GONE:View.VISIBLE);}
    private void collapse(){if(candidates!=null)candidates.expanded(false);if(keyboard!=null)keyboard.setVisibility(View.VISIBLE);}
    @Override public void settings(){startActivity(new Intent(this,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    @Override public void toggleTranslation(){prefs.store.edit().putBoolean("translation",!prefs.translation()).apply();render();}
    @Override public void punctuation(String text){key(english?text.replace('，',',').replace('。','.').replace('？','?').replace('！','!').replace('：',':'):text);}
    @Override public void cursor(int direction){
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        if(!preview.isEmpty()){String raw=preview;preview="";visible=EngineSnapshot.empty();submit(()->{if(engineOpen)engine.reset();workerRaw="";return new EngineSnapshot("","",Collections.emptyList(),raw);},"");render();}
        int count=Math.min(12,Math.abs(direction)),code=direction<0?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT;
        editorAction(()->{InputConnection connection=getCurrentInputConnection();for(int i=0;i<count;i++){connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code));connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code));}});
    }
    @Override public void longKey(String value){
        switch(value){
            case "LANG":((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();break;
            case "SHIFT":keyboard.shift();break;
            case "APOSTROPHE":key("'");break;
            case "DELETE_WORD":
                if(!preview.isEmpty()){preview="";showImmediate();submit(()->engineOpen?engine.reset():EngineSnapshot.empty(),"");}
                else editorAction(()->{InputConnection ic=getCurrentInputConnection();if(ic==null)return;CharSequence before=ic.getTextBeforeCursor(48,0);if(before!=null){String s=before.toString();int n=s.length();while(n>0&&Character.isWhitespace(s.charAt(n-1)))n--;while(n>0&&!Character.isWhitespace(s.charAt(n-1)))n--;ic.deleteSurroundingText(s.length()-n,0);}});break;
            case ",":case "，":key("、");break;
            case ".":case "。":key("…");break;
            case "ENTER":key("\n");break;
            default:break;
        }
    }
    @Override public void onUpdateSelection(int oldStart,int oldEnd,int newStart,int newEnd,int candidatesStart,int candidatesEnd){
        super.onUpdateSelection(oldStart,oldEnd,newStart,newEnd,candidatesStart,candidatesEnd);
        // User taps outside the composing span: finish it and retire all asynchronous results.
        if(!preview.isEmpty() && candidatesEnd>=0 && (newStart!=candidatesEnd||newEnd!=candidatesEnd)){
            session.incrementAndGet();revision++;preview="";visible=EngineSnapshot.empty();InputConnection ic=getCurrentInputConnection();if(ic!=null)ic.finishComposingText();
            decoder.post(()->{if(engineOpen)engine.reset();workerRaw="";});collapse();render();
        }
    }
    @Override public boolean onKeyDown(int code,KeyEvent event){
        if(code==KeyEvent.KEYCODE_BACK && candidates!=null&&candidates.expanded()){collapse();return true;}
        return super.onKeyDown(code,event);
    }
    @Override public void onFinishInputView(boolean finishing){if(keyboard!=null)keyboard.cancelTouch();collapse();super.onFinishInputView(finishing);}
    @Override public void onFinishInput(){
        session.incrementAndGet();revision++;preview="";visible=EngineSnapshot.empty();
        decoder.post(()->{if(engineOpen){engine.reset();if(!workerPrivate)engine.flush();}workerRaw="";});
        if(pendingTranslation!=null)translation.removeCallbacks(pendingTranslation);
        super.onFinishInput();
    }
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);restoreComposingSpan();configureKeyboard();if(root!=null)root.requestLayout();}
    @Override public void onDestroy(){
        destroyed=true;session.incrementAndGet();main.removeCallbacksAndMessages(null);
        decoder.post(()->{if(engineOpen)engine.close();decoderThread.quitSafely();});
        translation.post(()->{if(provider!=null)provider.close();translationThread.quitSafely();});super.onDestroy();
    }
}
