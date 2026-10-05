package com.qingyu.ime;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Width depends only on Chinese. Gloss arrival repaints; it never changes geometry. */
final class CandidateSurface extends View {
    interface Listener { void choose(int index); void expand(); void settings(); void toggleTranslation(); void punctuation(String text); }
    private final Listener listener;
    private final ImePreferences prefs;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private final float density;
    private Palette colors;
    private List<String> words=Collections.emptyList();
    private Map<String,String> glosses=Collections.emptyMap();
    private final List<Float> offsets=new ArrayList<>();
    private String composing="", status="";
    private boolean translations=true, expanded=false;
    private float scroll, downX, startScroll, totalWidth;
    private boolean swiped;
    private int page;
    private long generation, touchGeneration;
    private static final int TRANSLATION=1, SETTINGS=2, EXPAND=3, STATUS=4, PUNCTUATION=10, CANDIDATE=100, PREVIOUS_PAGE=1000, NEXT_PAGE=1001;
    private static final String[] PUNCTUATION_WORDS={"，","。","？","！","、","："};
    private final AccessibilityManager accessibility;
    private final AccessibilityNodeProvider nodeProvider=new CandidateNodeProvider();
    private int accessibilityFocus=NO_ID, hoveredNode=NO_ID;
    CandidateSurface(Context c,ImePreferences prefs,Listener listener) {
        super(c);this.prefs=prefs;this.listener=listener;density=c.getResources().getDisplayMetrics().density;
        accessibility=(AccessibilityManager)c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        colors=new Palette(prefs.dark(c));setFocusable(false);setContentDescription("中文候选词，上方为英文释义，左右滑动浏览");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    private float dp(float n){return n*density;}
    private boolean landscape(){return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;}
    void update(String composing,List<String> words,boolean translate,String status) {
        boolean changed=!this.words.equals(words);
        if(changed)clearVirtualFocus();
        this.composing=composing;this.words=new ArrayList<>(words);this.translations=translate;this.status=status;
        this.glosses=Collections.emptyMap(); colors=new Palette(prefs.dark(getContext()));
        if(changed){generation++;scroll=0;page=0;calculate();} invalidate();accessibilityChanged();
    }
    void glosses(Map<String,String> glosses){this.glosses=glosses;invalidate();accessibilityChanged();}
    void expanded(boolean value){if(expanded==value)return;clearVirtualFocus();generation++;expanded=value;page=0;scroll=0;requestLayout();invalidate();accessibilityChanged();}
    boolean expanded(){return expanded;}
    List<String> words(){return Collections.unmodifiableList(words);}
    private void calculate(){offsets.clear();totalWidth=0;paint.setTextSize(dp(19));for(String word:words){offsets.add(totalWidth);totalWidth+=Math.min(dp(166),Math.max(dp(76),paint.measureText(word)+dp(30)));}}
    @Override protected void onMeasure(int w,int h){setMeasuredDimension(MeasureSpec.getSize(w),(int)dp(expanded?(landscape()?184:330):(landscape()?64:86)));}
    private float compositionHeight(){return dp(landscape()?21:26);}
    private float controlWidth(){return dp(42);}
    private void text(Canvas c,String value,float x,float y,float size,int color,Paint.Align align){paint.setTextSize(dp(size));paint.setColor(color);paint.setTextAlign(align);paint.setTypeface(android.graphics.Typeface.DEFAULT);c.drawText(value,x,y,paint);}
    @Override protected void onDraw(Canvas c) {
        c.drawColor(colors.background);float compH=compositionHeight();
        paint.setTextSize(dp(12));String composition=TextUtils.ellipsize(composing.isEmpty()?"轻语  /  中英随行":composing,new android.text.TextPaint(paint),Math.max(0,getWidth()-dp(132)),TextUtils.TruncateAt.END).toString();
        text(c,composition,dp(12),compH-dp(7),12,colors.secondary,Paint.Align.LEFT);
        text(c,translations?"EN":"—",getWidth()-dp(92),compH-dp(7),11,colors.secondary,Paint.Align.CENTER);
        text(c,"⚙",getWidth()-dp(52),compH-dp(7),17,colors.secondary,Paint.Align.CENTER);
        text(c,expanded?"⌄":"⌃",getWidth()-dp(18),compH-dp(7),20,colors.text,Paint.Align.CENTER);
        if(words.isEmpty()) {
            if(!status.isEmpty())text(c,status,dp(12),compH+dp(30),13,colors.secondary,Paint.Align.LEFT);
            else {
                String[] punct={"，","。","？","！","、","："};float width=getWidth()/6f;
                for(int i=0;i<punct.length;i++)text(c,punct[i],width*(i+0.5f),compH+dp(35),22,colors.text,Paint.Align.CENTER);
            }
            return;
        }
        if(expanded) {
            float footer=dp(35), height=(getHeight()-compH-footer)/4;
            float width=getWidth()/3f;
            for(int slot=0;slot<12;slot++){
                int index=page*12+slot;if(index>=words.size())break;
                rect.set((slot%3)*width+dp(4),compH+(slot/3)*height,(slot%3+1)*width-dp(4),compH+(slot/3+1)*height);
                drawWord(c,index,rect,true);
            }
            int count=(words.size()+11)/12;
            text(c,"‹",getWidth()*0.2f,getHeight()-dp(10),24,colors.text,Paint.Align.CENTER);
            text(c,(page+1)+" / "+count,getWidth()*0.5f,getHeight()-dp(13),12,colors.secondary,Paint.Align.CENTER);
            text(c,"›",getWidth()*0.8f,getHeight()-dp(10),24,colors.text,Paint.Align.CENTER);
        } else {
            c.save();c.clipRect(0,compH,getWidth(),getHeight());
            for(int i=0;i<words.size();i++) {
                float left=offsets.get(i)-scroll;
                float right=(i+1<offsets.size()?offsets.get(i+1):totalWidth)-scroll;
                if(right<0 || left>getWidth())continue;
                rect.set(left+dp(2),compH,right-dp(2),getHeight()-dp(3));drawWord(c,i,rect,false);
            }c.restore();
        }
    }
    private void drawWord(Canvas c,int index,RectF bounds,boolean grid) {
        String word=words.get(index);float center=bounds.centerX();
        if(index==0){paint.setColor(colors.function);c.drawRoundRect(bounds,dp(9),dp(9),paint);}
        float width=bounds.width()-dp(12);String gloss=translations?glosses.get(word):null;
        // Both lines keep their baseline even with translation disabled, absent, or delayed.
        float mid=bounds.centerY();
        if(gloss!=null){paint.setTextSize(dp(11));String label=TextUtils.ellipsize(gloss, new android.text.TextPaint(paint),width,TextUtils.TruncateAt.END).toString();text(c,label,center,mid-dp(6),11,colors.secondary,Paint.Align.CENTER);}
        paint.setTextSize(dp(19));String label=TextUtils.ellipsize(word,new android.text.TextPaint(paint),width,TextUtils.TruncateAt.END).toString();
        text(c,label,center,mid+dp(18),19,colors.text,Paint.Align.CENTER);
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x=e.getX(),y=e.getY();
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:downX=x;startScroll=scroll;swiped=false;touchGeneration=generation;return true;
            case MotionEvent.ACTION_MOVE:
                if(!expanded && y>compositionHeight() && Math.abs(x-downX)>dp(6)){swiped=true;scroll=Math.max(0,Math.min(Math.max(0,totalWidth-getWidth()),startScroll+downX-x));invalidate();}return true;
            case MotionEvent.ACTION_UP:
                if(swiped){clearVirtualFocus();accessibilityChanged();return true;}
                if(x<0||x>=getWidth()||y<0||y>=getHeight()||touchGeneration!=generation)return true;
                if(y<compositionHeight()){
                    if(x>getWidth()-dp(34))listener.expand();
                    else if(x>getWidth()-dp(72))listener.settings();
                    else if(x>getWidth()-dp(114))listener.toggleTranslation();
                }else if(words.isEmpty()){
                    if(status.isEmpty()){String[] punct={"，","。","？","！","、","："};listener.punctuation(punct[Math.min(5,(int)(x/(getWidth()/6f)))]);}
                }else if(expanded){
                    if(y>getHeight()-dp(35)){changePage(x<getWidth()/2f?-1:1);}
                    else{float h=(getHeight()-compositionHeight()-dp(35))/4;int index=page*12+(int)((y-compositionHeight())/h)*3+Math.min(2,(int)(x/(getWidth()/3f)));if(index<words.size())listener.choose(index);}
                }else{
                    float target=x+scroll;for(int i=words.size()-1;i>=0;i--){if(target>=offsets.get(i)){listener.choose(i);break;}}
                }performClick();return true;
            case MotionEvent.ACTION_CANCEL:return true;
            default:return true;
        }
    }
    private boolean secure(){return status.equals("安全输入");}
    private RectF virtualBounds(int id) {
        float width=getWidth(),height=getHeight(),top=compositionHeight();
        if(width<=0||height<=0)return null;
        if(id==TRANSLATION)return new RectF(Math.max(0,width-dp(114)),0,width-dp(72),top);
        if(id==SETTINGS)return new RectF(Math.max(0,width-dp(72)),0,width-dp(34),top);
        if(id==EXPAND)return new RectF(Math.max(0,width-dp(34)),0,width,top);
        if(id==STATUS&&!status.isEmpty())return new RectF(0,top,width,height);
        if(words.isEmpty()) {
            int slot=id-PUNCTUATION;
            if(status.isEmpty()&&slot>=0&&slot<PUNCTUATION_WORDS.length)return new RectF(slot*width/6,top,(slot+1)*width/6,height);
            return null;
        }
        if(expanded&&(id==PREVIOUS_PAGE||id==NEXT_PAGE))return new RectF(id==PREVIOUS_PAGE?0:width/2,height-dp(35),id==PREVIOUS_PAGE?width/2:width,height);
        int index=id-CANDIDATE;if(index<0||index>=words.size())return null;
        if(expanded) {
            int slot=index-page*12;if(slot<0||slot>=12)return null;
            float rowHeight=(height-top-dp(35))/4;
            return new RectF((slot%3)*width/3,top+(slot/3)*rowHeight,(slot%3+1)*width/3,top+(slot/3+1)*rowHeight);
        }
        if(index>=offsets.size())return null;
        float left=offsets.get(index)-scroll,right=(index+1<offsets.size()?offsets.get(index+1):totalWidth)-scroll;
        if(right<=0||left>=width)return null;
        return new RectF(Math.max(0,left),top,Math.min(width,right),height);
    }
    private List<Integer> visibleNodes() {
        List<Integer> ids=new ArrayList<>();ids.add(TRANSLATION);ids.add(SETTINGS);ids.add(EXPAND);
        if(!status.isEmpty()&&words.isEmpty())ids.add(STATUS);
        if(words.isEmpty()&&status.isEmpty())for(int i=0;i<PUNCTUATION_WORDS.length;i++)ids.add(PUNCTUATION+i);
        else for(int i=0;i<words.size();i++)if(virtualBounds(CANDIDATE+i)!=null)ids.add(CANDIDATE+i);
        if(expanded&&!words.isEmpty()){ids.add(PREVIOUS_PAGE);ids.add(NEXT_PAGE);}
        return ids;
    }
    private String virtualName(int id) {
        if(id==TRANSLATION)return translations?"关闭英文释义":"显示英文释义";
        if(id==SETTINGS)return "输入法设置";
        if(id==EXPAND)return expanded?"收起候选":"展开候选";
        if(id==STATUS)return status;
        if(id==PREVIOUS_PAGE)return "上一页候选";
        if(id==NEXT_PAGE)return "下一页候选";
        if(id>=PUNCTUATION&&id<PUNCTUATION+6)return PUNCTUATION_WORDS[id-PUNCTUATION];
        int index=id-CANDIDATE;
        if(index>=0&&index<words.size()) {
            String word=words.get(index),gloss=translations?glosses.get(word):null;
            return gloss==null||gloss.isEmpty()?word:word+"，英文释义 "+gloss;
        }
        return "";
    }
    private boolean virtualEnabled(int id) {
        if(id==STATUS)return false;
        if(id==TRANSLATION)return !secure();
        if(id==EXPAND)return !words.isEmpty();
        if(id==PREVIOUS_PAGE)return page>0;
        if(id==NEXT_PAGE)return page<(words.size()-1)/12;
        return isEnabled();
    }
    private boolean activateNode(int id) {
        if(virtualBounds(id)==null||!virtualEnabled(id))return false;
        String name=virtualName(id);sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_CLICKED,name);
        if(id==TRANSLATION)listener.toggleTranslation();
        else if(id==SETTINGS)listener.settings();
        else if(id==EXPAND)listener.expand();
        else if(id==PREVIOUS_PAGE)return changePage(-1);
        else if(id==NEXT_PAGE)return changePage(1);
        else if(id>=PUNCTUATION&&id<PUNCTUATION+6)listener.punctuation(PUNCTUATION_WORDS[id-PUNCTUATION]);
        else if(id>=CANDIDATE&&id<CANDIDATE+words.size())listener.choose(id-CANDIDATE);
        else return false;
        return true;
    }
    private boolean changePage(int direction) {
        int next=Math.max(0,Math.min((words.size()-1)/12,page+direction));if(next==page)return false;
        clearVirtualFocus();page=next;generation++;invalidate();accessibilityChanged();
        sendVirtualEvent(NO_ID,AccessibilityEvent.TYPE_VIEW_SCROLLED,null);return true;
    }
    private boolean scrollCandidates(int direction) {
        if(words.isEmpty())return false;
        if(expanded)return changePage(direction);
        float next=Math.max(0,Math.min(Math.max(0,totalWidth-getWidth()),scroll+direction*getWidth()*0.8f));
        if(next==scroll)return false;
        clearVirtualFocus();scroll=next;generation++;invalidate();accessibilityChanged();sendVirtualEvent(NO_ID,AccessibilityEvent.TYPE_VIEW_SCROLLED,null);return true;
    }
    private void sendVirtualEvent(int id,int type,String name) {
        if(!accessibility.isEnabled()||getParent()==null)return;
        AccessibilityEvent event=AccessibilityEvent.obtain(type);event.setPackageName(getContext().getPackageName());
        event.setClassName(id==NO_ID?"android.view.View":"android.widget.Button");event.setSource(this,id);event.setEnabled(isEnabled());
        if(name!=null){event.setContentDescription(name);event.getText().add(name);}
        if(type==AccessibilityEvent.TYPE_VIEW_SCROLLED){event.setItemCount(words.size());event.setFromIndex(expanded?page*12:0);event.setToIndex(expanded?Math.min(words.size()-1,page*12+11):words.size()-1);}
        getParent().requestSendAccessibilityEvent(this,event);
    }
    private void accessibilityChanged() {
        if(!accessibility.isEnabled()||getParent()==null)return;
        AccessibilityEvent event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);onInitializeAccessibilityEvent(event);
        event.setSource(this);event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);getParent().requestSendAccessibilityEvent(this,event);
    }
    private void clearVirtualFocus() {
        if(accessibilityFocus!=NO_ID)sendVirtualEvent(accessibilityFocus,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,null);
        if(hoveredNode!=NO_ID)sendVirtualEvent(hoveredNode,AccessibilityEvent.TYPE_VIEW_HOVER_EXIT,null);
        accessibilityFocus=NO_ID;hoveredNode=NO_ID;
    }
    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider(){return nodeProvider;}
    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        if(accessibility.isEnabled()&&accessibility.isTouchExplorationEnabled()) {
            int next=NO_ID;
            if(event.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT)for(int id:visibleNodes()){RectF bounds=virtualBounds(id);if(bounds!=null&&bounds.contains(event.getX(),event.getY())){next=id;break;}}
            int previous=hoveredNode;
            if(next!=previous){hoveredNode=next;if(next!=NO_ID)sendVirtualEvent(next,AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,virtualName(next));if(previous!=NO_ID)sendVirtualEvent(previous,AccessibilityEvent.TYPE_VIEW_HOVER_EXIT,null);}
            if(next!=NO_ID||previous!=NO_ID)return true;
        }
        return super.dispatchHoverEvent(event);
    }
    private final class CandidateNodeProvider extends AccessibilityNodeProvider {
        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            if(id==HOST_VIEW_ID) {
                AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain(CandidateSurface.this);onInitializeAccessibilityNodeInfo(node);
                for(int child:visibleNodes())if(virtualBounds(child)!=null)node.addChild(CandidateSurface.this,child);
                node.setScrollable(!words.isEmpty());
                if(!words.isEmpty()){node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);}return node;
            }
            RectF box=virtualBounds(id);if(box==null)return null;
            AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain();node.setSource(CandidateSurface.this,id);node.setParent(CandidateSurface.this);
            node.setPackageName(getContext().getPackageName());node.setClassName(id==STATUS?"android.widget.TextView":"android.widget.Button");
            String resourceId=id==TRANSLATION?"annotation_toggle":id==SETTINGS?"ime_settings":id==EXPAND?"candidate_expand":id==STATUS?"input_status":id==PREVIOUS_PAGE?"candidate_previous_page":id==NEXT_PAGE?"candidate_next_page":id>=PUNCTUATION&&id<PUNCTUATION+6?"punctuation_"+(id-PUNCTUATION):"candidate_"+(id-CANDIDATE);
            node.setViewIdResourceName(getContext().getPackageName()+":id/"+resourceId);
            node.setContentDescription(virtualName(id));node.setText(id>=CANDIDATE&&id<CANDIDATE+words.size()?words.get(id-CANDIDATE):virtualName(id));
            node.setEnabled(virtualEnabled(id));node.setFocusable(true);node.setClickable(id!=STATUS&&virtualEnabled(id));node.setVisibleToUser(isShown());
            Rect bounds=new Rect();box.roundOut(bounds);node.setBoundsInParent(bounds);int[] screen=new int[2];getLocationOnScreen(screen);bounds.offset(screen[0],screen[1]);node.setBoundsInScreen(bounds);
            node.setAccessibilityFocused(accessibilityFocus==id);node.addAction(accessibilityFocus==id?AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            if(node.isClickable())node.addAction(AccessibilityNodeInfo.ACTION_CLICK);return node;
        }
        @Override public AccessibilityNodeInfo findFocus(int focus){return focus==AccessibilityNodeInfo.FOCUS_ACCESSIBILITY&&accessibilityFocus!=NO_ID?createAccessibilityNodeInfo(accessibilityFocus):null;}
        @Override public boolean performAction(int id,int action,Bundle arguments) {
            if(!isShown()||!isEnabled())return false;
            if(id==HOST_VIEW_ID){if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)return scrollCandidates(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1);return CandidateSurface.this.performAccessibilityAction(action,arguments);}
            if(virtualBounds(id)==null)return false;
            if(action==AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS){if(accessibilityFocus==id)return false;if(accessibilityFocus!=NO_ID)sendVirtualEvent(accessibilityFocus,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,null);accessibilityFocus=id;invalidate();sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,virtualName(id));return true;}
            if(action==AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS){if(accessibilityFocus!=id)return false;accessibilityFocus=NO_ID;invalidate();sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,null);return true;}
            return action==AccessibilityNodeInfo.ACTION_CLICK&&activateNode(id);
        }
        @Override public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String query,int id) {
            List<AccessibilityNodeInfo> result=new ArrayList<>();if(query==null)return result;String needle=query.toLowerCase(java.util.Locale.ROOT);
            for(int child:visibleNodes())if((id==HOST_VIEW_ID||id==child)&&virtualName(child).toLowerCase(java.util.Locale.ROOT).contains(needle))result.add(createAccessibilityNodeInfo(child));return result;
        }
    }
    @Override protected void onDetachedFromWindow(){clearVirtualFocus();super.onDetachedFromWindow();}
    @Override public boolean performClick(){super.performClick();return true;}
}
