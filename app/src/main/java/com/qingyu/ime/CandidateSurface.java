package com.qingyu.ime;

import android.content.Context;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Candidate geometry depends on the word, never on a delayed annotation. */
final class CandidateSurface extends View {
    interface Listener {
        void choose(int index); void detail(int index); void translate(int index); void expand();
        void settings(); void toggleTranslation(); void punctuation(String text);
        default void visibleWordsChanged(){}
        default void clearCandidates(){}
    }
    private static final int EXPAND=3, GOOGLE_ATTRIBUTION=4, CLEAR=5, CANDIDATE=100, PREVIOUS_PAGE=1000, NEXT_PAGE=1001;
    private static final int ACTION_TRANSLATE=0x02000001;
    private final Listener listener;
    private final ImePreferences prefs;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics wordMetrics=new Paint.FontMetrics(),glossMetrics=new Paint.FontMetrics();
    private final RectF rect=new RectF();
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final float density;
    private Palette colors;
    private boolean dark;
    private boolean googleTranslation;
    private Drawable googleBadge;
    private List<String> words=Collections.emptyList();
    private Map<String,String> glosses=Collections.emptyMap();
    private Map<String,String> partsOfSpeech=Collections.emptyMap();
    private final List<Float> offsets=new ArrayList<>();
    private String composing="";
    private boolean translations=true, interactiveComposition=true;
    private boolean embeddedGrid, predicting, expandIndicator;
    private float arrowFraction;
    private ValueAnimator arrowAnimator;
    private float scroll, downX, downY, startScroll, totalWidth;
    private boolean swiped, swipeTranslation, longFired, moved;
    private int page, pressed=NO_ID;
    private long generation, touchGeneration;
    private final AccessibilityManager accessibility;
    private final AccessibilityNodeProvider nodeProvider=new CandidateNodeProvider();
    private int accessibilityFocus=NO_ID, hoveredNode=NO_ID;
    private final Runnable longPress=new Runnable(){
        @Override public void run(){
            if(pressed<CANDIDATE||pressed>=CANDIDATE+words.size()||touchGeneration!=generation||moved)return;
            longFired=true;feedback();int index=pressed-CANDIDATE;
            sendVirtualEvent(pressed,AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,virtualName(pressed));
            listener.detail(index);invalidate();
        }
    };
    CandidateSurface(Context c,ImePreferences prefs,Listener listener) {
        super(c);this.prefs=prefs;this.listener=listener;density=c.getResources().getDisplayMetrics().density;
        accessibility=(AccessibilityManager)c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        dark=prefs.dark(c);colors=new Palette(dark);googleBadge=c.getDrawable(dark?R.drawable.google_translate_badge_dark:R.drawable.google_translate_badge);setFocusable(false);
        setContentDescription("候选词，上方为释义；左右滑动浏览，长按查看详情，上滑输入翻译");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    private float dp(float n){return n*density;}
    private boolean landscape(){return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;}
    void update(String composing,List<String> words,boolean translate,String status) {
        boolean wordsChanged=!this.words.equals(words);
        boolean changed=wordsChanged||!this.composing.equals(composing);
        if(changed){cancelTouch();clearVirtualFocus();generation++;scroll=0;page=0;}
        this.composing=composing;if(wordsChanged)this.words=new ArrayList<>(words);translations=translate;
        // Retain matching annotations while the next asynchronous batch is loading.
        boolean nextDark=prefs.dark(getContext());if(nextDark!=dark){dark=nextDark;colors=new Palette(dark);googleBadge=getContext().getDrawable(dark?R.drawable.google_translate_badge_dark:R.drawable.google_translate_badge);}
        if(wordsChanged)calculate();invalidate();accessibilityChanged();
    }
    void glosses(Map<String,String> glosses){this.glosses=glosses;invalidate();accessibilityChanged();}
    void partsOfSpeech(Map<String,String> values){partsOfSpeech=values;invalidate();}
    /** Header state only: expanding never changes the candidate row's height. */
    void setExpandIndicator(boolean value){
        if(expandIndicator==value)return;expandIndicator=value;
        if(arrowAnimator!=null)arrowAnimator.cancel();
        arrowAnimator=ValueAnimator.ofFloat(arrowFraction,value?1:0);arrowAnimator.setDuration(110);
        arrowAnimator.addUpdateListener(animation->{arrowFraction=(float)animation.getAnimatedValue();invalidate();});arrowAnimator.start();accessibilityChanged();
    }
    /** A separate instance fills the already reserved keyboard body. */
    void setEmbeddedGrid(boolean value){
        if(embeddedGrid==value)return;cancelTouch();clearVirtualFocus();generation++;embeddedGrid=value;scroll=0;page=0;requestLayout();invalidate();accessibilityChanged();
    }
    void setPredicting(boolean value){
        if(predicting==value)return;cancelTouch();clearVirtualFocus();generation++;predicting=value;
        scroll=Math.min(scroll,maxScroll());invalidate();accessibilityChanged();
    }
    /** Describes the current gloss batch's source; toggling attribution never changes candidate geometry. */
    void setGoogleTranslation(boolean value){
        if(Looper.myLooper()!=Looper.getMainLooper()){post(()->setGoogleTranslation(value));return;}
        if(googleTranslation==value)return;googleTranslation=value;invalidate();accessibilityChanged();
    }
    void setInteractiveComposition(boolean value){
        if(interactiveComposition==value)return;
        cancelTouch();clearVirtualFocus();generation++;interactiveComposition=value;
        invalidate();accessibilityChanged();
    }
    void expanded(boolean value){
        setExpandIndicator(value);
    }
    boolean expanded(){return expandIndicator;}
    List<String> words(){return Collections.unmodifiableList(words);}
    List<String> visibleWords(){
        List<String> result=new ArrayList<>(pageSize());if(getVisibility()!=VISIBLE||getWidth()<=0)return result;
        if(embeddedGrid){for(int i=page*pageSize();i<Math.min(words.size(),page*pageSize()+pageSize());i++)result.add(words.get(i));}
        else for(int i=0;i<words.size();i++){
            float left=offsets.get(i)-scroll,right=(i+1<offsets.size()?offsets.get(i+1):totalWidth)-scroll;
            if(right>0&&left<listWidth())result.add(words.get(i));
        }
        return result;
    }
    private void calculate(){
        offsets.clear();totalWidth=0;textPaint.setTextSize(dp(19));
        for(String word:words){offsets.add(totalWidth);totalWidth+=Math.min(dp(166),Math.max(dp(76),textPaint.measureText(word)+dp(30)));}
    }
    @Override protected void onMeasure(int w,int h){
        int desired=(int)(compositionHeight()+dp(embeddedGrid?(landscape()?160:300):(landscape()?44:58))+dp(16));
        setMeasuredDimension(MeasureSpec.getSize(w),resolveSize(desired,h));
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){cancelTouch();clearVirtualFocus();generation++;scroll=Math.min(scroll,maxScroll());page=Math.min(page,Math.max(0,(words.size()-1)/pageSize()));listener.visibleWordsChanged();}
    private float compositionHeight(){return embeddedGrid?0:dp(landscape()?18:22);}
    private int gridRows(){return landscape()?2:4;}
    private int pageSize(){return gridRows()*3;}
    private float footerHeight(){return dp(landscape()?30:36);}
    private float rowHeight(){return Math.max(1,(candidateBottom()-compositionHeight()-footerHeight())/gridRows());}
    // Fixed slot: an asynchronous model result must not move the word row or keyboard.
    private float candidateBottom(){return Math.max(compositionHeight(),getHeight()-dp(16));}
    private boolean hasGoogleAttribution(){return googleTranslation&&translations&&!words.isEmpty();}
    private boolean hasExpand(){return !embeddedGrid&&!predicting&&interactiveComposition&&!words.isEmpty();}
    private boolean hasClear(){return !embeddedGrid&&predicting&&!words.isEmpty();}
    // Snap the shared edge once: separately rounding adjacent virtual nodes
    // outward can otherwise make a word overlap the X by one physical pixel.
    private float listWidth(){return Math.max(0,getWidth()-(hasExpand()||hasClear()?Math.round(dp(42)):0));}
    private float expandLeft(){return Math.min(Math.max(0,totalWidth-scroll),getWidth()-dp(42));}
    private float maxScroll(){return Math.max(0,totalWidth-listWidth());}
    private void text(Canvas c,String value,float x,float y,float size,int color,Paint.Align align){
        paint.setTextSize(dp(size));paint.setColor(color);paint.setTextAlign(align);c.drawText(value,x,y,paint);
    }
    private String ellipsize(String value,float size,float width){
        textPaint.setTextSize(dp(size));return TextUtils.ellipsize(value,textPaint,Math.max(0,width),TextUtils.TruncateAt.END).toString();
    }
    @Override protected void onDraw(Canvas c) {
        c.drawColor(colors.background);float compH=compositionHeight(),bottom=candidateBottom();
        if(compH>0)text(c,ellipsize(composing,12,getWidth()-dp(24)),dp(12),compH-dp(5),12,colors.secondary,Paint.Align.LEFT);
        if(words.isEmpty())return;
        if(embeddedGrid) {
            float height=rowHeight(),width=getWidth()/3f;
            for(int slot=0;slot<pageSize();slot++){
                int index=page*pageSize()+slot;if(index>=words.size())break;
                rect.set((slot%3)*width+dp(4),compH+(slot/3)*height,(slot%3+1)*width-dp(4),compH+(slot/3+1)*height);
                drawWord(c,index,rect);
            }
            text(c,"‹",getWidth()*0.15f,bottom-dp(8),24,page>0?colors.text:colors.secondary,Paint.Align.CENTER);
            text(c,(page+1)+" / "+((words.size()+pageSize()-1)/pageSize()),getWidth()*0.5f,bottom-dp(11),12,colors.secondary,Paint.Align.CENTER);
            text(c,"›",getWidth()*0.85f,bottom-dp(8),24,page<(words.size()-1)/pageSize()?colors.text:colors.secondary,Paint.Align.CENTER);
        } else {
            c.save();c.clipRect(0,compH,listWidth(),bottom);
            for(int i=0;i<words.size();i++) {
                float left=offsets.get(i)-scroll,right=(i+1<offsets.size()?offsets.get(i+1):totalWidth)-scroll;
                if(right<=0||left>=listWidth())continue;
                rect.set(left+dp(2),compH,right-dp(2),bottom-dp(3));drawWord(c,i,rect);
            }c.restore();
            if(hasExpand()) {
                float expandX=expandLeft();
                if(pressed==EXPAND){paint.setColor(colors.pressed);c.drawRoundRect(expandX+dp(2),compH+dp(3),expandX+dp(39),bottom-dp(4),dp(9),dp(9),paint);}
                float centerX=expandX+dp(21),centerY=compH+(bottom-compH)/2;
                c.save();c.rotate(arrowFraction*180,centerX,centerY);paint.setColor(colors.text);paint.setStrokeWidth(dp(1.7f));paint.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(centerX-dp(5),centerY+dp(2.5f),centerX,centerY-dp(2.5f),paint);c.drawLine(centerX,centerY-dp(2.5f),centerX+dp(5),centerY+dp(2.5f),paint);c.restore();
            }else if(hasClear()){
                float left=listWidth(),centerX=left+dp(21),centerY=compH+(bottom-compH)/2;
                if(pressed==CLEAR){paint.setColor(colors.pressed);c.drawRoundRect(left+dp(2),compH+dp(3),getWidth()-dp(3),bottom-dp(4),dp(9),dp(9),paint);}
                paint.setColor(colors.secondary);paint.setStrokeWidth(dp(1.7f));paint.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(centerX-dp(4),centerY-dp(4),centerX+dp(4),centerY+dp(4),paint);c.drawLine(centerX+dp(4),centerY-dp(4),centerX-dp(4),centerY+dp(4),paint);
            }
        }
        if(accessibilityFocus!=NO_ID){
            RectF bounds=virtualBounds(accessibilityFocus);
            if(bounds!=null){paint.setColor(colors.accent);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));c.drawRoundRect(bounds,dp(9),dp(9),paint);paint.setStyle(Paint.Style.FILL);}
        }
        if(hasGoogleAttribution()&&googleBadge!=null){RectF badge=virtualBounds(GOOGLE_ATTRIBUTION);googleBadge.setBounds(Math.round(badge.left),Math.round(badge.top),Math.round(badge.right),Math.round(badge.bottom));googleBadge.draw(c);}
    }
    private void drawWord(Canvas c,int index,RectF bounds) {
        String word=words.get(index);float center=bounds.centerX(),mid=bounds.centerY();
        if(pressed==CANDIDATE+index||index==0){paint.setColor(pressed==CANDIDATE+index?colors.pressed:colors.function);c.drawRoundRect(bounds,dp(9),dp(9),paint);}
        String gloss=translations?glosses.get(word):null;float width=bounds.width()-dp(12);
        float wordSize=embeddedGrid&&landscape()?17:19,glossSize=11,wordBaseline=mid+dp(18),glossBaseline=mid-dp(6);
        if(embeddedGrid){
            // Keep both text lines inside even the minimum-height grid cell.
            // The gloss line remains reserved while asynchronous lookup runs.
            textPaint.setTextSize(dp(wordSize));textPaint.getFontMetrics(wordMetrics);
            textPaint.setTextSize(dp(glossSize));textPaint.getFontMetrics(glossMetrics);
            float gap=Math.min(dp(2),bounds.height()*.05f),padding=Math.min(dp(2),bounds.height()*.05f);
            float lines=wordMetrics.descent-wordMetrics.ascent+glossMetrics.descent-glossMetrics.ascent;
            float scale=Math.min(1,Math.max(0,(bounds.height()-2*padding-gap)/lines));wordSize*=scale;glossSize*=scale;
            textPaint.setTextSize(dp(wordSize));textPaint.getFontMetrics(wordMetrics);
            textPaint.setTextSize(dp(glossSize));textPaint.getFontMetrics(glossMetrics);
            float glossHeight=glossMetrics.descent-glossMetrics.ascent,wordHeight=wordMetrics.descent-wordMetrics.ascent;
            float top=mid-(glossHeight+gap+wordHeight)/2;
            glossBaseline=top-glossMetrics.ascent;wordBaseline=top+glossHeight+gap-wordMetrics.ascent;
        }
        if(gloss!=null&&!gloss.isEmpty())text(c,ellipsize(gloss,glossSize,width),center,glossBaseline,glossSize,colors.secondary,Paint.Align.CENTER);
        text(c,ellipsize(word,wordSize,width),center,wordBaseline,wordSize,colors.partOfSpeech(partsOfSpeech.get(word)),Paint.Align.CENTER);
        if(pressed==CANDIDATE+index&&swipeTranslation)text(c,"↑",bounds.right-dp(10),bounds.top+dp(12),12,colors.accent,Paint.Align.RIGHT);
    }
    private int hitNode(float x,float y) {
        if(x<0||x>=getWidth()||y<compositionHeight()||y>=candidateBottom())return NO_ID;
        if(embeddedGrid) {
            if(y>=candidateBottom()-footerHeight())return x<getWidth()*0.3f?PREVIOUS_PAGE:x>getWidth()*0.7f?NEXT_PAGE:NO_ID;
            int index=page*pageSize()+(int)((y-compositionHeight())/rowHeight())*3+Math.min(2,(int)(x/(getWidth()/3f)));
            return index<words.size()?CANDIDATE+index:NO_ID;
        }
        if(hasClear()&&x>=listWidth())return CLEAR;
        if(hasExpand()&&x>=expandLeft()&&x<expandLeft()+dp(42))return EXPAND;
        if(x>=listWidth())return NO_ID;
        float target=x+scroll;
        for(int i=words.size()-1;i>=0;i--)if(target>=offsets.get(i)&&target<totalWidth)return CANDIDATE+i;
        return NO_ID;
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x=e.getX(),y=e.getY();
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                cancelTouch();pressed=hitNode(x,y);if(pressed==NO_ID)return false;
                downX=x;downY=y;startScroll=scroll;touchGeneration=generation;
                if(pressed>=CANDIDATE&&pressed<CANDIDATE+words.size())timer.postDelayed(longPress,ViewConfiguration.getLongPressTimeout());
                invalidate();return true;
            case MotionEvent.ACTION_MOVE:
                if(pressed==NO_ID||longFired||touchGeneration!=generation)return true;
                float dx=x-downX,dy=y-downY;
                if(Math.max(Math.abs(dx),Math.abs(dy))>dp(8)){moved=true;timer.removeCallbacks(longPress);}
                if(!swiped&&dy<-dp(24)&&Math.abs(dy)>Math.abs(dx)*1.3f&&pressed>=CANDIDATE&&pressed<CANDIDATE+words.size())swipeTranslation=true;
                if(!swipeTranslation&&!embeddedGrid&&Math.abs(dx)>dp(8)&&Math.abs(dx)>Math.abs(dy)){swiped=true;scroll=Math.max(0,Math.min(maxScroll(),startScroll-dx));}
                invalidate();return true;
            case MotionEvent.ACTION_UP:
                int selected=pressed;boolean valid=selected!=NO_ID&&touchGeneration==generation&&!longFired;
                if(valid&&swipeTranslation){feedback();listener.translate(selected-CANDIDATE);}
                else if(valid&&!moved&&hitNode(x,y)==selected){feedback();activateNode(selected);performClick();}
                if(swiped){clearVirtualFocus();accessibilityChanged();listener.visibleWordsChanged();}cancelTouch();return true;
            case MotionEvent.ACTION_CANCEL:case MotionEvent.ACTION_POINTER_DOWN:cancelTouch();return true;
            default:return true;
        }
    }
    private void feedback(){if(prefs.haptic())performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);}
    void cancelTouch(){timer.removeCallbacks(longPress);pressed=NO_ID;swiped=false;swipeTranslation=false;longFired=false;moved=false;invalidate();}
    private RectF virtualBounds(int id) {
        float width=getWidth(),height=candidateBottom(),top=compositionHeight();if(width<=0||height<=0)return null;
        if(id==GOOGLE_ATTRIBUTION&&hasGoogleAttribution()){float badgeWidth=Math.min(dp(176),width),badgeHeight=badgeWidth/11f;return new RectF((width-badgeWidth)/2,height,(width+badgeWidth)/2,height+badgeHeight);}
        if(id==EXPAND&&hasExpand())return new RectF(expandLeft(),top,expandLeft()+dp(42),height);
        if(id==CLEAR&&hasClear())return new RectF(listWidth(),top,width,height);
        if(embeddedGrid&&(id==PREVIOUS_PAGE||id==NEXT_PAGE))return new RectF(id==PREVIOUS_PAGE?0:width*0.7f,height-footerHeight(),id==PREVIOUS_PAGE?width*0.3f:width,height);
        int index=id-CANDIDATE;if(index<0||index>=words.size())return null;
        if(embeddedGrid) {
            int slot=index-page*pageSize();if(slot<0||slot>=pageSize())return null;float rowHeight=rowHeight();
            return new RectF((slot%3)*width/3,top+(slot/3)*rowHeight,(slot%3+1)*width/3,top+(slot/3+1)*rowHeight);
        }
        if(index>=offsets.size())return null;
        float left=offsets.get(index)-scroll,right=(index+1<offsets.size()?offsets.get(index+1):totalWidth)-scroll;
        if(right<=0||left>=listWidth())return null;
        return new RectF(Math.max(0,left),top,Math.min(listWidth(),right),height);
    }
    private List<Integer> visibleNodes() {
        List<Integer> ids=new ArrayList<>();
        for(int i=0;i<words.size();i++)if(virtualBounds(CANDIDATE+i)!=null)ids.add(CANDIDATE+i);
        if(hasExpand())ids.add(EXPAND);if(hasClear())ids.add(CLEAR);if(embeddedGrid&&!words.isEmpty()){ids.add(PREVIOUS_PAGE);ids.add(NEXT_PAGE);}if(hasGoogleAttribution())ids.add(GOOGLE_ATTRIBUTION);return ids;
    }
    private String virtualName(int id) {
        if(id==GOOGLE_ATTRIBUTION)return "powered by Google Translate";
        if(id==EXPAND)return expandIndicator?"收起候选":"展开候选";
        if(id==CLEAR)return "清空预测候选";
        if(id==PREVIOUS_PAGE)return "上一页候选";if(id==NEXT_PAGE)return "下一页候选";
        int index=id-CANDIDATE;
        if(index>=0&&index<words.size()){String word=words.get(index),gloss=translations?glosses.get(word):null;return gloss==null||gloss.isEmpty()?word:word+"，释义 "+gloss;}return "";
    }
    private boolean virtualEnabled(int id) {
        if(id==PREVIOUS_PAGE)return page>0;if(id==NEXT_PAGE)return page<(words.size()-1)/pageSize();return isEnabled();
    }
    private boolean activateNode(int id) {
        if(virtualBounds(id)==null||!virtualEnabled(id))return false;
        sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_CLICKED,virtualName(id));
        if(id==EXPAND)listener.expand();else if(id==CLEAR)listener.clearCandidates();else if(id==PREVIOUS_PAGE)return changePage(-1);else if(id==NEXT_PAGE)return changePage(1);
        else if(id>=CANDIDATE&&id<CANDIDATE+words.size())listener.choose(id-CANDIDATE);else return false;return true;
    }
    private boolean changePage(int direction) {
        int next=Math.max(0,Math.min((words.size()-1)/pageSize(),page+direction));if(next==page)return false;
        cancelTouch();clearVirtualFocus();page=next;generation++;invalidate();accessibilityChanged();sendVirtualEvent(NO_ID,AccessibilityEvent.TYPE_VIEW_SCROLLED,null);listener.visibleWordsChanged();return true;
    }
    private boolean scrollCandidates(int direction) {
        if(words.isEmpty())return false;if(embeddedGrid)return changePage(direction);
        float next=Math.max(0,Math.min(maxScroll(),scroll+direction*listWidth()*0.8f));if(next==scroll)return false;
        cancelTouch();clearVirtualFocus();scroll=next;generation++;invalidate();accessibilityChanged();sendVirtualEvent(NO_ID,AccessibilityEvent.TYPE_VIEW_SCROLLED,null);listener.visibleWordsChanged();return true;
    }
    private void sendVirtualEvent(int id,int type,String name) {
        if(!accessibility.isEnabled()||getParent()==null)return;
        AccessibilityEvent event=AccessibilityEvent.obtain(type);event.setPackageName(getContext().getPackageName());
        event.setClassName(id==NO_ID?"android.view.View":"android.widget.Button");event.setSource(this,id);event.setEnabled(isEnabled());
        if(name!=null){event.setContentDescription(name);event.getText().add(name);}
        if(type==AccessibilityEvent.TYPE_VIEW_SCROLLED){event.setItemCount(words.size());event.setFromIndex(embeddedGrid?page*pageSize():0);event.setToIndex(embeddedGrid?Math.min(words.size()-1,(page+1)*pageSize()-1):words.size()-1);}
        getParent().requestSendAccessibilityEvent(this,event);
    }
    private void accessibilityChanged() {
        if(!accessibility.isEnabled()||getParent()==null)return;
        AccessibilityEvent event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);onInitializeAccessibilityEvent(event);
        event.setSource(this);event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);getParent().requestSendAccessibilityEvent(this,event);
    }
    private void clearVirtualFocus() {
        if(accessibilityFocus!=NO_ID)sendVirtualEvent(accessibilityFocus,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,null);
        if(hoveredNode!=NO_ID)sendVirtualEvent(hoveredNode,AccessibilityEvent.TYPE_VIEW_HOVER_EXIT,null);accessibilityFocus=NO_ID;hoveredNode=NO_ID;
    }
    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider(){return nodeProvider;}
    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        if(accessibility.isEnabled()&&accessibility.isTouchExplorationEnabled()) {
            int next=NO_ID;if(event.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT)for(int id:visibleNodes()){RectF bounds=virtualBounds(id);if(bounds!=null&&bounds.contains(event.getX(),event.getY())){next=id;break;}}
            int previous=hoveredNode;if(next!=previous){hoveredNode=next;if(next!=NO_ID)sendVirtualEvent(next,AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,virtualName(next));if(previous!=NO_ID)sendVirtualEvent(previous,AccessibilityEvent.TYPE_VIEW_HOVER_EXIT,null);}
            if(next!=NO_ID||previous!=NO_ID)return true;
        }return super.dispatchHoverEvent(event);
    }
    private final class CandidateNodeProvider extends AccessibilityNodeProvider {
        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            if(id==HOST_VIEW_ID) {
                AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain(CandidateSurface.this);onInitializeAccessibilityNodeInfo(node);
                for(int child:visibleNodes())if(virtualBounds(child)!=null)node.addChild(CandidateSurface.this,child);node.setScrollable(!words.isEmpty());
                if(!words.isEmpty()){node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);}return node;
            }
            RectF box=virtualBounds(id);if(box==null)return null;
            AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain();node.setSource(CandidateSurface.this,id);node.setParent(CandidateSurface.this);
            boolean attribution=id==GOOGLE_ATTRIBUTION;node.setPackageName(getContext().getPackageName());node.setClassName(attribution?"android.widget.TextView":"android.widget.Button");
            String resourceId=attribution?"candidate_attribution":id==EXPAND?"candidate_expand":id==CLEAR?"candidate_clear":id==PREVIOUS_PAGE?"candidate_previous_page":id==NEXT_PAGE?"candidate_next_page":"candidate_"+(id-CANDIDATE);
            node.setViewIdResourceName(getContext().getPackageName()+":id/"+resourceId);node.setContentDescription(virtualName(id));
            boolean candidate=id>=CANDIDATE&&id<CANDIDATE+words.size();node.setText(candidate?words.get(id-CANDIDATE):virtualName(id));
            node.setEnabled(virtualEnabled(id));node.setFocusable(true);node.setClickable(!attribution&&virtualEnabled(id));node.setVisibleToUser(isShown());
            Rect bounds=new Rect();box.roundOut(bounds);node.setBoundsInParent(bounds);int[] screen=new int[2];getLocationOnScreen(screen);bounds.offset(screen[0],screen[1]);node.setBoundsInScreen(bounds);
            node.setAccessibilityFocused(accessibilityFocus==id);node.addAction(accessibilityFocus==id?AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            if(node.isClickable())node.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            if(candidate){node.setLongClickable(true);node.setHintText(hasGoogleAttribution()?"长按查看完整释义，上滑输入翻译；机器译文 Translate with Google":"长按查看完整释义，上滑输入翻译");node.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);node.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_TRANSLATE,hasGoogleAttribution()?"Translate with Google，输入此候选的翻译":"输入此候选的翻译"));}return node;
        }
        @Override public AccessibilityNodeInfo findFocus(int focus){return focus==AccessibilityNodeInfo.FOCUS_ACCESSIBILITY&&accessibilityFocus!=NO_ID?createAccessibilityNodeInfo(accessibilityFocus):null;}
        @Override public boolean performAction(int id,int action,Bundle arguments) {
            if(!isShown()||!isEnabled())return false;
            if(id==HOST_VIEW_ID){if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)return scrollCandidates(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1);return CandidateSurface.this.performAccessibilityAction(action,arguments);}
            if(virtualBounds(id)==null)return false;
            if(action==AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS){if(accessibilityFocus==id)return false;if(accessibilityFocus!=NO_ID)sendVirtualEvent(accessibilityFocus,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,null);accessibilityFocus=id;invalidate();sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,virtualName(id));return true;}
            if(action==AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS){if(accessibilityFocus!=id)return false;accessibilityFocus=NO_ID;invalidate();sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,null);return true;}
            if(id>=CANDIDATE&&id<CANDIDATE+words.size()){
                if(action==AccessibilityNodeInfo.ACTION_LONG_CLICK){cancelTouch();listener.detail(id-CANDIDATE);return true;}
                if(action==ACTION_TRANSLATE){cancelTouch();listener.translate(id-CANDIDATE);return true;}
            }return action==AccessibilityNodeInfo.ACTION_CLICK&&activateNode(id);
        }
        @Override public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String query,int id) {
            List<AccessibilityNodeInfo> result=new ArrayList<>();if(query==null)return result;String needle=query.toLowerCase(java.util.Locale.ROOT);
            for(int child:visibleNodes())if((id==HOST_VIEW_ID||id==child)&&virtualName(child).toLowerCase(java.util.Locale.ROOT).contains(needle))result.add(createAccessibilityNodeInfo(child));return result;
        }
    }
    @Override protected void onDetachedFromWindow(){cancelTouch();clearVirtualFocus();if(arrowAnimator!=null)arrowAnimator.cancel();super.onDetachedFromWindow();}
    @Override public boolean performClick(){super.performClick();return true;}
}
