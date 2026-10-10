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
    private String rawPreview="",nineRawPreview="",nineVisiblePrefix="",editorEnterLabel="↵";
    private List<String> nineReadings=Collections.emptyList();
    private String nineReading="",nineVisibleReading="";
    private boolean temporaryNumeric;
    private boolean inputViewActive;
    private EngineSnapshot visible=EngineSnapshot.empty();
    private String preview="",workerRaw="",workerMode="pinyin",workerContext="",ninePrefix="",candidateMode="pinyin";
    private List<NineKeyCandidate> nineCandidates=Collections.emptyList();
    private final java.util.ArrayDeque<String[]> nineSegments=new java.util.ArrayDeque<>();
    private boolean english,numeric,sensitive,privateInput,engineOpen,selecting,composingActive;
    private volatile boolean destroyed;
    private volatile boolean translationReady;
    private final AtomicLong session=new AtomicLong();
    private volatile long revision;
    private long visibleRevision,detailSession,detailRevision,detailRequest,predictionRequest,glossRequest;
    private boolean skipNineSearch;
    private Runnable pendingTranslation;
    private final Map<String,String> activeGlosses=new HashMap<>();
    private final java.util.Set<String> activeModelWords=new LinkedHashSet<>();
    private String glossPair="",glossBatchKey="",detailTarget="",detailPos="",detailExample="",detailExampleTranslation="",detailExampleNote="";
    private String engineError="",editorIdentity="",detailSource="",detailTranslated="",detailExplanation="",detailNote="",detailExplanationNote="";
    private String detailAnchorContext="";
    private int detailSelectedLength;
    private boolean detailAnchorRequired,predictionSuppressed;
    private int predictionPicks;
    private long modelsRevision=-1;
    private File dictFile;
    private final EditorHistory editHistory=new EditorHistory();
    private long editTranslationRequest,editPanelRevision;
    private boolean editTranslateBusy;
    private String editStatus="撤回仅作用于当前输入框";

    @Override public void onCreate(){
        super.onCreate();prefs=new ImePreferences(this);
        decoderThread=new HandlerThread("qingyu-input",android.os.Process.THREAD_PRIORITY_DISPLAY);decoderThread.start();decoder=new Handler(decoderThread.getLooper());
        translationThread=new HandlerThread("qingyu-gloss",android.os.Process.THREAD_PRIORITY_BACKGROUND);translationThread.start();translation=new Handler(translationThread.getLooper());
        clipboard=new ClipboardHistory(this,translation,()->sensitive||!prefs.clipboard(),()->{if(panels!=null&&panels.active().equals("clipboard"))showClipboard();});
        translations=new TranslationRepository(this,translation);
        decoder.post(()->{
            try{dictFile=new File(getFilesDir(),"pinyin-v2.dat");if(!dictFile.exists())copyAsset("pinyin/dict_pinyin.dat",dictFile);openEngine(!prefs.learning());}
            catch(Exception|LinkageError error){main.post(()->{engineError="中文词库暂不可用";render();});}
        });
        translation.post(()->{
            try{translations.open();translationReady=true;main.post(this::requestGlosses);}catch(Exception error){translationReady=false;}
        });
        // One startup job: expanding the English lexicon must not hold input or gloss events.
        new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);long started=android.os.SystemClock.uptimeMillis();
            // Input dictionaries need CPU during startup even while the foreground app is busy.
            try{LocalInputDictionary loaded=LocalInputDictionary.open(this,partial->attachDictionary(partial,started,false));attachDictionary(loaded,started,true);}
            catch(Exception error){android.util.Log.w("QingyuInput","Auxiliary dictionary initialization failed",error);main.post(()->{if(destroyed)return;engineError="辅助词库暂不可用，中文全拼仍可使用";render();});}
        },"qingyu-data-install").start();
    }
    private void copyAsset(String path,File destination)throws Exception{
        File temp=new File(destination.getPath()+".tmp");try(InputStream in=getAssets().open(path);FileOutputStream out=new FileOutputStream(temp)){byte[] data=new byte[32768];int n;while((n=in.read(data))!=-1)out.write(data,0,n);out.getFD().sync();}if(!temp.renameTo(destination))throw new java.io.IOException("Asset install failed");
    }
    private void attachDictionary(LocalInputDictionary loaded,long started,boolean complete){
        if(destroyed){loaded.close();return;}
        boolean queued=decoder.post(()->{
            if(destroyed){loaded.close();return;}
            boolean first=inputDictionary!=loaded;inputDictionary=loaded;loaded.setLearningEnabled(prefs.learning()&&!privateInput);
            if(first&&engineOpen)engine.setLexicon(loaded);
            android.util.Log.i("QingyuInput",(complete?"Auxiliary dictionaries":"Chinese dictionaries")+" ready in "+(android.os.SystemClock.uptimeMillis()-started)+" ms");
            if(first)translations.setEnglishLookup(loaded::lookupEnglish);
            main.post(()->{if(destroyed)return;if(!preview.isEmpty()&&(first||english))submit(this::searchWorker,"",currentMode());else{requestGlosses();requestPrediction();}});
        });
        if(!queued)loaded.close();
    }
    private void openEngine(boolean privacy){if(engineOpen){engine.close();engineOpen=false;}engine.open(dictFile.getPath(),new File(getFilesDir(),"user-pinyin.dat").getPath());engine.setLearningEnabled(!privacy);engineOpen=true;if(inputDictionary!=null)engine.setLexicon(inputDictionary);resetWorker();}
    @Override public View onCreateInputView(){
        detailRequest++;
        if(candidates!=null)candidates.animate().cancel();if(panels!=null)panels.toolbar.animate().cancel();
        dismissPinyinBubble();pinyinBubble=null;pinyinText=null;
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setClipChildren(true);root.setClipToPadding(true);root.setBackgroundColor(new Palette(prefs.dark(this)).background);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->renderPinyinBubble());
        root.setOnApplyWindowInsetsListener((v,insets)->{int bottom=insets.getSystemWindowInsetBottom(),left=0,right=0;if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.navigationBars());bottom=bars.bottom;left=bars.left;right=bars.right;}v.setPadding(left,0,right,bottom);return insets;});
        candidates=new CandidateSurface(this,prefs,this);expandedCandidates=new CandidateSurface(this,prefs,this);expandedCandidates.setEmbeddedGrid(true);keyboard=new KeyboardSurface(this,prefs,this);panels=new ImePanels(this,prefs,keyboard,this::panelAction);
        topRegion=new FrameLayout(this){@Override protected void onMeasure(int w,int h){super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(topHeight(),View.MeasureSpec.EXACTLY));}};
        topRegion.setClipChildren(true);topRegion.addView(candidates,new FrameLayout.LayoutParams(-1,topHeight()-dp(48)));FrameLayout.LayoutParams nav=new FrameLayout.LayoutParams(-1,dp(48),android.view.Gravity.BOTTOM);topRegion.addView(panels.toolbar,nav);
        navigationVisible=true;panels.onPanelChanged(()->{editPanelRevision++;renderTopRegion();if(!panels.active().equals("edit"))cancelEditorTranslation();});root.addView(topRegion,new LinearLayout.LayoutParams(-1,-2));root.addView(panels.body,new LinearLayout.LayoutParams(-1,-2));configureKeyboard();render();return root;
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
        cancelEditorTranslation();if(!restarting||!identity.equals(editorIdentity)||sensitive)editHistory.clear();else editHistory.observe(EditorHistory.read(ic,false));
        if(restarting&&identity.equals(editorIdentity)&&!preview.isEmpty()&&before!=null&&before.toString().equals(preview)){restoreComposingSpan();applyLearningPreference(info);configureKeyboard();render();return;}
        if(!restarting||!identity.equals(editorIdentity))resetPredictionChain();editorIdentity=identity;long token=session.incrementAndGet();revision++;visibleRevision=revision;clearPreview();temporaryNumeric=false;visible=EngineSnapshot.empty();selecting=false;composingActive=false;
        int type=info.inputType,cls=type&InputType.TYPE_MASK_CLASS,variation=type&InputType.TYPE_MASK_VARIATION;
        numeric=cls==InputType.TYPE_CLASS_NUMBER||cls==InputType.TYPE_CLASS_PHONE||cls==InputType.TYPE_CLASS_DATETIME;
        sensitive=cls==InputType.TYPE_CLASS_TEXT&&(variation==InputType.TYPE_TEXT_VARIATION_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)||cls==InputType.TYPE_CLASS_NUMBER&&variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        boolean uri=cls==InputType.TYPE_CLASS_TEXT&&(variation==InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS||variation==InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS||variation==InputType.TYPE_TEXT_VARIATION_URI);
        english=sensitive||uri||prefs.store.getBoolean("english",false);privateInput=sensitive||(info.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0;
        decoder.post(()->{if(token!=session.get())return;try{if(!engineOpen)openEngine(privateInput||!prefs.learning());resetWorker();engine.setLearningEnabled(!privateInput&&prefs.learning());if(inputDictionary!=null)inputDictionary.setLearningEnabled(!privateInput&&prefs.learning());}catch(Exception|LinkageError ignored){}});
        if(keyboard!=null)keyboard.resetModes();collapse();configureKeyboard();render();requestPrediction();
    }
    @Override public void onStartInputView(EditorInfo info,boolean restarting){super.onStartInputView(info,restarting);inputViewActive=true;restoreComposingSpan();applyLearningPreference(info);configureKeyboard();long changed=prefs.store.getLong("models_revision",0);String language=prefs.glossLanguage();TranslationRepository.StateCallback ready=state->{if(inputViewActive&&language.equals(prefs.glossLanguage())&&state==TranslationRepository.State.READY)requestGlosses();};if(changed!=modelsRevision){modelsRevision=changed;glossRequest++;glossBatchKey="";activeGlosses.clear();activeModelWords.clear();applyGlosses();translations.refreshAfterModelChange(language,ready);}else translations.refreshModels(language,ready);observeModels();render();if(root!=null)root.post(this::renderPinyinBubble);}
    private void applyLearningPreference(EditorInfo info){boolean privacy=sensitive||!prefs.learning()||(info.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0;long token=session.get();decoder.post(()->{if(token!=session.get())return;if(engineOpen)engine.setLearningEnabled(!privacy);if(inputDictionary!=null)inputDictionary.setLearningEnabled(!privacy);});}
    private void restoreComposingSpan(){InputConnection ic=getCurrentInputConnection();if(ic==null||preview.isEmpty())return;CharSequence before=ic.getTextBeforeCursor(preview.length(),0);if(before==null||!before.toString().equals(preview))return;android.view.inputmethod.ExtractedText text=ic.getExtractedText(new android.view.inputmethod.ExtractedTextRequest(),0);if(text!=null&&text.selectionStart==text.selectionEnd){int end=text.startOffset+text.selectionEnd;if(end>=preview.length()){ic.setComposingRegion(end-preview.length(),end);composingActive=true;}}}
    private String currentMode(){return english?"english":prefs.nineKey()?"nine":"pinyin";}
    private void configureKeyboard(){
        if(keyboard==null)return;EditorInfo info=getCurrentInputEditorInfo();String action="↵";if(info!=null&&(info.imeOptions&EditorInfo.IME_FLAG_NO_ENTER_ACTION)==0){switch(info.imeOptions&EditorInfo.IME_MASK_ACTION){case EditorInfo.IME_ACTION_GO:action="前往";break;case EditorInfo.IME_ACTION_SEARCH:action="搜索";break;case EditorInfo.IME_ACTION_SEND:action="发送";break;case EditorInfo.IME_ACTION_NEXT:action="下一项";break;case EditorInfo.IME_ACTION_DONE:action="完成";break;}}
        editorEnterLabel=action;keyboard.configure(english,numeric,action,sensitive);keyboard.setHapticFeedbackEnabled(prefs.haptic());if(panels!=null)panels.refresh();Palette colors=new Palette(prefs.dark(this));if(root!=null)root.setBackgroundColor(colors.background);Window window=getWindow().getWindow();if(window!=null){window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));window.setNavigationBarColor(colors.background);window.getDecorView().setSystemUiVisibility(prefs.dark(this)?0:View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);}
    }
    private void render(){
        if(candidates==null)return;List<String> words=new ArrayList<>();for(Candidate c:visible.candidates)words.add(c.text);String status=sensitive?"安全输入":engineError;
        synchronizeGlossLanguage();
        boolean chineseComposition=!english&&!numeric&&!sensitive&&!preview.isEmpty();
        keyboard.enterLabel(chineseComposition?currentMode().equals("nine")?"确认":"拼音":editorEnterLabel);
        keyboard.nineState(chineseComposition&&currentMode().equals("nine"),nineReadings,!nineVisibleReading.isEmpty()||!nineVisiblePrefix.isEmpty());renderPinyinBubble();
        candidates.setContentDescription("候选词，上方为释义；左右滑动浏览，长按查看详情，上滑输入翻译"+(chineseComposition?"；原始拼音 · "+rawPreview:""));
        candidates.pending(visibleRevision!=revision);expandedCandidates.pending(visibleRevision!=revision);
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
        if(root==null||!inputViewActive||!root.isShown()||root.getWindowToken()==null||sensitive||numeric||english||preview.isEmpty()||rawPreview.isEmpty()){dismissPinyinBubble();return;}
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
    private boolean glossMatches(long token,String source,String target){return !destroyed&&inputViewActive&&session.get()==token&&prefs.translation()&&!sensitive&&source.equals(english?"en":"zh")&&target.equals(english?"zh":prefs.glossLanguage());}
    private void synchronizeGlossLanguage(){
        String pair=english?"en\nzh":"zh\n"+prefs.glossLanguage();
        if(pair.equals(glossPair))return;
        glossPair=pair;glossRequest++;detailRequest++;glossBatchKey="";activeGlosses.clear();activeModelWords.clear();applyGlosses();
        detailTranslated="";detailExplanation="";detailExample="";detailExampleTranslation="";detailExampleNote="";
    }
    private void requestGlosses(){
        if(candidates==null||destroyed||sensitive)return;if(visible.candidates.isEmpty()){glossBatchKey="";return;}
        synchronizeGlossLanguage();
        java.util.Set<String> currentWords=new LinkedHashSet<>();for(Candidate candidate:visible.candidates)currentWords.add(candidate.text);activeGlosses.keySet().retainAll(currentWords);activeModelWords.retainAll(currentWords);
        long token=session.get();String source=english?"en":"zh",target=english?"zh":prefs.glossLanguage();boolean withGloss=prefs.translation();
        LinkedHashSet<String> viewport=new LinkedHashSet<>(candidates.visibleWords());if(isCandidateExpanded())viewport.addAll(expandedCandidates.visibleWords());
        List<String> words=new ArrayList<>(viewport);if(words.size()>24)words=new ArrayList<>(words.subList(0,24));final List<String> batch=words;
        String batchKey=token+"\n"+source+"\n"+target+"\n"+withGloss+"\n"+translations.status(target.equals("zh")?source:target)+"\n"+String.join("\n",batch);
        if(batchKey.equals(glossBatchKey))return;glossBatchKey=batchKey;
        if(pendingTranslation!=null)translation.removeCallbacks(pendingTranslation);long request=++glossRequest;
        pendingTranslation=()->{
            if(token!=session.get())return;Map<String,String> found=new HashMap<>();List<String> missing=new ArrayList<>();
            Map<String,String> pos=Collections.emptyMap();try{if(source.equals("zh")&&inputDictionary!=null)pos=inputDictionary.partsOfSpeech(batch);}catch(Exception ignored){}Map<String,String> tags=pos;
            if(withGloss)for(String word:batch){String gloss="";try{gloss=translations.localGloss(word,source,target);}catch(Exception ignored){}if(!gloss.isEmpty())found.put(word,gloss);else missing.add(word);}
            // Glosses belong to words and a language pair, not to a raw-input revision.
            main.post(()->{if(destroyed||request!=glossRequest||token!=session.get())return;candidates.partsOfSpeech(tags);expandedCandidates.partsOfSpeech(tags);if(withGloss&&glossMatches(token,source,target)){for(String word:found.keySet())activeModelWords.remove(word);activeGlosses.putAll(found);applyGlosses();
                // ponytail: at most 24 visible words in batches of four; inference never enters the decoder path.
                missing.removeIf(activeGlosses::containsKey);requestModelGlosses(missing,0,token,request,source,target);
            }});
        };
        translation.postDelayed(pendingTranslation,150);
    }
    @Override public void visibleWordsChanged(){requestGlosses();}
    private void requestModelGlosses(List<String> words,int offset,long token,long request,String source,String target){
        if(request!=glossRequest||!glossMatches(token,source,target)||offset>=words.size()||translations.status(target.equals("zh")?source:target)!=TranslationRepository.State.READY)return;
        int end=Math.min(offset+4,words.size());int[] left={end-offset};
        for(int i=offset;i<end;i++){String word=words.get(i);translations.translate(word,source,target,(value,note)->{
            if(request!=glossRequest||!glossMatches(token,source,target))return;
            if(!value.isEmpty()){activeGlosses.put(word,value);if(note.startsWith("Google Translate"))activeModelWords.add(word);else activeModelWords.remove(word);applyGlosses();}
            if(--left[0]==0)requestModelGlosses(words,end,token,request,source,target);
        });}
    }
    private void applyGlosses(){Map<String,String> values=new HashMap<>(activeGlosses);candidates.glosses(values);expandedCandidates.glosses(values);candidates.modelWords(new LinkedHashSet<>(activeModelWords));expandedCandidates.modelWords(new LinkedHashSet<>(activeModelWords));}
    private EngineSnapshot snapshot(String raw,List<String> words,String commit){List<Candidate> items=new ArrayList<>();for(int i=0;i<words.size()&&i<128;i++)items.add(new Candidate(i,words.get(i)));return new EngineSnapshot(raw,workerRaw,items,commit);}
    private EngineSnapshot searchWorker(){
        if(workerMode.equals("english")){List<String> words=inputDictionary==null||inputDictionary.english()==null?Collections.emptyList():inputDictionary.english().suggest(workerRaw,workerContext);return snapshot(workerRaw,words,"");}
        if(workerMode.equals("nine")){nineReading=LocalInputDictionary.nineKeyTrimSelection(workerRaw,nineReading);if(skipNineSearch)return snapshot(ninePrefix+LocalInputDictionary.nineKeyPreedit(workerRaw,Collections.emptyList(),nineReading),Collections.emptyList(),"");nineCandidates=inputDictionary==null?Collections.emptyList():inputDictionary.suggestNineKey(workerRaw,nineReading,workerContext+ninePrefix);List<String> words=new ArrayList<>();for(NineKeyCandidate c:nineCandidates)words.add(c.text);return snapshot(ninePrefix+LocalInputDictionary.nineKeyPreedit(workerRaw,nineCandidates,nineReading),words,"");}
        if(engineOpen){engine.setContext(workerContext);return engine.search(workerRaw);}return new EngineSnapshot(workerRaw,workerRaw,Collections.emptyList(),"");
    }
    private void resetWorker(){if(engineOpen)engine.reset();workerRaw="";ninePrefix="";nineReading="";nineSegments.clear();nineCandidates=Collections.emptyList();}
    private void learnNineSegment(NineKeyCandidate candidate,String context){if(inputDictionary!=null&&!privateInput&&prefs.learning())inputDictionary.learn(candidate.typedSpelling,candidate.text,context,candidate.pinyin);}
    private void learnNineWhole(String text){
        if(inputDictionary==null||privateInput||!prefs.learning()||nineSegments.size()<2||text.length()>64||nineSegments.size()>64)return;
        StringBuilder typed=new StringBuilder(),canonical=new StringBuilder();
        for(String[] segment:nineSegments){if(segment.length<5||segment[3].isEmpty()||segment[4].isEmpty()||!segment[1].codePoints().allMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN))return;if(typed.length()>0){typed.append('\'');canonical.append('\'');}typed.append(segment[3]);canonical.append(segment[4]);}
        inputDictionary.learn(typed.toString(),text,workerContext,canonical.toString());
    }
    private EngineSnapshot finishWorker(){
        String commit="";
        if(workerMode.equals("english")){commit=workerRaw;if(inputDictionary!=null&&inputDictionary.english()!=null&&!workerRaw.isEmpty())inputDictionary.english().learn(workerRaw,workerContext);}
        else if(workerMode.equals("nine")){
            commit=ninePrefix;String remaining=workerRaw,selection=nineReading;for(int step=0;step<64&&!remaining.isEmpty();step++){List<NineKeyCandidate> found=inputDictionary==null?Collections.emptyList():inputDictionary.suggestNineKey(remaining,selection,workerContext+commit);if(found.isEmpty()){commit+=LocalInputDictionary.nineKeyPreedit(remaining,found,selection);break;}NineKeyCandidate first=found.get(0);if(first.consumedDigits<=0||first.consumedDigits>remaining.length()){commit+=LocalInputDictionary.nineKeyPreedit(remaining,found,selection);break;}learnNineSegment(first,workerContext+commit);nineSegments.addLast(new String[]{remaining.substring(0,first.consumedDigits),first.text,LocalInputDictionary.nineKeyTrimSelection(remaining.substring(0,first.consumedDigits),selection),first.typedSpelling,first.pinyin});commit+=first.text;selection=LocalInputDictionary.nineKeyRemainingSelection(remaining,selection,first.consumedDigits);remaining=remaining.substring(first.consumedDigits);}if(remaining.isEmpty())learnNineWhole(commit);
        }else if(!workerRaw.isEmpty()){
            if(engineOpen){EngineSnapshot state=engine.search(workerRaw);for(int i=0;i<64&&state.committedText.isEmpty()&&!state.candidates.isEmpty();i++)state=engine.select(state.candidates.get(0).id);commit=state.committedText.isEmpty()?state.composing:state.committedText;}else commit=workerRaw;
        }
        resetWorker();return new EngineSnapshot("","",Collections.emptyList(),commit);
    }
    private interface DecoderAction{EngineSnapshot run()throws Exception;}
    private void submit(DecoderAction action,String suffix,String mode){
        long token=session.get(),generation=++revision;String context=contextBeforeComposition();
        if(candidates!=null){candidates.pending(true);expandedCandidates.pending(true);}
        decoder.post(()->{
            if(token!=session.get())return;EngineSnapshot result;String committed=suffix;
            // Keep every ordered edit and commit; only an obsolete candidate calculation can be skipped.
            skipNineSearch=generation!=revision;
            try{result=action.run();committed=result.committedText+suffix;workerRaw=result.rawPinyin;}catch(Exception|LinkageError error){result=new EngineSnapshot(mode.equals("nine")?ninePrefix+LocalInputDictionary.nineKeyPreedit(workerRaw,Collections.emptyList(),nineReading):workerRaw,workerRaw,Collections.emptyList(),"");}finally{skipNineSearch=false;}
            EngineSnapshot output=result;String commit=committed;String original=output.composing.isEmpty()?"":mode.equals("pinyin")&&engineOpen?engine.rawInput():mode.equals("nine")?LocalInputDictionary.nineKeyPreedit(output.rawPinyin,nineCandidates,nineReading):output.rawPinyin;
            String selectedPrefix=ninePrefix,selectedReading=nineReading;List<String> readings=new ArrayList<>(mode.equals("nine")&&inputDictionary!=null?inputDictionary.nineKeyReadings(output.rawPinyin,selectedReading):Collections.emptyList());
            if(mode.equals("nine")&&!nineCandidates.isEmpty()){String[] spelling=nineCandidates.get(0).typedSpelling.split("'");int offset=selectedReading.isEmpty()?0:selectedReading.split("'").length;if(offset<spelling.length&&readings.remove(spelling[offset]))readings.add(0,spelling[offset]);}
            main.post(()->{
                if(destroyed||token!=session.get())return;InputConnection ic=getCurrentInputConnection();if(ic==null)return;if(!commit.isEmpty()){changeEditor(ic,()->ic.commitText(commit,1));composingActive=false;}
                if(!commit.isEmpty()&&!mode.equals("english")&&!privateInput&&prefs.learning()&&inputDictionary!=null)decoder.post(()->{if(token==session.get())inputDictionary.learnChinese(commit,context);});
                if(generation==revision){visible=output;visibleRevision=generation;candidateMode=mode;preview=output.composing;rawPreview=original;nineRawPreview=mode.equals("nine")?output.rawPinyin:"";nineVisiblePrefix=mode.equals("nine")?selectedPrefix:"";nineVisibleReading=mode.equals("nine")?selectedReading:"";nineReadings=readings;updateComposing(ic);render();if(preview.isEmpty())requestPrediction();}
            });
        });
    }
    private void editorAction(Runnable action){long token=session.get();decoder.post(()->main.post(()->{if(!destroyed&&token==session.get()&&getCurrentInputConnection()!=null)action.run();}));}
    private void changeEditor(InputConnection connection,Runnable action){cancelEditorTranslation();if(sensitive){editHistory.clear();action.run();}else editHistory.change(connection,action);refreshEditState();}
    private void refreshEditState(){if(panels!=null&&panels.active().equals("edit"))panels.editState(!sensitive&&editHistory.canUndo(),editTranslateBusy,editStatus);}
    private void cancelEditorTranslation(){if(!editTranslateBusy)return;editTranslationRequest++;translations.cancelEditorTranslation();editTranslateBusy=false;editStatus="翻译已取消，原文未替换";refreshEditState();}
    private void undoEditor(){
        if(sensitive)return;cancelEditorTranslation();finishBeforeEditing();
        editorAction(()->{InputConnection connection=getCurrentInputConnection();boolean changed=editHistory.undo(connection);editStatus=changed?"已撤回上一步":"无可撤回操作，或输入内容已由应用修改";selecting=false;resetPredictionChain();visible=EngineSnapshot.empty();refreshEditState();render();requestPrediction();});
    }
    private void translateEditorText(){
        if(sensitive||editTranslateBusy)return;finishBeforeEditing();
        long request=++editTranslationRequest,panelRevision=editPanelRevision,inputRevision=revision;
        editorAction(()->{
            if(request!=editTranslationRequest||panelRevision!=editPanelRevision||inputRevision!=revision||!inputViewActive||panels==null||!panels.active().equals("edit"))return;
            InputConnection connection=getCurrentInputConnection();EditorHistory.Snapshot anchor=EditorHistory.read(connection,true);
            if(anchor==null){editStatus="应用未提供完整文本，未执行翻译";refreshEditState();return;}
            int from=Math.min(anchor.start,anchor.end),to=Math.max(anchor.start,anchor.end);boolean selected=from!=to;
            if(!selected){from=0;to=anchor.text.length();}
            String source=anchor.text.substring(from,to),target=prefs.glossLanguage();
            if(source.isEmpty()||source.length()>4096){editStatus=source.isEmpty()?"没有可翻译的文字":"请选中 4096 字以内的内容翻译";refreshEditState();return;}
            long token=session.get();int start=from,end=to;
            editTranslateBusy=true;editStatus="正在翻译，原文保持不变";refreshEditState();
            translations.translateEditor(source,selected,target,(value,note)->{
                if(destroyed||request!=editTranslationRequest||token!=session.get())return;
                editTranslateBusy=false;
                if(!inputViewActive||panels==null||!panels.active().equals("edit")||!target.equals(prefs.glossLanguage())){editStatus="翻译已取消，原文未替换";refreshEditState();return;}
                if(value.isEmpty()){editStatus=note.isEmpty()?"翻译失败，原文保持不变":note;refreshEditState();return;}
                InputConnection current=getCurrentInputConnection();
                boolean applied=editHistory.replace(current,anchor,start,end,value);
                editStatus=applied?"已替换译文，可撤回":"输入内容或选区已变化，未替换译文";selecting=false;resetPredictionChain();visible=EngineSnapshot.empty();refreshEditState();render();requestPrediction();
            });
        });
    }
    private String contextBeforeComposition(){InputConnection ic=getCurrentInputConnection();CharSequence before=ic==null?null:ic.getTextBeforeCursor(512,0);String text=before==null?"":before.toString();if(!preview.isEmpty()&&text.endsWith(preview))text=text.substring(0,text.length()-preview.length());return text;}
    private void requestPrediction(){
        if(destroyed||sensitive||numeric||predictionSuppressed||predictionPicks>=3||!preview.isEmpty()||getCurrentInputConnection()==null)return;String context=contextBeforeComposition();long predictionToken=++predictionRequest;if(context.trim().isEmpty()){if(candidateMode.startsWith("predict")){visible=EngineSnapshot.empty();render();}return;}long token=session.get(),generation=revision;boolean en=english;
        decoder.post(()->{
            if(token!=session.get()||!workerRaw.isEmpty()||inputDictionary==null)return;List<String> words;
            if(en)words=inputDictionary.english()==null?Collections.emptyList():inputDictionary.english().suggest("",context);
            else words=engineOpen?engine.predict(context):inputDictionary.predictChinese(context);
            List<Candidate> items=new ArrayList<>();for(int i=0;i<words.size()&&i<64;i++)items.add(new Candidate(i,words.get(i)));EngineSnapshot prediction=new EngineSnapshot("","",items,"");
            main.post(()->{if(!destroyed&&!predictionSuppressed&&predictionPicks<3&&token==session.get()&&generation==revision&&predictionToken==predictionRequest&&preview.isEmpty()&&en==english&&context.equals(contextBeforeComposition())){visible=prediction;visibleRevision=generation;candidateMode=en?"predict_en":"predict_zh";render();}});
        });
    }
    private void resetPredictionChain(){predictionPicks=0;predictionSuppressed=false;predictionRequest++;}
    @Override public void clearCandidates(){predictionSuppressed=true;predictionRequest++;glossRequest++;detailRequest++;if(pendingTranslation!=null)translation.removeCallbacks(pendingTranslation);visible=EngineSnapshot.empty();glossBatchKey="";activeGlosses.clear();activeModelWords.clear();applyGlosses();collapse();render();}
    private void updateComposing(InputConnection ic){if(!preview.isEmpty()){if(sensitive)ic.setComposingText(preview,1);else editHistory.compose(ic,preview);composingActive=true;}else{if(composingActive){if(sensitive)ic.setComposingText("",1);else editHistory.compose(ic,"");}if(sensitive)ic.finishComposingText();else editHistory.finishComposition(ic);composingActive=false;}refreshEditState();}
    private void clearPreview(){preview="";rawPreview="";nineRawPreview="";nineVisiblePrefix="";nineVisibleReading="";nineReadings=Collections.emptyList();}
    private void showImmediate(){InputConnection ic=getCurrentInputConnection();if(ic!=null)updateComposing(ic);if(preview.isEmpty()||!candidateMode.equals(currentMode()))visible=EngineSnapshot.empty();render();}
    private void directCommit(String text){resetPredictionChain();String mode=currentMode();submit(this::finishWorker,text,mode);clearPreview();visible=EngineSnapshot.empty();render();collapse();}
    private void clearComposition(){resetPredictionChain();predictionSuppressed=true;clearPreview();showImmediate();collapse();submit(()->{resetWorker();return EngineSnapshot.empty();},"",currentMode());}
    private void undoNineSegment(){
        if(nineSegments.isEmpty())return;
        String[] segment=nineSegments.removeLast();String selection=nineReading;
        ninePrefix=ninePrefix.substring(0,ninePrefix.length()-segment[1].length());
        workerRaw=segment[0]+workerRaw;nineReading=segment[2];
        if(!selection.isEmpty()&&LocalInputDictionary.nineKeySelectionOffset(segment[0],nineReading)==segment[0].length())nineReading+=(nineReading.isEmpty()?"":"'")+selection;
        nineReading=LocalInputDictionary.nineKeyTrimSelection(workerRaw,nineReading);
    }
    private void selectNineLiteral(String literal){
        int offset=LocalInputDictionary.nineKeySelectionOffset(workerRaw,nineReading);
        if(offset<0||offset>=workerRaw.length())return;
        int consumed=offset+1;if(consumed<workerRaw.length()&&workerRaw.charAt(consumed)=='\'')consumed++;
        // Explicit letters stay composing; choosing a literal never chooses a Chinese word for the user.
        String text=nineReading.replace("'","")+literal;
        nineSegments.addLast(new String[]{workerRaw.substring(0,consumed),text,nineReading});
        ninePrefix+=text;workerRaw=workerRaw.substring(consumed);nineReading="";
    }
    @Override public void key(String value){
        if(getCurrentInputConnection()==null)return;
        cancelEditorTranslation();
        detailRequest++;
        if(value.startsWith("READING_")){if(!english&&!numeric&&!sensitive&&currentMode().equals("nine")&&(!nineRawPreview.isEmpty()||!nineVisiblePrefix.isEmpty())){String reading=value.substring(8);resetPredictionChain();showImmediate();submit(()->{
            if(reading.equals("BACK")){if(!nineReading.isEmpty())nineReading=LocalInputDictionary.nineKeyUndoSelection(nineReading);else undoNineSegment();}
            else if(inputDictionary!=null&&inputDictionary.nineKeyReadings(workerRaw,nineReading).contains(reading)){
                if(reading.matches("[A-Z2-9]"))selectNineLiteral(reading);else nineReading=LocalInputDictionary.nineKeySelect(workerRaw,nineReading,reading);
            }
            return searchWorker();},"","nine");}return;}
        switch(value){
            case "SHIFT":keyboard.shift();return;
            case "SPLIT":{String raw=currentMode().equals("nine")?nineRawPreview:rawPreview;if(!english&&!numeric&&!sensitive&&!raw.isEmpty()&&!raw.endsWith("'"))key("'");return;}
            case "CLEAR":clearComposition();return;
            case "NUMERIC":{String mode=currentMode();resetPredictionChain();submit(this::finishWorker,"",mode);clearPreview();visible=EngineSnapshot.empty();numeric=true;temporaryNumeric=true;keyboard.resetModes();collapse();configureKeyboard();render();return;}
            case "SYMBOLS":keyboard.toggleSymbols();return;
            case "?123":case "ABC":if(numeric){numeric=false;if(!temporaryNumeric)english=true;temporaryNumeric=false;keyboard.resetModes();configureKeyboard();render();}else keyboard.toggleSymbols();return;
            case "LANG":if(!sensitive)changeMode(!english,prefs.keyboardMode());return;
            case "SPACE":{resetPredictionChain();String mode=currentMode();clearPreview();visible=EngineSnapshot.empty();render();collapse();submit(()->{boolean empty=workerRaw.isEmpty()&&ninePrefix.isEmpty();EngineSnapshot finished=finishWorker();return new EngineSnapshot("","",Collections.emptyList(),finished.committedText+(mode.equals("english")||empty?" ":""));},"",mode);return;}
            case "ENTER":resetPredictionChain();enter();return;case "⌫":resetPredictionChain();backspace();return;
            default:
                resetPredictionChain();
                boolean letter=value.length()==1&&value.charAt(0)>='a'&&value.charAt(0)<='z';String mode=currentMode();
                boolean compose=!sensitive&&!numeric&&!keyboard.isSymbols()&&(english?letter:mode.equals("nine")?value.length()==1&&value.charAt(0)>='2'&&value.charAt(0)<='9'||value.equals("'")&&!nineRawPreview.isEmpty():letter&&!keyboard.uppercase()||value.equals("'")&&!preview.isEmpty());
                if(compose){
                    String text=english&&keyboard.uppercase()?value.toUpperCase(java.util.Locale.ROOT):value;String context=contextBeforeComposition();
                    if(mode.equals("nine")){String prior=nineRawPreview;if(prior.length()>=64){nineRawPreview=text;nineVisiblePrefix="";nineVisibleReading="";prior="";}else nineRawPreview+=text;rawPreview=!nineVisibleReading.isEmpty()&&LocalInputDictionary.nineKeySelectionOffset(prior,nineVisibleReading)==prior.length()&&!prior.endsWith("'")&&!text.equals("'")?rawPreview+"'"+LocalInputDictionary.nineKeyFallback(text):LocalInputDictionary.nineKeyPending(nineRawPreview,prior,rawPreview);preview=nineVisiblePrefix+rawPreview;}
                    else{rawPreview=preview.length()>=64?text:rawPreview+text;preview=preview.length()>=64?text:preview+text;}showImmediate();
                    submit(()->{String committed="";if(!workerMode.equals(mode)){resetWorker();workerMode=mode;}if(workerRaw.length()>=64)committed=finishWorker().committedText;workerContext=context;workerRaw+=text;EngineSnapshot state=searchWorker();return committed.isEmpty()?state:new EngineSnapshot(state.composing,state.rawPinyin,state.candidates,committed);},"",mode);if(english)keyboard.consumedLetter();
                }else{String text=letter&&keyboard.uppercase()?value.toUpperCase(java.util.Locale.ROOT):value;directCommit(text);if(letter)keyboard.consumedLetter();}
        }
    }
    private void changeMode(boolean en,String mode){if(sensitive)return;resetPredictionChain();clearPreview();visible=EngineSnapshot.empty();submit(this::finishWorker,"",en?"english":mode.equals("t9")?"nine":"pinyin");english=en;numeric=false;temporaryNumeric=false;prefs.store.edit().putBoolean("english",en).putString("keyboard_mode",mode).apply();keyboard.resetModes();collapse();configureKeyboard();render();}
    private void enter(){
        EditorInfo info=getCurrentInputEditorInfo();String mode=currentMode();int action=info==null?EditorInfo.IME_ACTION_NONE:info.imeOptions&EditorInfo.IME_MASK_ACTION;long token=session.get();clearPreview();visible=EngineSnapshot.empty();collapse();
        render();submit(()->{if(!workerRaw.isEmpty()||!ninePrefix.isEmpty()){if(workerMode.equals("pinyin")){String raw=engineOpen?engine.rawInput():workerRaw;resetWorker();return new EngineSnapshot("","",Collections.emptyList(),raw);}return finishWorker();}if(info!=null&&(info.imeOptions&EditorInfo.IME_FLAG_NO_ENTER_ACTION)==0&&action!=EditorInfo.IME_ACTION_NONE&&action!=EditorInfo.IME_ACTION_UNSPECIFIED){main.post(()->{if(token==session.get()&&getCurrentInputConnection()!=null)getCurrentInputConnection().performEditorAction(action);});return EngineSnapshot.empty();}return new EngineSnapshot("","",Collections.emptyList(),"\n");},"",mode);
    }
    private void backspace(){
        String mode=currentMode();
        if(mode.equals("nine")&&!nineRawPreview.isEmpty()){if(!nineVisibleReading.isEmpty()&&LocalInputDictionary.nineKeySelectionOffset(nineRawPreview,nineVisibleReading)==nineRawPreview.length())nineVisibleReading=LocalInputDictionary.nineKeyUndoSelection(nineVisibleReading);else{String prior=nineRawPreview;nineRawPreview=nineRawPreview.substring(0,nineRawPreview.length()-1);nineVisibleReading=LocalInputDictionary.nineKeyTrimSelection(nineRawPreview,nineVisibleReading);rawPreview=LocalInputDictionary.nineKeyPending(nineRawPreview,prior,rawPreview);preview=nineVisiblePrefix+rawPreview;}showImmediate();}
        else if(!preview.isEmpty()){preview=preview.substring(0,preview.offsetByCodePoints(preview.length(),-1));if(!rawPreview.isEmpty())rawPreview=rawPreview.substring(0,rawPreview.length()-1);showImmediate();}long token=session.get();
        submit(()->{if(!workerRaw.isEmpty()){if(workerMode.equals("pinyin")&&engineOpen)return engine.backspace();if(workerMode.equals("nine")&&!nineReading.isEmpty()&&LocalInputDictionary.nineKeySelectionOffset(workerRaw,nineReading)==workerRaw.length())nineReading=LocalInputDictionary.nineKeyUndoSelection(nineReading);else{workerRaw=workerRaw.substring(0,workerRaw.length()-1);nineReading=LocalInputDictionary.nineKeyTrimSelection(workerRaw,nineReading);}return searchWorker();}if(workerMode.equals("nine")&&!nineSegments.isEmpty()){undoNineSegment();return searchWorker();}main.post(()->{if(token!=session.get())return;InputConnection ic=getCurrentInputConnection();if(ic==null)return;changeEditor(ic,()->{CharSequence selected=ic.getSelectedText(0);if(selected!=null&&selected.length()>0)ic.commitText("",1);else ic.deleteSurroundingTextInCodePoints(1,0);});});return EngineSnapshot.empty();},"",mode);
    }
    @Override public void choose(int index){
        if(index<0||index>=visible.candidates.size())return;boolean pending=visibleRevision!=revision;String kind=candidateMode,word=visible.candidates.get(index).text;int id=visible.candidates.get(index).id;String context=contextBeforeComposition();
        if(pending&&(!kind.equals(currentMode())||preview.isEmpty()))return;
        if(kind.startsWith("predict"))predictionPicks++;else resetPredictionChain();visible=EngineSnapshot.empty();render();
        submit(()->{
            int selectedId=id;
            if(pending){
                // Resolve the visible word after queued edits; never reuse an old decoder id.
                boolean skipped=skipNineSearch;skipNineSearch=false;EngineSnapshot latest;
                try{latest=searchWorker();}finally{skipNineSearch=skipped;}
                boolean matched=false;for(Candidate candidate:latest.candidates)if(candidate.text.equals(word)){selectedId=candidate.id;matched=true;break;}
                if(!matched)return latest;
            }
            if(kind.startsWith("predict")){resetWorker();if(kind.equals("predict_en")&&inputDictionary!=null&&inputDictionary.english()!=null)inputDictionary.english().learn(word,context);return new EngineSnapshot("","",Collections.emptyList(),word+(kind.equals("predict_en")?" ":""));}
            if(kind.equals("english")){if(inputDictionary!=null&&inputDictionary.english()!=null)inputDictionary.english().learn(word,workerContext);resetWorker();return new EngineSnapshot("","",Collections.emptyList(),word+" ");}
            if(kind.equals("nine")){if(selectedId>=nineCandidates.size())return searchWorker();NineKeyCandidate c=nineCandidates.get(selectedId);int consumed=Math.min(workerRaw.length(),c.consumedDigits);if(consumed<=0)return searchWorker();learnNineSegment(c,workerContext+ninePrefix);nineSegments.addLast(new String[]{workerRaw.substring(0,consumed),c.text,LocalInputDictionary.nineKeyTrimSelection(workerRaw.substring(0,consumed),nineReading),c.typedSpelling,c.pinyin});ninePrefix+=c.text;nineReading=LocalInputDictionary.nineKeyRemainingSelection(workerRaw,nineReading,consumed);workerRaw=workerRaw.substring(consumed);if(workerRaw.isEmpty()){String commit=ninePrefix;learnNineWhole(commit);resetWorker();return new EngineSnapshot("","",Collections.emptyList(),commit);}return searchWorker();}
            return engineOpen?engine.select(selectedId):EngineSnapshot.empty();
        },"",kind.equals("predict_en")?"english":kind.equals("predict_zh")?currentMode():kind);collapse();
    }
    private boolean isCandidateExpanded(){return panels!=null&&panels.active().equals("candidates");}
    @Override public void expand(){if(candidates==null||visible.candidates.isEmpty()||preview.isEmpty())return;detailRequest++;if(isCandidateExpanded()){collapse();requestGlosses();return;}List<String> words=new ArrayList<>();for(Candidate c:visible.candidates)words.add(c.text);expandedCandidates.setInteractiveComposition(true);expandedCandidates.setPredicting(false);expandedCandidates.update(preview,words,prefs.translation()&&!sensitive,engineError);expandedCandidates.expanded(true);expandedCandidates.glosses(new HashMap<>(activeGlosses));panels.showCandidates(expandedCandidates);candidates.setExpandIndicator(true);requestGlosses();}
    private void collapse(){detailRequest++;if(candidates!=null)candidates.setExpandIndicator(false);if(panels!=null)panels.close();else if(keyboard!=null)keyboard.setVisibility(View.VISIBLE);}
    private void openSettings(boolean modelManager){startActivity(new Intent(this,SettingsActivity.class).putExtra("model_manager",modelManager).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
    private void checkUpdates(){startActivity(new Intent(this,SettingsActivity.class).putExtra("check_updates",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
    @Override public void settings(){openSettings(false);}
    @Override public void toggleTranslation(){glossRequest++;prefs.store.edit().putBoolean("translation",!prefs.translation()).apply();glossBatchKey="";activeGlosses.clear();activeModelWords.clear();if(candidates!=null)applyGlosses();render();}
    @Override public void punctuation(String text){key(english?text.replace('，',',').replace('。','.').replace('？','?').replace('！','!').replace('：',':'):text);}
    @Override public void cursor(int direction){
        moveCursor(direction<0?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT,Math.min(12,Math.abs(direction)));
    }
    private void moveCursor(int code,int count){
        finishBeforeEditing();boolean extend=selecting;
        editorAction(()->{InputConnection ic=getCurrentInputConnection();int meta=extend?KeyEvent.META_SHIFT_ON:0;if(extend)ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SHIFT_LEFT));try{for(int i=0;i<count;i++){ic.sendKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_DOWN,code,0,meta));ic.sendKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_UP,code,0,meta));}}finally{if(extend)ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT));}requestPrediction();});
    }
    @Override public void longKey(String value){
        if(value.startsWith("DIRECT_")){directCommit(value.substring(7));return;}
        switch(value){case "LANG":((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();break;case "SHIFT":keyboard.shift();break;case "APOSTROPHE":key("SPLIT");break;
            case "DELETE_WORD":if(!preview.isEmpty()){clearPreview();showImmediate();submit(()->{resetWorker();return EngineSnapshot.empty();},"",currentMode());}else editorAction(()->{InputConnection ic=getCurrentInputConnection();changeEditor(ic,()->{CharSequence before=ic.getTextBeforeCursor(128,0);if(before!=null){String s=before.toString();int n=s.length();while(n>0&&Character.isWhitespace(s.charAt(n-1)))n--;while(n>0&&!Character.isWhitespace(s.charAt(n-1)))n--;ic.deleteSurroundingText(s.length()-n,0);}});requestPrediction();});break;
            case ",":case "，":key("、");break;case ".":case "。":key("…");break;case "ENTER":key("\n");break;default:break;}
    }
    private String nineTranslationSource(int id){
        if(id<0||id>=nineCandidates.size())return "";NineKeyCandidate selected=nineCandidates.get(id);int consumed=selected.consumedDigits;if(consumed<=0||consumed>workerRaw.length())return "";
        StringBuilder text=new StringBuilder(ninePrefix).append(selected.text);String remaining=workerRaw.substring(consumed),selection=LocalInputDictionary.nineKeyRemainingSelection(workerRaw,nineReading,consumed);
        for(int step=0;step<64&&!remaining.isEmpty();step++){List<NineKeyCandidate> found=inputDictionary.suggestNineKey(remaining,selection,workerContext+text);if(found.isEmpty()){text.append(LocalInputDictionary.nineKeyPreedit(remaining,found,selection));break;}NineKeyCandidate first=found.get(0);if(first.consumedDigits<=0||first.consumedDigits>remaining.length()){text.append(LocalInputDictionary.nineKeyPreedit(remaining,found,selection));break;}text.append(first.text);selection=LocalInputDictionary.nineKeyRemainingSelection(remaining,selection,first.consumedDigits);remaining=remaining.substring(first.consumedDigits);}
        return text.toString();
    }
    @Override public void detail(int index){translateCandidate(index,false);}
    @Override public void translate(int index){translateCandidate(index,true);}
    private void translateCandidate(int index,boolean commitOnReady){
        if(sensitive||getCurrentInputConnection()==null||visibleRevision!=revision||index<0||index>=visible.candidates.size())return;String kind=candidateMode,word=visible.candidates.get(index).text;int id=visible.candidates.get(index).id;String language=kind.equals("english")||kind.equals("predict_en")?"en":"zh",target=language.equals("en")?"zh":prefs.glossLanguage();long token=session.get(),generation=revision,request=++detailRequest;
        detailTarget=target;
        detailAnchorRequired=kind.startsWith("predict");detailAnchorContext=contextBeforeComposition();CharSequence selected=getCurrentInputConnection().getSelectedText(0);detailSelectedLength=selected==null?0:selected.length();
        if(!commitOnReady){panels.detail(word,"正在查找完整释义…");candidates.setExpandIndicator(false);}
        decoder.post(()->{if(token!=session.get())return;String resolved=word;try{if(kind.equals("pinyin")&&engineOpen)resolved=engine.previewCandidate(id);else if(kind.equals("nine")&&inputDictionary!=null)resolved=nineTranslationSource(id);}catch(Exception|LinkageError ignored){resolved="";}String source=resolved;
            String pos="",example="";if(inputDictionary!=null&&language.equals("zh")){try{pos=inputDictionary.partsOfSpeech(Collections.singletonList(source)).getOrDefault(source,"");if(!commitOnReady)example=inputDictionary.example(source);}catch(RuntimeException ignored){}}
            String part=pos,exampleText=example;
            main.post(()->{if(!detailMatches(token,generation,request))return;detailSource=source;detailTranslated="";detailExplanation="";detailNote="";detailExplanationNote="";detailPos=part;detailExample=exampleText;detailExampleTranslation="";detailExampleNote="";detailSession=token;detailRevision=generation;
                translations.translate(source,language,target,(value,note)->{if(!detailMatches(token,generation,request))return;detailTranslated=value;detailNote=note;if(commitOnReady&&!value.isEmpty()){commitTranslation(value);return;}if(commitOnReady)panels.detail(source,note);showDetailResult();});
                if(!commitOnReady)translations.describe(source,language,target,(value,note)->{if(!detailMatches(token,generation,request))return;if(!value.isEmpty()&&note.startsWith("Google Translate")){if(detailTranslated.isEmpty()){detailTranslated=value;detailNote=note;}}else{detailExplanation=value;detailExplanationNote=note;}showDetailResult();});
                if(!commitOnReady&&!exampleText.isEmpty())translations.translate(exampleText,language,target,(value,note)->{if(!detailMatches(token,generation,request))return;detailExampleTranslation=value;detailExampleNote=note;showDetailResult();});
            });
        });
    }
    private boolean detailAnchorMatches(){InputConnection ic=getCurrentInputConnection();if(ic==null||!detailAnchorContext.equals(contextBeforeComposition()))return false;CharSequence selected=ic.getSelectedText(0);return detailSelectedLength==(selected==null?0:selected.length());}
    private boolean detailMatches(long token,long generation,long request){return !destroyed&&inputViewActive&&token==session.get()&&generation==revision&&request==detailRequest&&detailTarget.equals(english?"zh":prefs.glossLanguage())&&detailAnchorMatches();}
    private void showDetailResult(){panels.detailResult(detailSource,detailTranslated,detailNote,detailExplanation.isEmpty()?"":detailExplanation+(detailExplanationNote.isEmpty()?"":"\n"+detailExplanationNote),detailPos,detailTarget,detailExample,detailExampleTranslation,detailExampleNote);}
    private void commitTranslation(String value){if(value.isEmpty()||sensitive||detailSession!=session.get()||detailRevision!=revision||!detailAnchorMatches())return;InputConnection ic=getCurrentInputConnection();if(ic==null)return;long token=session.get();revision++;clearPreview();visible=EngineSnapshot.empty();composingActive=false;
        // Commit in the same main-thread callback as the anchor check; a decoder round-trip lets the cursor move in between.
        if(detailAnchorRequired)predictionPicks++;else resetPredictionChain();changeEditor(ic,()->ic.commitText(value,1));decoder.post(()->{if(token==session.get())resetWorker();});collapse();render();requestPrediction();}
    private String modelStatus(){return TranslationRepository.languageName(prefs.glossLanguage())+" · "+translations.modelStatusText(prefs.glossLanguage());}
    private long modelPollGeneration;
    private int modelPollTicks;
    private final Runnable modelStatusPoll=new Runnable(){public void run(){
        if(destroyed||!inputViewActive||panels==null)return;
        String language=prefs.glossLanguage();long poll=modelPollGeneration;
        if(modelPollTicks++%4==0&&translations.status(language)!=TranslationRepository.State.READY)translations.refreshModels(language,state->{if(poll==modelPollGeneration&&inputViewActive&&language.equals(prefs.glossLanguage())&&state==TranslationRepository.State.READY)requestGlosses();});
        if(!panels.active().equals("languages")){if(prefs.translation()&&translations.status(language)!=TranslationRepository.State.READY)main.postDelayed(this,1000);return;}
        translations.modelProgress(language,progress->{
            if(destroyed||poll!=modelPollGeneration||!inputViewActive||!panels.active().equals("languages")||!language.equals(prefs.glossLanguage()))return;
            panels.modelStatus(TranslationRepository.languageName(language)+" · "+progress.message);
            main.removeCallbacks(this);main.postDelayed(this,1000);
        });
    }};
    private void observeModels(){modelPollGeneration++;modelPollTicks=0;main.removeCallbacks(modelStatusPoll);main.post(modelStatusPoll);}
    private void showLanguages(){panels.languages(modelStatus());observeModels();}
    private void showClipboard(){if(panels==null||sensitive)return;panels.clipboard(clipboard.snapshot(),this::directCommit,clipboard::remove);}
    private void panelAction(String action){
        if(panels==null)return;
        if(!action.equals("copy_translation")&&!action.equals("commit_translation"))detailRequest++;
        if(isCandidateExpanded())candidates.setExpandIndicator(false);
        switch(action){
            case "keyboard":collapse();return;case "hide":keyboard.cancelTouch();requestHideSelf(0);return;
            case "more":if(panels.active().equals("more")){collapse();return;}panels.more();return;case "edit":if(sensitive)editHistory.clear();else editHistory.observe(EditorHistory.read(getCurrentInputConnection(),false));panels.edit(sensitive,selecting);refreshEditState();return;case "emoji":panels.emoji();return;case "mode":panels.modes();return;
            case "languages":showLanguages();translations.refreshModels(prefs.glossLanguage(),state->panels.modelStatus(modelStatus()));return;case "settings":settings();return;case "model_manager":openSettings(true);return;case "check_updates":checkUpdates();return;
            case "project_home":try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(AppUpdate.PROJECT_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(android.content.ActivityNotFoundException ignored){android.widget.Toast.makeText(this,"未找到可打开项目主页的浏览器",android.widget.Toast.LENGTH_SHORT).show();}return;
            case "height":panels.height();return;case "height_changed":configureKeyboard();return;case "style":panels.styles();return;
            case "haptic":prefs.store.edit().putBoolean("haptic",!prefs.haptic()).apply();configureKeyboard();panels.more();return;case "toggle_gloss":toggleTranslation();showLanguages();return;
            case "download_models":translations.ensureModels(prefs.glossLanguage(),state->{panels.modelStatus(modelStatus());if(state==TranslationRepository.State.READY)requestGlosses();});return;
            case "mode_full":changeMode(false,"full");return;case "mode_t9":changeMode(false,"t9");return;case "mode_english":changeMode(true,prefs.keyboardMode());return;
            case "left":cursor(-1);return;case "right":cursor(1);return;case "select":selecting=!selecting;panels.edit(sensitive,selecting);refreshEditState();return;
            case "up":moveCursor(KeyEvent.KEYCODE_DPAD_UP,1);return;case "down":moveCursor(KeyEvent.KEYCODE_DPAD_DOWN,1);return;
            case "DELETE":cancelEditorTranslation();key("⌫");return;case "undo":undoEditor();return;case "edit_translate":translateEditorText();return;
            case "select_all":editContext(android.R.id.selectAll);return;case "copy":if(!sensitive)editContext(android.R.id.copy);return;case "cut":if(!sensitive)editContext(android.R.id.cut);return;case "paste":editContext(android.R.id.paste);return;
            case "clipboard":showClipboard();return;case "clear_clipboard":clipboard.clear();return;
            case "home":case "end":moveCursor(action.equals("home")?KeyEvent.KEYCODE_MOVE_HOME:KeyEvent.KEYCODE_MOVE_END,1);return;
            case "commit_translation":commitTranslation(detailTranslated);return;case "copy_translation":if(!detailTranslated.isEmpty())clipboard.copy(detailTranslated);return;
            default:if(action.startsWith("language_")){detailRequest++;String lang=action.substring(9);if(!TranslationRepository.isGlossLanguage(lang))return;prefs.store.edit().putString("gloss_language",lang).putBoolean("translation",true).apply();glossBatchKey="";activeGlosses.clear();activeModelWords.clear();applyGlosses();render();showLanguages();TranslationRepository.StateCallback changed=state->{if(!lang.equals(prefs.glossLanguage()))return;panels.modelStatus(modelStatus());if(state==TranslationRepository.State.READY)requestGlosses();};if(lang.equals("en"))translations.refreshModels(lang,changed);else translations.ensureModels(lang,changed);}else if(action.startsWith("style_")){prefs.store.edit().putString("style",action.substring(6)).apply();configureKeyboard();panels.styles();}else if(action.startsWith("emoji_"))directCommit(action.substring(6));
        }
    }
    private void editContext(int id){if(sensitive&&(id==android.R.id.copy||id==android.R.id.cut||id==android.R.id.selectAll||id==android.R.id.paste))return;submit(this::finishWorker,"",currentMode());editorAction(()->{InputConnection ic=getCurrentInputConnection();Runnable edit=()->{boolean applied=ic.performContextMenuAction(id);if(!applied&&id==android.R.id.paste){String text=clipboard.currentText();if(!text.isEmpty())ic.commitText(text,1);}};if(id==android.R.id.cut||id==android.R.id.paste)changeEditor(ic,edit);else edit.run();if(id==android.R.id.copy||id==android.R.id.cut)clipboard.record(clipboard.currentText());requestPrediction();});}
    private void finishBeforeEditing(){if(!preview.isEmpty()){clearPreview();visible=EngineSnapshot.empty();submit(this::finishWorker,"",currentMode());render();}}
    @Override public void onUpdateSelection(int oldStart,int oldEnd,int newStart,int newEnd,int candidatesStart,int candidatesEnd){
        super.onUpdateSelection(oldStart,oldEnd,newStart,newEnd,candidatesStart,candidatesEnd);InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        if(!sensitive){editHistory.observe(EditorHistory.read(ic,false));refreshEditState();}
        if(!preview.isEmpty()){
            // Selection notifications may be coalesced or older than our immediate preedit.
            // Inspect the actual cursor text before cancelling an ordered input transaction.
            CharSequence before=ic.getTextBeforeCursor(preview.length(),0),selected=ic.getSelectedText(0);
            if(before==null)return;
            if(before!=null&&before.toString().equals(preview)&&(selected==null||selected.length()==0))return;
            session.incrementAndGet();revision++;detailRequest++;cancelEditorTranslation();clearPreview();visible=EngineSnapshot.empty();composingActive=false;if(sensitive)ic.finishComposingText();else editHistory.finishComposition(ic);decoder.post(this::resetWorker);collapse();render();requestPrediction();
        }else if(oldStart!=newStart||oldEnd!=newEnd){if(detailAnchorRequired&&!detailAnchorMatches()){detailRequest++;detailRevision=-1;}requestPrediction();}
    }
    @Override public boolean onKeyDown(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_BACK&&panels!=null&&panels.isOpen()){collapse();return true;}return super.onKeyDown(code,event);}
    @Override public void onWindowHidden(){if(panels!=null)panels.cancelTouch();cancelEditorTranslation();super.onWindowHidden();}
    @Override public void onFinishInputView(boolean finishing){inputViewActive=false;glossBatchKey="";glossRequest++;if(pendingTranslation!=null)translation.removeCallbacks(pendingTranslation);cancelEditorTranslation();dismissPinyinBubble();if(panels!=null)panels.cancelTouch();if(keyboard!=null)keyboard.cancelTouch();if(candidates!=null)candidates.cancelTouch();if(expandedCandidates!=null)expandedCandidates.cancelTouch();collapse();
        // Hiding the view may not finish input; persist queued choices before the next session.
        if(!finishing)decoder.post(()->{if(engineOpen&&!privateInput)engine.flush();if(inputDictionary!=null)try{inputDictionary.flush();}catch(Exception ignored){}});
        super.onFinishInputView(finishing);}
    @Override public void onFinishInput(){cancelEditorTranslation();editHistory.clear();dismissPinyinBubble();rawPreview="";if(destroyed){super.onFinishInput();return;}session.incrementAndGet();revision++;clearPreview();visible=EngineSnapshot.empty();composingActive=false;decoder.post(()->{resetWorker();if(engineOpen&&!privateInput)engine.flush();if(inputDictionary!=null)try{inputDictionary.flush();}catch(Exception ignored){}});if(pendingTranslation!=null)translation.removeCallbacks(pendingTranslation);super.onFinishInput();}
    @Override public void onConfigurationChanged(Configuration config){dismissPinyinBubble();super.onConfigurationChanged(config);restoreComposingSpan();configureKeyboard();if(panels!=null&&panels.isOpen())collapse();if(root!=null)root.requestLayout();}
    @Override public void onDestroy(){destroyed=true;editHistory.clear();if(panels!=null)panels.cancelTouch();dismissPinyinBubble();session.incrementAndGet();main.removeCallbacksAndMessages(null);if(clipboard!=null)clipboard.close();translations.close();decoder.post(()->{if(engineOpen){engine.close();engineOpen=false;}if(inputDictionary!=null){inputDictionary.close();inputDictionary=null;}decoderThread.quitSafely();});translation.post(()->translationThread.quitSafely());super.onDestroy();}
}
