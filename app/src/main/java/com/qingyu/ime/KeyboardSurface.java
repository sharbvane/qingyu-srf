package com.qingyu.ime;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.util.ArrayList;
import java.util.List;

/** Native single-canvas keyboard: no per-key layouts or allocations in draw/touch move. */
final class KeyboardSurface extends View {
    interface Listener {
        void key(String value);
        void cursor(int direction);
        void longKey(String value);
    }
    static final class Key {
        final String value, alternate;
        final RectF bounds = new RectF();
        Key(String value, String alternate) { this.value=value; this.alternate=alternate; }
    }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Key> keys = new ArrayList<>();
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final ImePreferences prefs;
    private final float density;
    private Palette colors;
    private Key pressed, released;
    private long releaseTime;
    private float downX, downY, cursorX;
    private boolean dragged, longFired, cursorMode;
    private boolean english, shifted, capsLock, symbols, secondSymbols, numeric, secure;
    private boolean nineKey, dark;
    private String style="classic";
    private long lastShift;
    private String enterLabel = "↵";
    private final AccessibilityManager accessibility;
    private final AccessibilityNodeProvider nodeProvider = new KeyNodeProvider();
    private int accessibilityFocus = NO_ID, hoveredNode = NO_ID;
    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (pressed != null && pressed.value.equals("⌫")) {
                longFired = true;
                listener.key("⌫");
                timer.postDelayed(this, 48);
            }
        }
    };
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (pressed == null || dragged || !hasLongAction(pressed)) return;
            longFired = true;
            feedback();
            activateLongKey(pressed);
            invalidate();
        }
    };
    KeyboardSurface(Context c, ImePreferences p, Listener listener) {
        super(c); this.prefs=p; this.listener=listener; density=c.getResources().getDisplayMetrics().density;
        accessibility=(AccessibilityManager)c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        dark=p.dark(c);colors = new Palette(dark); setFocusable(false);
        setContentDescription("轻语键盘，空格左右滑动移动光标，退格长按连续删除");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    void configure(boolean english, boolean numeric, String action, boolean secure) {
        boolean nextNineKey=prefs.keyboardMode().equals("t9");
        boolean switchLayout=this.english!=english||this.numeric!=numeric||this.nineKey!=nextNineKey;
        this.english=english; this.numeric=numeric; enterLabel=action;
        this.secure=secure;this.nineKey=nextNineKey;style=prefs.style();
        if (numeric) symbols=false;
        boolean nextDark=prefs.dark(getContext());if(nextDark!=dark){dark=nextDark;colors=new Palette(dark);}
        cancelTouch();layoutKeys();requestLayout();invalidate();
        if(switchLayout)animateSwitch();
    }
    void toggleSymbols() { cancelTouch();symbols=!symbols; secondSymbols=false; layoutKeys(); invalidate();animateSwitch(); }
    private void animateSwitch(){animate().cancel();setAlpha(.88f);animate().alpha(1f).setDuration(100).start();}
    private boolean isNineKey(){return nineKey&&!english&&!numeric&&!secure&&!symbols;}
    void shift() {
        if (symbols) { secondSymbols=!secondSymbols; layoutKeys(); invalidate(); return; }
        long now=android.os.SystemClock.uptimeMillis();
        if (shifted && now-lastShift<350) capsLock=true;
        else { shifted=!shifted; capsLock=false; }
        lastShift=now; invalidate(); accessibilityChanged();
    }
    void consumedLetter() { if (shifted && !capsLock) { shifted=false; invalidate(); accessibilityChanged(); } }
    boolean uppercase() { return shifted; }
    boolean isSymbols() { return symbols || numeric; }
    void resetModes() { symbols=false; shifted=false; capsLock=false; secondSymbols=false; cancelTouch(); }
    private float dp(float n) { return n*density; }
    private boolean landscape() { return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE; }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int height=(int)dp((landscape()?136:244)*prefs.height()+8);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { cancelTouch();layoutKeys(); }
    private void row(String[] labels, String[] alternates, float top, float height, float indent, float[] weights) {
        float left=dp(3)+indent, width=getWidth()-dp(6)-indent*2;
        float sum=0; for(int i=0;i<labels.length;i++) sum+=weights==null?1:weights[i];
        for(int i=0;i<labels.length;i++) {
            float kw=width*(weights==null?1:weights[i])/sum;
            Key k=new Key(labels[i], alternates==null?"":alternates[i]);
            k.bounds.set(left+dp(2),top+dp(3),left+kw-dp(2),top+height-dp(3));
            keys.add(k); left+=kw;
        }
    }
    private void layoutKeys() {
        released=null;
        clearVirtualFocus();
        keys.clear(); if(getWidth()==0) return;
        float pad=dp(4), h=(getHeight()-pad*2)/4f;
        if (numeric) {
            row(new String[]{"1","2","3","-"},null,pad,h,0,null);
            row(new String[]{"4","5","6", "/"},null,pad+h,h,0,null);
            row(new String[]{"7","8","9","⌫"},null,pad+h*2,h,0,null);
            row(new String[]{"ABC",".","0","ENTER"},null,pad+h*3,h,0,new float[]{1,1,1,1});
        } else if(symbols) {
            row((secondSymbols?new String[]{"[","]","{","}","<",">","%","^","~","`"}:new String[]{"1","2","3","4","5","6","7","8","9","0"}),null,pad,h,0,null);
            row((secondSymbols?new String[]{"/","\\","|","*","=","\"","€","£","$"}:new String[]{"@","#","'","_","&","-","+","(",")"}),null,pad+h,h,dp(13),null);
            String[] punctuation=english||secure||secondSymbols?new String[]{"SHIFT",",",".","?","!",":",";","⌫"}:
                new String[]{"SHIFT","，","。","？","！","：","；","⌫"};
            row(punctuation,null,pad+h*2,h,0,new float[]{1.3f,1,1,1,1,1,1,1.3f});
            row(new String[]{"ABC","LANG",english?",":"，","SPACE",english?".":"。","ENTER"},null,pad+h*3,h,0,new float[]{1.3f,1,0.9f,3.7f,0.9f,1.6f});
        } else if(isNineKey()) {
            float[] weights={1,1,1,.8f};
            row(new String[]{"，","2","3","⌫"},null,pad,h,0,weights);
            row(new String[]{"4","5","6","。"},null,pad+h,h,0,weights);
            row(new String[]{"7","8","9","ENTER"},null,pad+h*2,h,0,weights);
            row(new String[]{"?123","LANG","SPACE","0"},null,pad+h*3,h,0,new float[]{1.1f,.9f,2.9f,.9f});
        } else {
            row("q w e r t y u i o p".split(" "),"1 2 3 4 5 6 7 8 9 0".split(" "),pad,h,0,null);
            row("a s d f g h j k l".split(" "),null,pad+h,h,dp(16),null);
            row(new String[]{"SHIFT","z","x","c","v","b","n","m","⌫"},null,pad+h*2,h,0,new float[]{1.35f,1,1,1,1,1,1,1,1.35f});
            row(new String[]{"?123","LANG",english?",":"，","SPACE",english?".":"。","ENTER"},null,pad+h*3,h,0,new float[]{1.35f,1,0.9f,3.6f,0.9f,1.65f});
        }
        accessibilityChanged();
    }
    private String label(Key k) {
        switch(k.value) {
            case "SHIFT":return symbols?(secondSymbols?"123":"#+="):(capsLock?"⇪":"⇧");
            case "SPACE":return cursorMode?"‹  移动光标  ›":(english?"English":isNineKey()?"轻语 · 九键":"轻语 · 拼音");
            case "LANG":return english?"EN":"中";
            case "ENTER":return enterLabel;
            default:return isT9Digit(k)?t9Letters(k.value):shifted && k.value.length()==1?k.value.toUpperCase(java.util.Locale.ROOT):k.value;
        }
    }
    private boolean isT9Digit(Key key){return isNineKey()&&key.value.length()==1&&key.value.charAt(0)>='2'&&key.value.charAt(0)<='9';}
    private String t9Letters(String digit){switch(digit){case "2":return "abc";case "3":return "def";case "4":return "ghi";case "5":return "jkl";case "6":return "mno";case "7":return "pqrs";case "8":return "tuv";case "9":return "wxyz";default:return digit;}}
    private int blend(int base,int accent,float amount){return Color.rgb((int)(Color.red(base)+(Color.red(accent)-Color.red(base))*amount),(int)(Color.green(base)+(Color.green(accent)-Color.green(base))*amount),(int)(Color.blue(base)+(Color.blue(accent)-Color.blue(base))*amount));}
    @Override protected void onDraw(Canvas c) {
        c.drawColor(colors.background);
        float release= released==null?0:Math.max(0,1f-(android.os.SystemClock.uptimeMillis()-releaseTime)/120f);
        float radius=dp(style.equals("flat")?4:8);
        for(Key k:keys) {
            boolean enter=k.value.equals("ENTER");
            boolean function=k.value.length()>1 || k.value.equals("⌫");
            int base=enter?colors.accent:(function?colors.function:colors.key);
            paint.setColor(k==pressed?colors.pressed:k==released&&release>0?blend(base,colors.pressed,release):base);
            c.drawRoundRect(k.bounds,radius,radius,paint);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));
            paint.setColor(enter?colors.accentText:colors.text);
            paint.setTextSize(dp(k.value.equals("SPACE")?12:(k.value.length()>1?14:(isT9Digit(k)?(landscape()?19:23):(landscape()?17:22)))));
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(label(k),k.bounds.centerX(),k.bounds.centerY()-(paint.ascent()+paint.descent())/2,paint);
            String alternate=isT9Digit(k)?k.value:!english&&!numeric&&!secure&&(k.value.equals("，")||k.value.equals(","))?"'":k.alternate;
            if(!alternate.isEmpty()) {
                paint.setTextSize(dp(9)); paint.setColor(colors.secondary); paint.setTextAlign(Paint.Align.RIGHT);
                c.drawText(alternate,k.bounds.right-dp(5),k.bounds.top+dp(12),paint);
            }
        }
        if(release>0)postInvalidateOnAnimation();else released=null;
        if(pressed!=null && prefs.preview() && !secure && pressed.value.length()==1 && !pressed.value.equals("⌫") && !cursorMode) {
            float x=pressed.bounds.centerX(), y=Math.max(dp(2),pressed.bounds.top-dp(48));
            paint.setColor(colors.accent);
            c.drawRoundRect(x-dp(24),y,x+dp(24),y+dp(48),dp(12),dp(12),paint);
            paint.setColor(colors.accentText); paint.setTextSize(dp(26)); paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(label(pressed),x,y+dp(33),paint);
        }
        if(accessibilityFocus>0 && accessibilityFocus<=keys.size()) {
            paint.setColor(colors.accent);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));
            c.drawRoundRect(keys.get(accessibilityFocus-1).bounds,dp(8),dp(8),paint);paint.setStyle(Paint.Style.FILL);
        }
    }
    private Key hit(float x,float y) {
        // Entire cell responds, including visual gutters; keys remain physically separated.
        Key closest=null; float best=Float.MAX_VALUE;
        for(Key k:keys) {
            if(y>=k.bounds.top-dp(3) && y<=k.bounds.bottom+dp(3)) {
                float distance=Math.abs(k.bounds.centerX()-x);
                if(distance<best) { best=distance; closest=k; }
            }
        }
        return closest;
    }
    private void feedback() { if(prefs.haptic()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }
    private void releaseKey(Key key) {
        int id=keys.indexOf(key)+1;
        String name=secure && characterKey(key)?"已输入密码字符":accessibilityName(key);
        released=key;releaseTime=android.os.SystemClock.uptimeMillis();
        listener.key(key.value);
        if(id>0) sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_CLICKED,name);
    }
    private boolean characterKey(Key key) { return key.value.length()==1 && !key.value.equals("⌫"); }
    private boolean hasLongAction(Key key) {
        return isT9Digit(key) || !key.alternate.isEmpty() || key.value.equals("SPACE") || key.value.equals("LANG") || key.value.equals("SHIFT")
            || key.value.equals("ENTER") || key.value.equals(",") || key.value.equals("，") || key.value.equals(".") || key.value.equals("。");
    }
    private void activateLongKey(Key key) {
        if(isT9Digit(key))listener.longKey("DIRECT_"+key.value);
        else if(!key.alternate.isEmpty())listener.key(key.alternate);
        else if(key.value.equals("SPACE")){cursorMode=true;cursorX=downX;}
        else if(!english && !numeric && !secure && (key.value.equals(",")||key.value.equals("，")))listener.longKey("APOSTROPHE");
        else listener.longKey(key.value);
    }
    private String accessibilityName(Key key) {
        switch(key.value) {
            case "SHIFT":return symbols?(secondSymbols?"数字符号页":"更多符号"):(capsLock?"大写已锁定":"切换大小写");
            case "SPACE":return "空格";
            case "LANG":return secure?"安全输入，英文键盘":(english?"切换中文":"切换英文");
            case "ENTER":return enterLabel.equals("↵")?"换行":enterLabel;
            case "⌫":return "删除";
            case "?123":return "数字和符号";
            case "ABC":return "字母键盘";
            case "，":return "逗号";
            case "。":return "句号";
            default:return label(key);
        }
    }
    private String longHint(Key key) {
        if(isT9Digit(key))return "长按输入数字 "+key.value;
        if(!key.alternate.isEmpty())return "长按输入 "+key.alternate;
        if(key.value.equals("SPACE"))return "长按进入光标操作，更多操作可左右移动光标";
        if(key.value.equals("LANG"))return "长按选择系统输入法";
        if(!english&&!numeric&&(key.value.equals("，")||key.value.equals(",")))return "长按输入拼音隔音符";
        if(key.value.equals("⌫"))return "长按删除上一词";
        return hasLongAction(key)?"支持长按":"";
    }
    private void sendVirtualEvent(int id,int type,String name) {
        if(!accessibility.isEnabled()||getParent()==null)return;
        AccessibilityEvent event=AccessibilityEvent.obtain(type);
        event.setPackageName(getContext().getPackageName());event.setClassName("android.widget.Button");
        event.setSource(this,id);event.setEnabled(isEnabled());event.setPassword(secure);
        if(name!=null){event.setContentDescription(name);event.getText().add(name);}
        getParent().requestSendAccessibilityEvent(this,event);
    }
    private void accessibilityChanged() {
        if(!accessibility.isEnabled()||getParent()==null)return;
        AccessibilityEvent event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        onInitializeAccessibilityEvent(event);event.setSource(this);event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        getParent().requestSendAccessibilityEvent(this,event);
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
            if(event.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT && event.getX()>=0 && event.getX()<getWidth() && event.getY()>=0 && event.getY()<getHeight()) {
                Key key=hit(event.getX(),event.getY());if(key!=null)next=keys.indexOf(key)+1;
            }
            int previous=hoveredNode;
            if(next!=previous) {
                hoveredNode=next;
                if(next!=NO_ID)sendVirtualEvent(next,AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,accessibilityName(keys.get(next-1)));
                if(previous!=NO_ID)sendVirtualEvent(previous,AccessibilityEvent.TYPE_VIEW_HOVER_EXIT,null);
            }
            if(next!=NO_ID||previous!=NO_ID)return true;
        }
        return super.dispatchHoverEvent(event);
    }
    private final class KeyNodeProvider extends AccessibilityNodeProvider {
        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            if(id==HOST_VIEW_ID) {
                AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain(KeyboardSurface.this);
                onInitializeAccessibilityNodeInfo(node);node.setClassName("android.inputmethodservice.KeyboardView");
                for(int i=0;i<keys.size();i++)node.addChild(KeyboardSurface.this,i+1);
                return node;
            }
            if(id<1||id>keys.size())return null;
            Key key=keys.get(id-1);AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain();
            node.setSource(KeyboardSurface.this,id);node.setParent(KeyboardSurface.this);
            node.setPackageName(getContext().getPackageName());node.setClassName("android.widget.Button");
            node.setViewIdResourceName(getContext().getPackageName()+":id/key_"+key.value);
            node.setText(label(key));node.setContentDescription(accessibilityName(key));node.setHintText(longHint(key));
            node.setEnabled(isEnabled()&&(!secure||!key.value.equals("LANG")));node.setFocusable(true);node.setClickable(node.isEnabled());
            node.setPassword(secure&&characterKey(key));node.setVisibleToUser(isShown());
            Rect bounds=new Rect();key.bounds.roundOut(bounds);node.setBoundsInParent(bounds);
            int[] screen=new int[2];getLocationOnScreen(screen);bounds.offset(screen[0],screen[1]);node.setBoundsInScreen(bounds);
            node.setAccessibilityFocused(id==accessibilityFocus);
            node.addAction(id==accessibilityFocus?AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            if(node.isEnabled())node.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            boolean longClickable=hasLongAction(key)||key.value.equals("⌫");
            node.setLongClickable(longClickable);if(longClickable)node.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            if(key.value.equals("SPACE")) {
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,"光标向左"));
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,"光标向右"));
            }
            return node;
        }
        @Override public AccessibilityNodeInfo findFocus(int focus) {
            return focus==AccessibilityNodeInfo.FOCUS_ACCESSIBILITY&&accessibilityFocus!=NO_ID?createAccessibilityNodeInfo(accessibilityFocus):null;
        }
        @Override public boolean performAction(int id,int action,Bundle arguments) {
            if(id==HOST_VIEW_ID)return KeyboardSurface.this.performAccessibilityAction(action,arguments);
            if(id<1||id>keys.size()||!isShown()||!isEnabled())return false;
            Key key=keys.get(id-1);
            if(action==AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                if(accessibilityFocus==id)return false;
                if(accessibilityFocus!=NO_ID)sendVirtualEvent(accessibilityFocus,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,null);
                accessibilityFocus=id;invalidate();sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,accessibilityName(key));return true;
            }
            if(action==AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                if(accessibilityFocus!=id)return false;
                accessibilityFocus=NO_ID;invalidate();sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,null);return true;
            }
            if(action==AccessibilityNodeInfo.ACTION_CLICK) {
                if(secure&&key.value.equals("LANG"))return false;
                cancelTouch();feedback();releaseKey(key);return true;
            }
            if(action==AccessibilityNodeInfo.ACTION_LONG_CLICK&&(hasLongAction(key)||key.value.equals("⌫"))) {
                cancelTouch();feedback();int sourceId=id;
                if(key.value.equals("⌫"))listener.longKey("DELETE_WORD");else activateLongKey(key);
                sendVirtualEvent(sourceId,AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,secure&&characterKey(key)?"已输入密码字符":accessibilityName(key));invalidate();return true;
            }
            if(key.value.equals("SPACE")&&(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                listener.cursor(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1);return true;
            }
            return false;
        }
        @Override public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String query,int id) {
            List<AccessibilityNodeInfo> result=new ArrayList<>();
            if(query==null)return result;
            String needle=query.toLowerCase(java.util.Locale.ROOT);
            for(int i=0;i<keys.size();i++)if((id==HOST_VIEW_ID||id==i+1)&&accessibilityName(keys.get(i)).toLowerCase(java.util.Locale.ROOT).contains(needle))result.add(createAccessibilityNodeInfo(i+1));
            return result;
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action=event.getActionMasked(); float x=event.getX(),y=event.getY();
        if(action==MotionEvent.ACTION_DOWN) {
            cancelTouch(); pressed=hit(x,y); downX=x;downY=y;cursorX=x; dragged=false;longFired=false;cursorMode=false;
            if(pressed==null) return false;
            feedback();
            if(pressed.value.equals("⌫")) { releaseKey(pressed); longFired=true; timer.postDelayed(repeat,380); }
            else timer.postDelayed(longPress,ViewConfiguration.getLongPressTimeout());
            invalidate(); return true;
        }
        if(action==MotionEvent.ACTION_MOVE && pressed!=null) {
            float dx=x-downX,dy=y-downY;
            if(pressed.value.equals("SPACE") && (cursorMode || Math.abs(dx)>dp(18))) {
                cursorMode=true; dragged=true; timer.removeCallbacks(longPress);
                int steps=(int)((x-cursorX)/dp(12));
                if(steps!=0) { listener.cursor(steps);cursorX+=steps*dp(12); }
                invalidate(); return true;
            }
            if(Math.abs(dx)>dp(24) || Math.abs(dy)>dp(30)) timer.removeCallbacks(longPress);
            if(pressed.value.equals("⌫") && dx < -dp(44) && !dragged) {
                dragged=true; timer.removeCallbacks(repeat); listener.longKey("DELETE_WORD"); invalidate();return true;
            }
            if(!longFired && !dragged) { Key next=hit(x,y); if(next!=null && next!=pressed){pressed=next;invalidate();} }
            return true;
        }
        if(action==MotionEvent.ACTION_UP) {
            if(pressed!=null && !longFired && !dragged && !cursorMode) { releaseKey(pressed); performClick(); }
            cancelTouch(); return true;
        }
        if(action==MotionEvent.ACTION_CANCEL) { cancelTouch(); return true; }
        if(action==MotionEvent.ACTION_POINTER_DOWN) {
            // Commit the first finger before switching so rapid two-thumb typing doesn't drop it.
            if(pressed!=null && !longFired && !dragged) releaseKey(pressed);
            cancelTouch(); int index=event.getActionIndex();
            pressed=hit(event.getX(index),event.getY(index)); longFired=false; dragged=false;
            if(pressed!=null) { releaseKey(pressed);longFired=true;feedback();invalidate(); }
            return true;
        }
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    void cancelTouch(){timer.removeCallbacks(repeat);timer.removeCallbacks(longPress);pressed=null;cursorMode=false;invalidate();}
    @Override protected void onDetachedFromWindow(){animate().cancel();setAlpha(1f);cancelTouch();released=null;clearVirtualFocus();super.onDetachedFromWindow();}
}

