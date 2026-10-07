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
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import com.qingyu.core.Candidate;
import com.qingyu.core.EngineSnapshot;
import com.qingyu.core.NineKeyCandidate;
import com.qingyu.core.PinyinEngine;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Main: touch/paint/InputConnection. One ordered decoder; an independent gloss worker. */
public final class QingyuImeService extends InputMethodService implements KeyboardSurface.Listener, CandidateSurface.Listener {
    private final Handler main=new Handler(Looper.getMainLooper());
    private HandlerThread decoderThread,translationThread;
    private Handler decoder,translation;
    private final PinyinEngine engine=new PinyinEngine();
    private TranslationRepository translations;
    private LocalInputDictionary inputDictionary;
    private ClipboardHistory clipboard;
    private ImePreferences prefs;
    private KeyboardSurface keyboard;
    private CandidateSurface candidates,expandedCandidates;
    private ImePanels panels;
    private LinearLayout root;
    private FrameLayout topRegion;
    private boolean navigationVisible=true;
    private PopupWindow pinyinBubble;
    private TextView pinyinText;
    private String rawPreview="",editorEnterLabel="↵";
    private boolean inputViewActive;
    private EngineSnapshot visible=EngineSnapshot.empty();
    private String preview="",workerRaw="",workerMode="pinyin",workerContext="",ninePrefix="",candidateMode="pinyin";
    private List<NineKeyCandidate> nineCandidates=Collections.emptyList();
    private final java.util.ArrayDeque<String[]> nineSegments=new java.util.ArrayDeque<>();
    private boolean english,numeric,sensitive,privateInput,engineOpen,selecting,composingActive;
    private volatile boolean destroyed;
    private volatile boolean translationReady;
    private final AtomicLong session=new AtomicLong();
    private long revision,visibleRevision,detailSession,detailRevision,detailRequest,predictionRequest,glossRequest;
    private Runnable pendingTranslation;
    private final Map<String,String> activeGlosses=new HashMap<>();
    private String engineError="",editorIdentity="",detailSource="",detailTranslated="",detailExplanation="",detailNote="",detailExplanationNote="";
    private String detailAnchorContext="";
    private int detailSelectedLength;
    private boolean detailAnchorRequired,predictionSuppressed;
    private int predictionPicks;
    private long modelsRevision=-1;
    private File dictFile;

    @Override public void onCreate(){
        super.onCreate();prefs=new ImePreferences(this);
        decoderThread=new HandlerThread("qingyu-input",android.os.Process.THREAD_PRIORITY_DISPLAY);decoderThread.start();decoder=new Handler(decoderThread.getLooper());
        translationThread=new HandlerThread("qingyu-gloss",android.os.Process.THREAD_PRIORITY_BACKGROUND);translationThread.start();translation=new Handler(translationThread.getLooper());
        clipboard=new ClipboardHistory(this,translation,()->sensitive||!prefs.clipboard(),()->{if(panels!=null&&panels.active().equals("clipboard"))showClipboard();});
        translations=new TranslationRepository(this,translation);
        decoder.post(()->{
            try{dictFile=new File(getFilesDir(),"pinyin-v1.dat");if(!dictFile.exists())copyAsset("pinyin/dict_pinyin.dat",dictFile);openEngine(!prefs.learning());}
            catch(Exception|LinkageError error){main.post(()->{engineError="中文词库暂不可用";render();});}
        });
        translation.post(()->{
            try{translations.open();translationReady=true;main.post(this::requestGlosses);}catch(Exception error){translationReady=false;}
        });
        // One startup job: expanding the English lexicon must not hold input or gloss events.
        new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try{LocalInputDictionary loaded=LocalInputDictionary.open(this);if(destroyed){loaded.close();return;}boolean queued=decoder.post(()->{if(destroyed){loaded.close();return;}inputDictionary=loaded;loaded.setLearningEnabled(prefs.learning()&&!privateInput);translations.setEnglishLookup(loaded::lookupEnglish);main.post(()->{if(destroyed)return;if(!preview.isEmpty()&&!currentMode().equals("pinyin"))submit(this::searchWorker,"",currentMode());else{requestGlosses();requestPrediction();}});});if(!queued)loaded.close();}
            catch(Exception error){main.post(()->{if(destroyed)return;engineError="辅助词库暂不可用，中文全拼仍可使用";render();});}
        },"qingyu-data-install").start();
    }
    private void copyAsset(String path,File destination)throws Exception{
        File temp=new File(destination.getPath()+".tmp");try(InputStream in=getAssets().open(path);FileOutputStream out=new FileOutputStream(temp)){byte[] data=new byte[32768];int n;while((n=in.read(data))!=-1)out.write(data,0,n);out.getFD().sync();}if(!temp.renameTo(destination))throw new java.io.IOException("Asset install failed");
    }
    private void openEngine(boolean privacy){if(engineOpen){engine.close();engineOpen=false;}engine.open(dictFile.getPath(),new File(getFilesDir(),"user-pinyin.dat").getPath());engine.setLearningEnabled(!privacy);engineOpen=true;resetWorker();}
    @Override public View onCreateInputView(){
        if(candidates!=null)candidates.animate().cancel();if(panels!=null)panels.toolbar.animate().cancel();
        dismissPinyinBubble();pinyinBubble=null;pinyinText=null;
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setClipChildren(true);root.setClipToPadding(true);root.setBackgroundColor(new Palette(prefs.dark(this)).background);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->renderPinyinBubble());
        root.setOnApplyWindowInsetsListener((v,insets)->{int bottom=insets.getSystemWindowInsetBottom(),left=0,right=0;if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.navigationBars());bottom=bars.bottom;left=bars.left;right=bars.right;}v.setPadding(left,0,right,bottom);return insets;});
        candidates=new CandidateSurface(this,prefs,this);expandedCandidates=new CandidateSurface(this,prefs,this);expandedCandidates.setEmbeddedGrid(true);keyboard=new KeyboardSurface(this,prefs,this);panels=new ImePanels(this,prefs,keyboard,this::panelAction);
        topRegion=new FrameLayout(this){@Override protected void onMeasure(int w,int h){super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(topHeight(),View.MeasureSpec.EXACTLY));}};
        topRegion.setClipChildren(true);topRegion.addView(candidates,new FrameLayout.LayoutParams(-1,topHeight()-dp(48)));FrameLayout.LayoutParams nav=new FrameLayout.LayoutParams(-1,dp(48),android.view.Gravity.BOTTOM);topRegion.addView(panels.toolbar,nav);
        navigationVisible=true;panels.onPanelChanged(this::renderTopRegion);root.addView(topRegion,new LinearLayout.LayoutParams(-1,-2));root.addView(panels.body,new LinearLayout.LayoutParams(-1,-2));configureKeyboard();render();return root;
    }
    @Override public void onComputeInsets(Insets out){
        super.onComputeInsets(out);if(root==null)return;int[] position=new int[2];root.getLocationInWindow(position);
        // The entire opaque IME, including its fixed candidate slot, reserves editor space.
        out.contentTopInsets=position[1];out.visibleTopInsets=position[1];
        out.touchableInsets=Insets.TOUCHABLE_INSETS_VISIBLE;
    }
    @Override public boolean onEvaluateFullscreenMode(){return false;}
    @Override public void onStartInput(EditorInfo info,boolean restarting){
        super.onStartInput(info,restarting);
        String identity=info.packageName+":"+info.fieldId+":"+info.inputType+":"+info.imeOptions;InputConnection ic=getCurrentInputConnection();CharSequence before=ic==null||preview.isEmpty()?null:ic.getTextBeforeCursor(preview.length(),0);
        if(restarting&&identity.equals(editorIdentity)&&!preview.isEmpty()&&before!=null&&before.toString().equals(preview)){restoreComposingSpan();applyLearningPreference(info);configureKeyboard();render();return;}
        if(!restarting||!identity.equals(editorIdentity))resetPredictionChain();editorIdentity=identity;long token=session.incrementAndGet();revision++;visibleRevision=revision;preview="";rawPreview="";visible=EngineSnapshot.empty();selecting=false;composingActive=false;
        int type=info.inputType,cls=type&InputType.TYPE_MASK_CLASS,variation=type&InputType.TYPE_MASK_VARIATION;
        numeric=cls==InputType.TYPE_CLASS_NUMBER||cls==InputType.TYPE_CLASS_PHONE||cls==InputType.TYPE_CLASS_DATETIME;
        sensitive=cls==InputType.TYPE_CLASS_TEXT&&(variation==InputType.TYPE_TEXT_VARIATION_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)||cls==InputType.TYPE_CLASS_NUMBER&&variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        boolean uri=cls==InputType.TYPE_CLASS_TEXT&&(variation==InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS||variation==InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS||variation==InputType.TYPE_TEXT_VARIATION_URI);
        english=sensitive||uri||prefs.store.getBoolean("english",false);privateInput=sensitive||(info.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0;
        decoder.post(()->{if(token!=session.get())return;try{if(!engineOpen)openEngine(privateInput||!prefs.learning());resetWorker();engine.setLearningEnabled(!privateInput&&prefs.learning());if(inputDictionary!=null)inputDictionary.setLearningEnabled(!privateInput&&prefs.learning());}catch(Exception|LinkageError ignored){}});
        if(keyboard!=null)keyboard.resetModes();collapse();configureKeyboard();render();requestPrediction();
    }
    @Override public void onStartInputView(EditorInfo info,boolean restarting){super.onStartInputView(info,restarting);inputViewActive=true;restoreComposingSpan();applyLearningPreference(info);configureKeyboard();long changed=prefs.store.getLong("models_revision",0);if(changed!=modelsRevision){modelsRevision=changed;translations.refreshAfterModelChange(prefs.glossLanguage(),state->requestGlosses());}else translations.refreshModels(prefs.glossLanguage(),null);render();if(root!=null)root.post(this::renderPinyinBubble);}
    private void applyLearningPreference(EditorInfo info){boolean privacy=sensitive||!prefs.learning()||(info.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0;long token=session.get();decoder.post(()->{if(token!=session.get())return;if(engineOpen)engine.setLearningEnabled(!privacy);if(inputDictionary!=null)inputDictionary.setLearningEnabled(!privacy);});}
    private void restoreComposingSpan(){InputConnection ic=getCurrentInputConnection();if(ic==null||preview.isEmpty())return;CharSequence before=ic.getTextBeforeCursor(preview.length(),0);if(before==null||!before.toString().equals(preview))return;android.view.inputmethod.ExtractedText text=ic.getExtractedText(new android.view.inputmethod.ExtractedTextRequest(),0);if(text!=null&&text.selectionStart==text.selectionEnd){int end=text.startOffset+text.selectionEnd;if(end>=preview.length()){ic.setComposingRegion(end-preview.length(),end);composingActive=true;}}}
    private String currentMode(){return english?"english":prefs.nineKey()?"nine":"pinyin";}
    private void configureKeyboard(){
        if(keyboard==null)return;EditorInfo info=getCurrentInputEditorInfo();String action="↵";if(info!=null&&(info.imeOptions&EditorInfo.IME_FLAG_NO_ENTER_ACTION)==0){switch(info.imeOptions&EditorInfo.IME_MASK_ACTION){case EditorInfo.IME_ACTION_GO:action="前往";break;case EditorInfo.IME_ACTION_SEARCH:action="搜索";break;case EditorInfo.IME_ACTION_SEND:action="发送";break;case EditorInfo.IME_ACTION_NEXT:action="下一项";break;case EditorInfo.IME_ACTION_DONE:action="完成";break;}}
        editorEnterLabel=action;keyboard.configure(english,numeric,action,sensitive);keyboard.setHapticFeedbackEnabled(prefs.haptic());if(panels!=null)panels.refresh();Palette colors=new Palette(prefs.dark(this));if(root!=null)root.setBackgroundColor(colors.background);Window window=getWindow().getWindow();if(window!=null){window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));window.setNavigationBarColor(colors.background);window.getDecorView().setSystemUiVisibility(prefs.dark(this)?0:View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);}
    }
    private void render(){
        if(candidates==null)return;List<String> words=new ArrayList<>();for(Candidate c:visible.candidates)words.add(c.text);String status=sensitive?"安全输入":engineError;
        keyboard.enterLabel(!english&&!numeric&&!sensitive&&!preview.isEmpty()&&currentMode().equals("pinyin")?"拼音":editorEnterLabel);renderPinyinBubble();
        candidates.setContentDescription("候选词，上方为释义；左右滑动浏览，长按查看详情，上滑输入翻译"+(!english&&!numeric&&!sensitive&&currentMode().equals("pinyin")&&!preview.isEmpty()?"；原始拼音 · "+rawPreview:""));
        if(words.isEmpty()&&isCandidateExpanded())collapse();
        boolean predicting=!words.isEmpty()&&candidateMode.startsWith("predict");
        candidates.setVisibility(View.VISIBLE);candidates.setInteractiveComposition(!preview.isEmpty());candidates.setPredicting(predicting);candidates.setExpandIndicator(isCandidateExpanded());candidates.update(preview,words,prefs.translation()&&!sensitive,status);
        renderTopRegion();
        if(isCandidateExpanded()){expandedCandidates.setInteractiveComposition(!preview.isEmpty());expandedCandidates.setPredicting(predicting);expandedCandidates.update(preview,words,prefs.translation()&&!sensitive,status);expandedCandidates.expanded(true);}requestGlosses();
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private int topHeight(){return dp(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?92:100);}
    private void renderTopRegion(){
        if(topRegion==null||panels==null)return;boolean show=preview.isEmpty()||sensitive||numeric||panels.isOpen()&&!isCandidateExpanded();int height=show?topHeight()-dp(48):topHeight();
        FrameLayout.LayoutParams layout=(FrameLayout.LayoutParams)candidates.getLayoutParams();
        if(show==navigationVisible){if(layout.height!=height){layout.height=height;candidates.setLayoutParams(layout);}return;}
        navigationVisible=show;candidates.animate().cancel();panels.toolbar.animate().cancel();
        panels.toolbar.setImportantForAccessibility(show?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);for(int i=0;i<panels.toolbar.getChildCount();i++)panels.toolbar.getChildAt(i).setEnabled(show);
        boolean animate=root.isAttachedToWindow()&&android.animation.ValueAnimator.areAnimatorsEnabled();
        // Set touch bounds once; animating the height cancels gestures on every size change.
        layout.height=height;candidates.setLayoutParams(layout);
        if(!animate){candidates.setAlpha(1f);panels.toolbar.setAlpha(1f);panels.toolbar.setVisibility(show?View.VISIBLE:View.GONE);return;}
        candidates.setAlpha(.82f);candidates.animate().alpha(1f).setDuration(110).start();
        panels.toolbar.setVisibility(View.VISIBLE);panels.toolbar.animate().alpha(show?1f:0f).setDuration(110).withEndAction(()->{if(!navigationVisible)panels.toolbar.setVisibility(View.GONE);}).start();
    }
    private void dismissPinyinBubble(){if(pinyinBubble!=null)pinyinBubble.dismiss();}
    private void renderPinyinBubble(){
        if(root==null||!inputViewActive||!root.isShown()||root.getWindowToken()==null||sensitive||numeric||english||!currentMode().equals("pinyin")||preview.isEmpty()||rawPreview.isEmpty()){dismissPinyinBubble();return;}
        if(root.getWidth()==0)return;
        Palette colors=new Palette(prefs.dark(this));
        if(pinyinText==null){
            pinyinText=new TextView(this);pinyinText.setId(R.id.pinyin_preedit);pinyinText.setTextSize(16);pinyinText.setSingleLine(true);pinyinText.setEllipsize(android.text.TextUtils.TruncateAt.END);pinyinText.setPadding(dp(12),dp(6),dp(12),dp(6));
            pinyinBubble=new PopupWindow(pinyinText,-2,-2,false);pinyinBubble.setTouchable(false);pinyinBubble.setOutsideTouchable(false);pinyinBubble.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);pinyinBubble.setClippingEnabled(false);pinyinBubble.setAnimationStyle(0);pinyinBubble.setElevation(0);
            if(android.os.Build.VERSION.SDK_INT>=29)pinyinBubble.setIsLaidOutInScreen(true);
        }
        pinyinText.setTextSize(16);pinyinText.setPadding(dp(12),dp(6),dp(12),dp(6));pinyinText.setText(rawPreview);pinyinText.setContentDescription("原始拼音 · "+rawPreview);pinyinText.setTextColor(colors.text);
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(colors.background);background.setCornerRadii(new float[]{dp(9),dp(9),dp(9),dp(9),0,0,0,0});pinyinText.setBackground(background);
        int widthLimit=Math.max(dp(48),root.getWidth()-root.getPaddingLeft()-root.getPaddingRight()-dp(16));
        // PopupWindow gives its content a fixed width; equal-height text updates can cache the old measurement.
        pinyinText.forceLayout();pinyinText.measure(View.MeasureSpec.makeMeasureSpec(widthLimit,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int[] position=new int[2];root.getLocationOnScreen(position);int x=position[0]+root.getPaddingLeft()+dp(8),y=Math.max(dp(4),position[1]-pinyinText.getMeasuredHeight());
        try{if(pinyinBubble.isShowing())pinyinBubble.update(x,y,pinyinText.getMeasuredWidth(),pinyinText.getMeasuredHeight());else{pinyinBubble.setWidth(pinyinText.getMeasuredWidth());pinyinBubble.setHeight(pinyinText.getMeasuredHeight());pinyinBubble.showAtLocation(root,android.view.Gravity.TOP|android.view.Gravity.LEFT,x,y);}}catch(android.view.WindowManager.BadTokenException ignored){dismissPinyinBubble();}
    }
    private boolean glossMatches(long token,long generation,String source,String target){return !destroyed&&session.get()==token&&revision==generation&&prefs.translation()&&!sensitive&&source.equals(english?"en":"zh")&&target.equals(english?"zh":prefs.glossLanguage());}
    private void requestGlosses(){
        if(candidates==null||destroyed||sensitive||visible.candidates.isEmpty())return;
        if(pendingTranslation!=null)translation.removeCallbacks(pendingTranslation);long token=session.get(),generation=revision,request=++glossRequest;String source=english?"en":"zh",target=english?"zh":prefs.glossLanguage();boolean withGloss=prefs.translation()&&translationReady;List<String> words=new ArrayList<>(candidates.words()),displayed=new ArrayList<>((isCandidateExpanded()?expandedCandidates:candidates).visibleWords());
        pendingTranslation=()->{
            if(token!=session.get())return;Map<String,String> found=new HashMap<>();List<String> missing=new ArrayList<>();
            Map<String,String> pos=Collections.emptyMap();try{if(source.equals("zh")&&inputDictionary!=null)pos=inputDictionary.partsOfSpeech(words);}catch(Exception ignored){}Map<String,String> tags=pos;
            if(withGloss)for(String word:words){String gloss="";try{gloss=translations.localGloss(word,source,target);}catch(Exception ignored){}if(!gloss.isEmpty())found.put(word,gloss);else if(displayed.contains(word))missing.add(word);}
            main.post(()->{if(destroyed||request!=glossRequest||token!=session.get()||generation!=revision)return;candidates.partsOfSpeech(tags);expandedCandidates.partsOfSpeech(tags);if(withGloss&&glossMatches(token,generation,source,target)){activeGlosses.clear();activeGlosses.putAll(found);applyGlosses(false);if(translations.status(source.equals("en")?"en":target)==TranslationRepository.State.READY)requestModelGlosses(missing,0,token,generation,request,source,target);}});
        };
        // Coalesce glosses, never input events. A fast typist must not queue inference per key.
        translation.postDelayed(pendingTranslation,100);
    }
    @Override public void visibleWordsChanged(){requestGlosses();}
    private void applyGlosses(boolean google){Map<String,String> values=new HashMap<>(activeGlosses);candidates.setGoogleTranslation(google);candidates.glosses(values);expandedCandidates.setGoogleTranslation(google);expandedCandidates.glosses(values);}
    private void requestModelGlosses(List<String> words,int index,long token,long generation,long request,String source,String target){
        if(index>=words.size()||request!=glossRequest||!glossMatches(token,generation,source,target))return;String word=words.get(index);
        // One visible-page inference at a time; moving the page or typing cancels the remaining queue.
        translations.translate(word,source,target,(value,note)->{if(request!=glossRequest||!glossMatches(token,generation,source,target))return;if(!value.isEmpty()){activeGlosses.put(word,value);applyGlosses(note.startsWith("Google Translate"));}requestModelGlosses(words,index+1,token,generation,request,source,target);});
    }
    private EngineSnapshot snapshot(String raw,List<String> words,String commit){List<Candidate> items=new ArrayList<>();for(int i=0;i<words.size()&&i<128;i++)items.add(new Candidate(i,words.get(i)));return new EngineSnapshot(raw,workerRaw,items,commit);}
    private EngineSnapshot searchWorker(){
        if(workerMode.equals("english")){List<String> words=inputDictionary==null?Collections.emptyList():inputDictionary.english().suggest(workerRaw,workerContext);return snapshot(workerRaw,words,"");}
        if(workerMode.equals("nine")){nineCandidates=inputDictionary==null?Collections.emptyList():inputDictionary.suggestNineKey(workerRaw);List<String> words=new ArrayList<>();for(NineKeyCandidate c:nineCandidates)words.add(c.text);return snapshot(ninePrefix+workerRaw,words,"");}
        return engineOpen?engine.search(workerRaw):new EngineSnapshot(workerRaw,workerRaw,Collections.emptyList(),"");
    }
    private void resetWorker(){if(engineOpen)engine.reset();workerRaw="";ninePrefix="";nineSegments.clear();nineCandidates=Collections.emptyList();}
    private EngineSnapshot finishWorker(){
        String commit="";
        if(workerMode.equals("english")){commit=workerRaw;if(inputDictionary!=null&&!workerRaw.isEmpty())inputDictionary.english().learn(workerRaw,workerContext);}
        else if(workerMode.equals("nine")){
            commit=ninePrefix;String remaining=workerRaw;for(int step=0;step<64&&!remaining.isEmpty();step++){List<NineKeyCandidate> found=inputDictionary==null?Collections.emptyList():inputDictionary.suggestNineKey(remaining);if(found.isEmpty()){commit+=remaining;break;}NineKeyCandidate first=found.get(0);if(first.consumedDigits<=0||first.consumedDigits>remaining.length()){commit+=remaining;break;}commit+=first.text;remaining=remaining.substring(first.consumedDigits);}
        }else if(!workerRaw.isEmpty()){
            if(engineOpen){EngineSnapshot state=engine.search(workerRaw);for(int i=0;i<64&&state.committedText.isEmpty()&&!state.candidates.isEmpty();i++)state=engine.select(state.candidates.get(0).id);commit=state.committedText.isEmpty()?state.composing:state.committedText;}else commit=workerRaw;
        }
        resetWorker();return new EngineSnapshot("","",Collections.emptyList(),commit);
    }
    private interface DecoderAction{EngineSnapshot run()throws Exception;}
    private void submit(DecoderAction action,String suffix,String mode){
        long token=session.get(),generation=++revision;String context=contextBeforeComposition();
        decoder.post(()->{
            if(token!=session.get())return;EngineSnapshot result;String committed=suffix;
            try{result=action.run();committed=result.committedText+suffix;workerRaw=result.rawPinyin;}catch(Exception|LinkageError error){result=new EngineSnapshot(workerRaw,workerRaw,Collections.emptyList(),"");}
            EngineSnapshot output=result;String commit=committed;String original=output.composing.isEmpty()?"":mode.equals("pinyin")&&engineOpen?engine.rawInput():output.rawPinyin;
            main.post(()->{
                if(destroyed||token!=session.get())return;InputConnection ic=getCurrentInputConnection();if(ic==null)return;if(!commit.isEmpty()){ic.commitText(commit,1);composingActive=false;}
                if(!commit.isEmpty()&&!mode.equals("english")&&!privateInput&&prefs.learning()&&inputDictionary!=null)decoder.post(()->{if(token==session.get())inputDictionary.learnChinese(commit,context);});
                if(generation==revision){visible=output;visibleRevision=generation;candidateMode=mode;preview=output.composing;rawPreview=original;updateComposing(ic);render();if(preview.isEmpty())requestPrediction();}
            });
        });
    }
    private void editorAction(Runnable action){long token=session.get();decoder.post(()->main.post(()->{if(!destroyed&&token==session.get()&&getCurrentInputConnection()!=null)action.run();}));}
    private String contextBeforeComposition(){InputConnection ic=getCurrentInputConnection();CharSequence before=ic==null?null:ic.getTextBeforeCursor(512,0);String text=before==null?"":before.toString();if(!preview.isEmpty()&&text.endsWith(preview))text=text.substring(0,text.length()-preview.length());return text;}
    private void requestPrediction(){
        if(destroyed||sensitive||numeric||predictionSuppressed||predictionPicks>=3||!preview.isEmpty()||getCurrentInputConnection()==null)return;String context=contextBeforeComposition();long predictionToken=++predictionRequest;if(context.trim().isEmpty()){if(candidateMode.startsWith("predict")){visible=EngineSnapshot.empty();render();}return;}long token=session.get(),generation=revision;boolean en=english;
        decoder.post(()->{
            if(token!=session.get()||!workerRaw.isEmpty()||inputDictionary==null)return;List<String> words;
            if(en)words=inputDictionary.english().suggest("",context);
            else{LinkedHashSet<String> all=new LinkedHashSet<>();if(engineOpen)all.addAll(engine.predict(context));all.addAll(inputDictionary.predictChinese(context));words=new ArrayList<>(all);}
            List<Candidate> items=new ArrayList<>();for(int i=0;i<words.size()&&i<64;i++)items.add(new Candidate(i,words.get(i)));EngineSnapshot prediction=new EngineSnapshot("","",items,"");
            main.post(()->{if(!destroyed&&!predictionSuppressed&&predictionPicks<3&&token==session.get()&&generation==revision&&predictionToken==predictionRequest&&preview.isEmpty()&&en==english&&context.equals(contextBeforeComposition())){visible=prediction;visibleRevision=generation;candidateMode=en?"predict_en":"predict_zh";render();}});
        });
    }
    private void resetPredictionChain(){predictionPicks=0;predictionSuppressed=false;predictionRequest++;}
    @Override public void clearCandidates(){predictionSuppressed=true;predictionRequest++;glossRequest++;detailRequest++;if(pendingTranslation!=null)translation.removeCallbacks(pendingTranslation);visible=EngineSnapshot.empty();activeGlosses.clear();collapse();render();}
    private void updateComposing(InputConnection ic){if(!preview.isEmpty()){ic.setComposingText(preview,1);composingActive=true;}else{if(composingActive)ic.setComposingText("",1);ic.finishComposingText();composingActive=false;}}
    private void showImmediate(){InputConnection ic=getCurrentInputConnection();if(ic!=null)updateComposing(ic);visible=EngineSnapshot.empty();render();}
    private void directCommit(String text){resetPredictionChain();String mode=currentMode();submit(this::finishWorker,text,mode);preview="";rawPreview="";visible=EngineSnapshot.empty();render();collapse();}
    @Override public void key(String value){
        if(getCurrentInputConnection()==null)return;
        switch(value){
            case "SHIFT":keyboard.shift();return;
            case "?123":case "ABC":keyboard.toggleSymbols();if(numeric){numeric=false;english=true;configureKeyboard();}return;
            case "LANG":if(!sensitive)changeMode(!english,prefs.keyboardMode());return;
            case "SPACE":{resetPredictionChain();String mode=currentMode();preview="";rawPreview="";visible=EngineSnapshot.empty();render();collapse();submit(()->{boolean empty=workerRaw.isEmpty()&&ninePrefix.isEmpty();EngineSnapshot finished=finishWorker();return new EngineSnapshot("","",Collections.emptyList(),finished.committedText+(mode.equals("english")||empty?" ":""));},"",mode);return;}
            case "ENTER":resetPredictionChain();enter();return;case "⌫":resetPredictionChain();backspace();return;
            default:
                resetPredictionChain();
                boolean letter=value.length()==1&&value.charAt(0)>='a'&&value.charAt(0)<='z';String mode=currentMode();
                boolean compose=!sensitive&&!numeric&&!keyboard.isSymbols()&&(english?letter:mode.equals("nine")?value.length()==1&&value.charAt(0)>='2'&&value.charAt(0)<='9':letter&&!keyboard.uppercase()||value.equals("'")&&!preview.isEmpty());
                if(compose){
                    String text=english&&keyboard.uppercase()?value.toUpperCase(java.util.Locale.ROOT):value;String context=contextBeforeComposition();rawPreview=preview.length()>=64?text:rawPreview+text;preview=preview.length()>=64?text:preview+text;showImmediate();
                    submit(()->{String committed="";if(!workerMode.equals(mode)){resetWorker();workerMode=mode;}if(workerRaw.length()>=64)committed=finishWorker().committedText;workerContext=context;workerRaw+=text;EngineSnapshot state=searchWorker();return committed.isEmpty()?state:new EngineSnapshot(state.composing,state.rawPinyin,state.candidates,committed);},"",mode);if(english)keyboard.consumedLetter();
                }else{String text=letter&&keyboard.uppercase()?value.toUpperCase(java.util.Locale.ROOT):value;directCommit(text);if(letter)keyboard.consumedLetter();}
        }
    }
    private void changeMode(boolean en,String mode){if(sensitive)return;resetPredictionChain();preview="";rawPreview="";visible=EngineSnapshot.empty();submit(this::finishWorker,"",en?"english":mode.equals("t9")?"nine":"pinyin");english=en;numeric=false;prefs.store.edit().putBoolean("english",en).putString("keyboard_mode",mode).apply();keyboard.resetModes();collapse();configureKeyboard();render();}
    private void enter(){
        EditorInfo info=getCurrentInputEditorInfo();String mode=currentMode();int action=info==null?EditorInfo.IME_ACTION_NONE:info.imeOptions&EditorInfo.IME_MASK_ACTION;long token=session.get();preview="";rawPreview="";visible=EngineSnapshot.empty();collapse();
        render();submit(()->{if(!workerRaw.isEmpty()||!ninePrefix.isEmpty()){if(workerMode.equals("pinyin")){String raw=engineOpen?engine.rawInput():workerRaw;resetWorker();return new EngineSnapshot("","",Collections.emptyList(),raw);}return finishWorker();}if(info!=null&&(info.imeOptions&EditorInfo.IME_FLAG_NO_ENTER_ACTION)==0&&action!=EditorInfo.IME_ACTION_NONE&&action!=EditorInfo.IME_ACTION_UNSPECIFIED){main.post(()->{if(token==session.get()&&getCurrentInputConnection()!=null)getCurrentInputConnection().performEditorAction(action);});return EngineSnapshot.empty();}return new EngineSnapshot("","",Collections.emptyList(),"\n");},"",mode);
    }
    private void backspace(){
        if(!preview.isEmpty()){preview=preview.substring(0,preview.offsetByCodePoints(preview.length(),-1));if(!rawPreview.isEmpty())rawPreview=rawPreview.substring(0,rawPreview.length()-1);showImmediate();}String mode=currentMode();long token=session.get();
        submit(()->{if(!workerRaw.isEmpty()){if(workerMode.equals("pinyin")&&engineOpen)return engine.backspace();workerRaw=workerRaw.substring(0,workerRaw.length()-1);return searchWorker();}if(workerMode.equals("nine")&&!nineSegments.isEmpty()){String[] segment=nineSegments.removeLast();ninePrefix=ninePrefix.substring(0,ninePrefix.length()-segment[1].length());workerRaw=segment[0];return searchWorker();}main.post(()->{if(token!=session.get())return;InputConnection ic=getCurrentInputConnection();if(ic==null)return;CharSequence selected=ic.getSelectedText(0);if(selected!=null&&selected.length()>0)ic.commitText("",1);else ic.deleteSurroundingTextInCodePoints(1,0);});return EngineSnapshot.empty();},"",mode);
    }
    @Override public void choose(int index){
        if(visibleRevision!=revision||index<0||index>=visible.candidates.size())return;String kind=candidateMode,word=visible.candidates.get(index).text;int id=visible.candidates.get(index).id;String context=contextBeforeComposition();
        if(kind.startsWith("predict"))predictionPicks++;else resetPredictionChain();visible=EngineSnapshot.empty();render();
        submit(()->{
            if(kind.startsWith("predict")){resetWorker();if(inputDictionary!=null){if(kind.equals("predict_en"))inputDictionary.english().learn(word,context);else inputDictionary.learnChinese(word,context);}return new EngineSnapshot("","",Collections.emptyList(),word+(kind.equals("predict_en")?" ":""));}
            if(kind.equals("english")){if(inputDictionary!=null)inputDictionary.english().learn(word,workerContext);resetWorker();return new EngineSnapshot("","",Collections.emptyList(),word+" ");}
            if(kind.equals("nine")){if(id>=nineCandidates.size())return searchWorker();NineKeyCandidate c=nineCandidates.get(id);int consumed=Math.min(workerRaw.length(),c.consumedDigits);if(consumed<=0)return searchWorker();nineSegments.addLast(new String[]{workerRaw.substring(0,consumed),c.text});ninePrefix+=c.text;workerRaw=workerRaw.substring(consumed);if(workerRaw.isEmpty()){String commit=ninePrefix;resetWorker();return new EngineSnapshot("","",Collections.emptyList(),commit);}return searchWorker();}
            return engineOpen?engine.select(id):EngineSnapshot.empty();
        },"",kind.equals("predict_en")?"english":kind.equals("predict_zh")?currentMode():kind);collapse();
    }
    private boolean isCandidateExpanded(){return panels!=null&&panels.active().equals("candidates");}
    @Override public void expand(){if(candidates==null||visible.candidates.isEmpty()||preview.isEmpty())return;if(isCandidateExpanded()){collapse();requestGlosses();return;}List<String> words=new ArrayList<>();for(Candidate c:visible.candidates)words.add(c.text);expandedCandidates.setInteractiveComposition(true);expandedCandidates.setPredicting(false);expandedCandidates.update(preview,words,prefs.translation()&&!sensitive,engineError);expandedCandidates.expanded(true);expandedCandidates.glosses(new HashMap<>(activeGlosses));panels.showCandidates(expandedCandidates);candidates.setExpandIndicator(true);requestGlosses();}
    private void collapse(){if(candidates!=null)candidates.setExpandIndicator(false);if(panels!=null)panels.close();else if(keyboard!=null)keyboard.setVisibility(View.VISIBLE);}
    private void openSettings(boolean modelManager){startActivity(new Intent(this,SettingsActivity.class).putExtra("model_manager",modelManager).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
    private void checkUpdates(){startActivity(new Intent(this,SettingsActivity.class).putExtra("check_updates",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
    @Override public void settings(){openSettings(false);}
    @Override public void toggleTranslation(){prefs.store.edit().putBoolean("translation",!prefs.translation()).apply();activeGlosses.clear();if(candidates!=null)applyGlosses(false);render();}
    @Override public void punctuation(String text){key(english?text.replace('，',',').replace('。','.').replace('？','?').replace('！','!').replace('：',':'):text);}
    @Override public void cursor(int direction){
        finishBeforeEditing();
        int count=Math.min(12,Math.abs(direction)),code=direction<0?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT;editorAction(()->{InputConnection ic=getCurrentInputConnection();boolean extend=selecting;int meta=extend?KeyEvent.META_SHIFT_ON:0;if(extend)ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SHIFT_LEFT));try{for(int i=0;i<count;i++){ic.sendKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_DOWN,code,0,meta));ic.sendKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_UP,code,0,meta));}}finally{if(extend)ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT));}requestPrediction();});
    }
    @Override public void longKey(String value){
        if(value.startsWith("DIRECT_")){directCommit(value.substring(7));return;}
        switch(value){case "LANG":((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();break;case "SHIFT":keyboard.shift();break;case "APOSTROPHE":if(!prefs.nineKey())key("'");break;
            case "DELETE_WORD":if(!preview.isEmpty()){preview="";rawPreview="";showImmediate();submit(()->{resetWorker();return EngineSnapshot.empty();},"",currentMode());}else editorAction(()->{InputConnection ic=getCurrentInputConnection();CharSequence before=ic.getTextBeforeCursor(128,0);if(before!=null){String s=before.toString();int n=s.length();while(n>0&&Character.isWhitespace(s.charAt(n-1)))n--;while(n>0&&!Character.isWhitespace(s.charAt(n-1)))n--;ic.deleteSurroundingText(s.length()-n,0);requestPrediction();}});break;
            case ",":case "，":key("、");break;case ".":case "。":key("…");break;case "ENTER":key("\n");break;default:break;}
    }
    private String nineTranslationSource(int id){
        if(id<0||id>=nineCandidates.size())return "";NineKeyCandidate selected=nineCandidates.get(id);int consumed=selected.consumedDigits;if(consumed<=0||consumed>workerRaw.length())return "";
        StringBuilder text=new StringBuilder(ninePrefix).append(selected.text);String remaining=workerRaw.substring(consumed);
        for(int step=0;step<64&&!remaining.isEmpty();step++){List<NineKeyCandidate> found=inputDictionary.suggestNineKey(remaining);if(found.isEmpty()){text.append(remaining);break;}NineKeyCandidate first=found.get(0);if(first.consumedDigits<=0||first.consumedDigits>remaining.length()){text.append(remaining);break;}text.append(first.text);remaining=remaining.substring(first.consumedDigits);}
        return text.toString();
    }
    @Override public void detail(int index){translateCandidate(index,false);}
    @Override public void translate(int index){translateCandidate(index,true);}
    private void translateCandidate(int index,boolean commitOnReady){
        if(sensitive||getCurrentInputConnection()==null||visibleRevision!=revision||index<0||index>=visible.candidates.size())return;String kind=candidateMode,word=visible.candidates.get(index).text;int id=visible.candidates.get(index).id;String language=kind.equals("english")||kind.equals("predict_en")?"en":"zh",target=language.equals("en")?"zh":prefs.glossLanguage();long token=session.get(),generation=revision,request=++detailRequest;
        detailAnchorRequired=kind.startsWith("predict");detailAnchorContext=contextBeforeComposition();CharSequence selected=getCurrentInputConnection().getSelectedText(0);detailSelectedLength=selected==null?0:selected.length();
        if(!commitOnReady){panels.detail(word,"正在查找完整释义…");candidates.setExpandIndicator(false);}
        decoder.post(()->{if(token!=session.get())return;String resolved=word;try{if(kind.equals("pinyin")&&engineOpen)resolved=engine.previewCandidate(id);else if(kind.equals("nine")&&inputDictionary!=null)resolved=nineTranslationSource(id);}catch(Exception|LinkageError ignored){resolved="";}String source=resolved;
            main.post(()->{if(!detailMatches(token,generation,request))return;detailSource=source;detailTranslated="";detailExplanation="";detailNote="";detailExplanationNote="";detailSession=token;detailRevision=generation;
                translations.translate(source,language,target,(value,note)->{if(!detailMatches(token,generation,request))return;detailTranslated=value;detailNote=note;if(commitOnReady&&!value.isEmpty()){commitTranslation(value);return;}if(commitOnReady)panels.detail(source,note);showDetailResult();});
                if(!commitOnReady)translations.describe(source,language,target,(value,note)->{if(!detailMatches(token,generation,request))return;detailExplanation=value;detailExplanationNote=note;showDetailResult();});
            });
        });
    }
    private boolean detailAnchorMatches(){if(!detailAnchorRequired)return true;InputConnection ic=getCurrentInputConnection();if(ic==null||!detailAnchorContext.equals(contextBeforeComposition()))return false;CharSequence selected=ic.getSelectedText(0);return detailSelectedLength==(selected==null?0:selected.length());}
    private boolean detailMatches(long token,long generation,long request){return !destroyed&&token==session.get()&&generation==revision&&request==detailRequest&&detailAnchorMatches();}
    private void showDetailResult(){panels.detailResult(detailSource,detailTranslated,detailNote+(detailExplanation.isEmpty()?"":"\n\n"+detailExplanationNote+"\n"+detailExplanation));}
    private void commitTranslation(String value){if(value.isEmpty()||sensitive||detailSession!=session.get()||detailRevision!=revision||!detailAnchorMatches())return;InputConnection ic=getCurrentInputConnection();if(ic==null)return;long token=session.get();revision++;preview="";rawPreview="";visible=EngineSnapshot.empty();composingActive=false;
        // Commit in the same main-thread callback as the anchor check; a decoder round-trip lets the cursor move in between.
        if(detailAnchorRequired)predictionPicks++;else resetPredictionChain();ic.commitText(value,1);decoder.post(()->{if(token==session.get())resetWorker();});collapse();render();requestPrediction();}
    private String modelStatus(){return TranslationRepository.languageName(prefs.glossLanguage())+" · "+TranslationRepository.stateText(translations.status(prefs.glossLanguage()));}
    private void showClipboard(){if(panels==null||sensitive)return;panels.clipboard(clipboard.snapshot(),this::directCommit,clipboard::remove);}
    private void panelAction(String action){
        if(panels==null)return;
        if(isCandidateExpanded())candidates.setExpandIndicator(false);
        switch(action){
            case "keyboard":collapse();return;case "hide":keyboard.cancelTouch();requestHideSelf(0);return;
            case "more":if(panels.active().equals("more")){collapse();return;}panels.more();return;case "edit":panels.edit(sensitive,selecting);return;case "emoji":panels.emoji();return;case "mode":panels.modes();return;
            case "languages":panels.languages(modelStatus());translations.refreshModels(prefs.glossLanguage(),state->panels.modelStatus(modelStatus()));return;case "settings":settings();return;case "model_manager":openSettings(true);return;case "check_updates":checkUpdates();return;
            case "project_home":try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(AppUpdate.PROJECT_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(android.content.ActivityNotFoundException ignored){android.widget.Toast.makeText(this,"未找到可打开项目主页的浏览器",android.widget.Toast.LENGTH_SHORT).show();}return;
            case "height":panels.height();return;case "height_changed":configureKeyboard();return;case "style":panels.styles();return;
            case "haptic":prefs.store.edit().putBoolean("haptic",!prefs.haptic()).apply();configureKeyboard();panels.more();return;case "toggle_gloss":toggleTranslation();panels.languages(modelStatus());return;
            case "download_models":translations.ensureModels(prefs.glossLanguage(),state->{panels.modelStatus(modelStatus());if(state==TranslationRepository.State.READY)requestGlosses();});return;
            case "mode_full":changeMode(false,"full");return;case "mode_t9":changeMode(false,"t9");return;case "mode_english":changeMode(true,prefs.keyboardMode());return;
            case "left":cursor(-1);return;case "right":cursor(1);return;case "select":selecting=!selecting;panels.edit(sensitive,selecting);return;
            case "select_all":editContext(android.R.id.selectAll);return;case "copy":if(!sensitive)editContext(android.R.id.copy);return;case "cut":if(!sensitive)editContext(android.R.id.cut);return;case "paste":editContext(android.R.id.paste);return;
            case "clipboard":showClipboard();return;case "clear_clipboard":clipboard.clear();return;
            case "home":case "end":finishBeforeEditing();int code=action.equals("home")?KeyEvent.KEYCODE_MOVE_HOME:KeyEvent.KEYCODE_MOVE_END;editorAction(()->{InputConnection ic=getCurrentInputConnection();ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code));ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code));requestPrediction();});return;
            case "commit_translation":commitTranslation(detailTranslated);return;case "copy_translation":if(!detailTranslated.isEmpty())clipboard.copy(detailTranslated);return;
            default:if(action.startsWith("language_")){detailRequest++;String lang=action.substring(9);if(!TranslationRepository.isGlossLanguage(lang))return;prefs.store.edit().putString("gloss_language",lang).putBoolean("translation",true).apply();activeGlosses.clear();applyGlosses(false);render();panels.languages(modelStatus());TranslationRepository.StateCallback changed=state->{if(!lang.equals(prefs.glossLanguage()))return;panels.modelStatus(modelStatus());if(state==TranslationRepository.State.READY)requestGlosses();};if(lang.equals("en"))translations.refreshModels(lang,changed);else translations.ensureModels(lang,changed);}else if(action.startsWith("style_")){prefs.store.edit().putString("style",action.substring(6)).apply();configureKeyboard();panels.styles();}else if(action.startsWith("emoji_"))directCommit(action.substring(6));
        }
    }
    private void editContext(int id){if(sensitive&&(id==android.R.id.copy||id==android.R.id.cut||id==android.R.id.selectAll))return;submit(this::finishWorker,"",currentMode());editorAction(()->{InputConnection ic=getCurrentInputConnection();boolean applied=ic.performContextMenuAction(id);if(!applied&&id==android.R.id.paste){String text=clipboard.currentText();if(!text.isEmpty())ic.commitText(text,1);}if(id==android.R.id.copy||id==android.R.id.cut)clipboard.record(clipboard.currentText());requestPrediction();});}
    private void finishBeforeEditing(){if(!preview.isEmpty()){preview="";rawPreview="";visible=EngineSnapshot.empty();submit(this::finishWorker,"",currentMode());render();}}
    @Override public void onUpdateSelection(int oldStart,int oldEnd,int newStart,int newEnd,int candidatesStart,int candidatesEnd){
        super.onUpdateSelection(oldStart,oldEnd,newStart,newEnd,candidatesStart,candidatesEnd);InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        if(!preview.isEmpty()){
            // Selection notifications may be coalesced or older than our immediate preedit.
            // Inspect the actual cursor text before cancelling an ordered input transaction.
            CharSequence before=ic.getTextBeforeCursor(preview.length(),0),selected=ic.getSelectedText(0);
            if(before==null)return;
            if(before!=null&&before.toString().equals(preview)&&(selected==null||selected.length()==0))return;
            session.incrementAndGet();revision++;detailRequest++;preview="";rawPreview="";visible=EngineSnapshot.empty();composingActive=false;ic.finishComposingText();decoder.post(this::resetWorker);collapse();render();requestPrediction();
        }else if(oldStart!=newStart||oldEnd!=newEnd){if(detailAnchorRequired&&!detailAnchorMatches()){detailRequest++;detailRevision=-1;}requestPrediction();}
    }
    @Override public boolean onKeyDown(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_BACK&&panels!=null&&panels.isOpen()){collapse();return true;}return super.onKeyDown(code,event);}
    @Override public void onFinishInputView(boolean finishing){inputViewActive=false;dismissPinyinBubble();if(keyboard!=null)keyboard.cancelTouch();if(candidates!=null)candidates.cancelTouch();if(expandedCandidates!=null)expandedCandidates.cancelTouch();collapse();super.onFinishInputView(finishing);}
    @Override public void onFinishInput(){dismissPinyinBubble();rawPreview="";if(destroyed){super.onFinishInput();return;}session.incrementAndGet();revision++;preview="";rawPreview="";visible=EngineSnapshot.empty();composingActive=false;decoder.post(()->{resetWorker();if(engineOpen&&!privateInput)engine.flush();if(inputDictionary!=null)try{inputDictionary.flush();}catch(Exception ignored){}});if(pendingTranslation!=null)translation.removeCallbacks(pendingTranslation);super.onFinishInput();}
    @Override public void onConfigurationChanged(Configuration config){dismissPinyinBubble();super.onConfigurationChanged(config);restoreComposingSpan();configureKeyboard();if(panels!=null&&panels.isOpen())collapse();if(root!=null)root.requestLayout();}
    @Override public void onDestroy(){destroyed=true;dismissPinyinBubble();session.incrementAndGet();main.removeCallbacksAndMessages(null);if(clipboard!=null)clipboard.close();translations.close();decoder.post(()->{if(engineOpen){engine.close();engineOpen=false;}if(inputDictionary!=null){inputDictionary.close();inputDictionary=null;}decoderThread.quitSafely();});translation.post(()->translationThread.quitSafely());super.onDestroy();}
}
