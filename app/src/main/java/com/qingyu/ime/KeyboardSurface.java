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
import android.view.animation.DecelerateInterpolator;
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
    private boolean nineKey, dark, nineComposing;
    private final List<String> nineReadings = new ArrayList<>();
    private final RectF nineRail = new RectF(), choiceBounds = new RectF();
    private int nineRailStart = -1;
    private String[] holdChoices;
    private int holdChoice;
    private boolean holdNine;
    private int holdColumns;
    private final RectF holdBounds = new RectF();
    private long holdStarted;
    private static final int HOLD_NODE_BASE=1000, T9_DIRECT_ACTION_BASE=0x7f0a1000;
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
            if(letterKey(pressed) || isT9Digit(pressed)) openLetterChoices(pressed); else activateLongKey(pressed);
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
        if(switchLayout){shifted=false;capsLock=false;lastShift=0;}
        this.english=english; this.numeric=numeric; enterLabel=action;
        this.secure=secure;this.nineKey=nextNineKey;style=prefs.style();
        if (numeric) symbols=false;
        boolean nextDark=prefs.dark(getContext());if(nextDark!=dark){dark=nextDark;colors=new Palette(dark);}
        cancelTouch();layoutKeys();requestLayout();invalidate();
        if(switchLayout)animateSwitch();
    }
    void toggleSymbols() { cancelTouch();symbols=!symbols; secondSymbols=false; layoutKeys(); invalidate();animateSwitch(); }
    void nineState(boolean composing, List<String> readings) {
        List<String> next=readings.subList(0,Math.min(4,readings.size()));
        if(nineComposing==composing && nineReadings.equals(next))return;
        nineComposing=composing;nineReadings.clear();nineReadings.addAll(next);
        if(isNineKey() && nineRailStart>=0) {
            for(int i=0;i<4;i++) {
                Key previous=keys.get(nineRailStart+i), replacement=new Key(railValue(i),"");
                replacement.bounds.set(previous.bounds);
                if(previous.value.equals(replacement.value))continue;
                if(pressed==previous)cancelTouch();
                if(released==previous)released=null;
                keys.set(nineRailStart+i,replacement);
            }
            accessibilityChanged();invalidate();
        }
    }
    private void animateSwitch(){animate().cancel();setAlpha(.94f);animate().alpha(1f).setDuration(140).setInterpolator(new DecelerateInterpolator()).start();}
    private boolean isNineKey(){return nineKey&&!english&&!numeric&&!secure&&!symbols;}
    void shift() {
        if (symbols) { secondSymbols=!secondSymbols; layoutKeys(); invalidate(); return; }
        long now=android.os.SystemClock.uptimeMillis();
        if (shifted && !capsLock && now-lastShift<350) capsLock=true;
        else { shifted=!shifted; capsLock=false; }
        lastShift=now; invalidate(); accessibilityChanged();
    }
    void consumedLetter() { if (shifted && !capsLock) { shifted=false; invalidate(); accessibilityChanged(); } }
    boolean uppercase() { return shifted; }
    void enterLabel(String value) { if(!enterLabel.equals(value)){enterLabel=value;invalidate();accessibilityChanged();} }
    boolean isSymbols() { return symbols || numeric; }
    void resetModes() { symbols=false; shifted=false; capsLock=false; secondSymbols=false; cancelTouch(); }
    private float dp(float n) { return n*density; }
    private boolean landscape() { return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE; }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int height=(int)dp((landscape()?136:244)*prefs.height()+8);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(height,heightSpec));
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
        keys.clear();nineRailStart=-1; if(getWidth()==0) return;
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
            nineCell("SPLIT",1,0,1,pad,h);nineCell("2",2,0,1,pad,h);nineCell("3",3,0,1,pad,h);
            nineCell("4",1,1,1,pad,h);nineCell("5",2,1,1,pad,h);nineCell("6",3,1,1,pad,h);
            nineCell("7",1,2,1,pad,h);nineCell("8",2,2,1,pad,h);nineCell("9",3,2,1,pad,h);
            nineCell("⌫",4,0,1,pad,h);nineCell("CLEAR",4,1,1,pad,h);nineCell("ENTER",4,2,2,pad,h);
            nineCell("SYMBOLS",0,3,1,pad,h);nineCell("LANG",1,3,1,pad,h);
            nineCell("SPACE",2,3,1,pad,h);nineCell("NUMERIC",3,3,1,pad,h);
            nineCell("",0,0,3,pad,h);nineRail.set(keys.remove(keys.size()-1).bounds);
            nineRailStart=keys.size();
            for(int i=0;i<4;i++) {
                Key key=new Key(railValue(i),"");
                key.bounds.set(nineRail.left,nineRail.top+nineRail.height()*i/4,nineRail.right,nineRail.top+nineRail.height()*(i+1)/4);
                keys.add(key);
            }
        } else {
            row("q w e r t y u i o p".split(" "),"1 2 3 4 5 6 7 8 9 0".split(" "),pad,h,0,null);
            row("a s d f g h j k l".split(" "),null,pad+h,h,dp(16),null);
            row(new String[]{english||secure?"SHIFT":"SPLIT","z","x","c","v","b","n","m","⌫"},null,pad+h*2,h,0,new float[]{1.35f,1,1,1,1,1,1,1,1.35f});
            row(new String[]{"?123","LANG",english?",":"，","SPACE",english?".":"。","ENTER"},null,pad+h*3,h,0,new float[]{1.35f,1,0.9f,3.6f,0.9f,1.65f});
        }
        accessibilityChanged();
    }
    private void nineCell(String value,int column,int row,int span,float top,float height) {
        float unit=(getWidth()-dp(6))/4.6f;
        float left=dp(3)+(column==0?0:unit*(.78f+column-1));
        float width=unit*(column==0?.78f:column==4?.82f:1);
        Key key=new Key(value,"");key.bounds.set(left+dp(2),top+row*height+dp(3),left+width-dp(2),top+(row+span)*height-dp(3));keys.add(key);
    }
    private String railValue(int index) {return nineComposing?"READING_"+(index<nineReadings.size()?nineReadings.get(index):""):new String[]{"，","。","？","！"}[index];}
    private boolean railKey(Key key) {return nineRailStart>=0 && keys.indexOf(key)>=nineRailStart;}
    private boolean enabledKey(Key key) {return !key.value.equals("READING_");}
    private String label(Key k) {
        switch(k.value) {
            case "SHIFT":return symbols?(secondSymbols?"123":"#+="):(capsLock?"⇪":"⇧");
            case "SPLIT":return "分词";
            case "CLEAR":return "清空";
            case "SYMBOLS":return "符号";
            case "NUMERIC":return "123";
            case "SPACE":return cursorMode?(isNineKey()?"‹  ›":"‹  移动光标  ›"):(english?"English":isNineKey()?"空格":"轻语 · 拼音");
            case "LANG":return english?"EN":"中";
            case "ENTER":return enterLabel;
            default:return k.value.startsWith("READING_")?k.value.substring(8):isT9Digit(k)?t9Letters(k.value).toUpperCase(java.util.Locale.ROOT):shifted && k.value.length()==1?k.value.toUpperCase(java.util.Locale.ROOT):k.value;
        }
    }
    private boolean isT9Digit(Key key){return isNineKey()&&key.value.length()==1&&key.value.charAt(0)>='2'&&key.value.charAt(0)<='9';}
    private String t9Letters(String digit){switch(digit){case "2":return "abc";case "3":return "def";case "4":return "ghi";case "5":return "jkl";case "6":return "mno";case "7":return "pqrs";case "8":return "tuv";case "9":return "wxyz";default:return digit;}}
    private int blend(int base,int accent,float amount){return Color.rgb((int)(Color.red(base)+(Color.red(accent)-Color.red(base))*amount),(int)(Color.green(base)+(Color.green(accent)-Color.green(base))*amount),(int)(Color.blue(base)+(Color.blue(accent)-Color.blue(base))*amount));}
    @Override protected void onDraw(Canvas c) {
        c.drawColor(colors.background);
        float release= released==null?0:Math.max(0,1f-(android.os.SystemClock.uptimeMillis()-releaseTime)/120f);
        float radius=dp(style.equals("flat")?4:8);
        if(isNineKey()){paint.setColor(colors.key);c.drawRoundRect(nineRail,radius,radius,paint);}
        for(Key k:keys) {
            boolean enter=k.value.equals("ENTER");
            boolean rail=railKey(k);
            boolean function=k.value.length()>1 || k.value.equals("⌫");
            int base=enter?colors.accent:(function?colors.function:colors.key);
            if(k.value.equals("SHIFT") && shifted && !symbols)base=blend(base,colors.accent,.14f);
            paint.setColor(k==pressed?colors.pressed:k==released&&release>0?blend(base,colors.pressed,release):base);
            if(!rail || k==pressed || k==released&&release>0)c.drawRoundRect(k.bounds,radius,radius,paint);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));
            paint.setColor(enter?colors.accentText:k.value.equals("SHIFT") && shifted && !symbols || rail && nineComposing && keys.indexOf(k)==nineRailStart?colors.accent:colors.text);
            paint.setTextSize(dp(rail?(landscape()?15:18):k.value.equals("SPACE")?12:(k.value.length()>1?14:(isT9Digit(k)?(landscape()?19:23):(landscape()?17:22)))));
            if(rail){float width=k.bounds.width()-dp(8);if(paint.measureText(label(k))>width)paint.setTextSize(paint.getTextSize()*width/paint.measureText(label(k)));}
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(label(k),k.bounds.centerX(),k.bounds.centerY()-(paint.ascent()+paint.descent())/2,paint);
            String alternate=isT9Digit(k)?k.value:!english&&!numeric&&!secure&&(k.value.equals("，")||k.value.equals(","))?"'":k.alternate;
            if(!alternate.isEmpty()) {
                paint.setTextSize(dp(9)); paint.setColor(colors.secondary); paint.setTextAlign(Paint.Align.RIGHT);
                c.drawText(alternate,k.bounds.right-dp(5),k.bounds.top+dp(12),paint);
            }
        }
        if(release>0)postInvalidateOnAnimation();else released=null;
        if(holdChoices!=null) drawLetterChoices(c);
        else if(pressed!=null && prefs.preview() && !secure && !isT9Digit(pressed) && pressed.value.length()==1 && !pressed.value.equals("⌫") && !cursorMode) {
            float x=Math.max(dp(24),Math.min(getWidth()-dp(24),pressed.bounds.centerX())), y=Math.max(dp(2),pressed.bounds.top-dp(48));
            paint.setColor(colors.accent);
            c.drawRoundRect(x-dp(24),y,x+dp(24),y+dp(48),dp(12),dp(12),paint);
            paint.setColor(colors.accentText); paint.setTextSize(dp(isT9Digit(pressed)?18:26)); paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(label(pressed),x,y+dp(33),paint);
        }
        if(accessibilityFocus>0 && accessibilityFocus<=keys.size()) {
            paint.setColor(colors.accent);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));
            c.drawRoundRect(keys.get(accessibilityFocus-1).bounds,dp(8),dp(8),paint);paint.setStyle(Paint.Style.FILL);
        } else if(holdChoices!=null && accessibilityFocus>=HOLD_NODE_BASE && accessibilityFocus<HOLD_NODE_BASE+holdChoices.length) {
            letterChoiceBounds(accessibilityFocus-HOLD_NODE_BASE,choiceBounds);paint.setColor(colors.accent);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));
            c.drawRoundRect(choiceBounds,dp(7),dp(7),paint);paint.setStyle(Paint.Style.FILL);
        }
    }
    private Key hit(float x,float y) {
        // Entire cell responds, including visual gutters; keys remain physically separated.
        Key closest=null; float best=Float.MAX_VALUE;
        boolean insideRail=isNineKey() && x<=nineRail.right+dp(2) && y>=nineRail.top-dp(3) && y<=nineRail.bottom+dp(3);
        for(Key k:keys) {
            if(!enabledKey(k))continue;
            if(insideRail && !railKey(k))continue;
            if(y>=k.bounds.top-dp(3) && y<=k.bounds.bottom+dp(3)) {
                float distance=Math.abs(k.bounds.centerX()-x);
                if(railKey(k) && (x<k.bounds.left-dp(2) || x>k.bounds.right+dp(2)))continue;
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
        if(!english && shifted && letterKey(key)) {
            listener.longKey("DIRECT_"+key.value.toUpperCase(java.util.Locale.ROOT));
            consumedLetter();
        } else listener.key(key.value);
        if(id>0) sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_CLICKED,name);
    }
    private boolean characterKey(Key key) { return key.value.length()==1 && !key.value.equals("⌫"); }
    private boolean letterKey(Key key) { return key.value.length()==1 && key.value.charAt(0)>='a' && key.value.charAt(0)<='z'; }
    private void openLetterChoices(Key key) {
        holdNine=isT9Digit(key);
        String lower=holdNine?t9Letters(key.value):key.value, upper=lower.toUpperCase(java.util.Locale.ROOT);
        if(holdNine) {
            holdColumns=lower.length()+1;holdChoices=new String[lower.length()*2+1];
            for(int i=0;i<lower.length();i++){holdChoices[i]=upper.substring(i,i+1);holdChoices[i+lower.length()]=lower.substring(i,i+1);}
            holdChoices[holdChoices.length-1]=key.value;holdChoice=lower.length();
        } else {holdColumns=key.alternate.isEmpty()?2:3;holdChoices=key.alternate.isEmpty()?new String[]{upper,lower}:new String[]{upper,lower,key.alternate};holdChoice=1;}
        holdStarted=android.os.SystemClock.uptimeMillis();
        float width=Math.min(getWidth()-dp(8),dp(44)*holdColumns);
        float left=Math.max(dp(4),Math.min(getWidth()-dp(4)-width,key.bounds.centerX()-width/2));
        float height=Math.min(getHeight()-dp(8),dp(holdNine?96:56));
        float top=Math.max(dp(4),Math.min(getHeight()-dp(4)-height,key.bounds.top-height-dp(3)));
        holdBounds.set(left,top,left+width,top+height);accessibilityChanged();
    }
    private void letterChoiceBounds(int index,RectF out) {
        float width=holdBounds.width()/holdColumns;
        if(!holdNine){out.set(holdBounds.left+index*width,holdBounds.top,holdBounds.left+(index+1)*width,holdBounds.bottom);return;}
        int count=holdColumns-1;
        if(index==holdChoices.length-1){out.set(holdBounds.left+count*width,holdBounds.top,holdBounds.right,holdBounds.bottom);return;}
        int column=index%count,row=index/count;
        out.set(holdBounds.left+column*width,holdBounds.top+row*holdBounds.height()/2,holdBounds.left+(column+1)*width,holdBounds.top+(row+1)*holdBounds.height()/2);
    }
    private void drawLetterChoices(Canvas c) {
        float progress=Math.min(1f,(android.os.SystemClock.uptimeMillis()-holdStarted)/90f);
        int save=c.save();c.translate(0,dp(3)*(1f-progress));
        paint.setColor(holdNine?colors.key:colors.function);paint.setAlpha((int)(255*progress));
        c.drawRoundRect(holdBounds,dp(10),dp(10),paint);
        if(holdNine){paint.setColor(colors.border);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));c.drawRoundRect(holdBounds,dp(10),dp(10),paint);paint.setStyle(Paint.Style.FILL);}
        for(int i=0;i<holdChoices.length;i++) {
            letterChoiceBounds(i,choiceBounds);
            if(i==holdChoice) {
                paint.setColor(colors.accent);paint.setAlpha((int)(255*progress));
                c.drawRoundRect(choiceBounds.left+dp(3),choiceBounds.top+dp(3),choiceBounds.right-dp(3),choiceBounds.bottom-dp(3),dp(7),dp(7),paint);
            }
            paint.setColor(i==holdChoice?colors.accentText:colors.text);paint.setAlpha((int)(255*progress));
            paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(dp(23));
            c.drawText(holdChoices[i],choiceBounds.centerX(),holdNine?choiceBounds.centerY()-(paint.ascent()+paint.descent())/2:holdBounds.top+dp(29),paint);
            if(!holdNine){paint.setTextSize(dp(9));c.drawText(i==0?"大写":i==1?"小写":"数字",choiceBounds.centerX(),holdBounds.bottom-dp(8),paint);}
        }
        paint.setAlpha(255);c.restoreToCount(save);
        if(progress<1f)postInvalidateOnAnimation();
    }
    private int hitLetterChoice(float x,float y) {
        if(holdChoices==null || !holdBounds.contains(x,y))return -1;
        for(int i=0;i<holdChoices.length;i++){letterChoiceBounds(i,choiceBounds);if(choiceBounds.contains(x,y))return i;}
        return -1;
    }
    private void selectLetterChoice(float x,float y) {
        if(holdNine && Math.abs(x-downX)<dp(4) && Math.abs(y-downY)<dp(4))return;
        // Edge keys need a shorter travel distance so the finger can stay inside the keyboard.
        float delta=x-downX;
        float left=Math.min(dp(16),downX*.6f), right=Math.min(dp(16),(getWidth()-downX)*.6f);
        int next;
        if(holdNine) {
            next=hitLetterChoice(x,y);
            if(next<0) {
                int count=holdColumns-1;
                float step=Math.min(dp(24),Math.max(dp(10),(getWidth()-downX-dp(2))/count));
                int column=Math.max(0,Math.min(count,Math.round((delta<-left?-delta-left:delta)/step)));
                next=column==count?holdChoices.length-1:column+(delta<-left || y-downY<-dp(18)?0:count);
            }
        } else next=delta < -left?0:holdChoices.length==3 && delta>right?2:1;
        if(next!=holdChoice){holdChoice=next;feedback();sendVirtualEvent(HOLD_NODE_BASE+next,AccessibilityEvent.TYPE_VIEW_SELECTED,directName(holdChoices[next]));invalidate();}
    }
    private boolean hasLongAction(Key key) {
        return letterKey(key) || isT9Digit(key) || !key.alternate.isEmpty() || key.value.equals("SPACE") || key.value.equals("LANG") || key.value.equals("SHIFT")
            || key.value.equals("ENTER") || key.value.equals(",") || key.value.equals("，") || key.value.equals(".") || key.value.equals("。");
    }
    private void activateLongKey(Key key) {
        if(letterKey(key)){listener.longKey("DIRECT_"+key.value);consumedLetter();}
        else if(isT9Digit(key))listener.longKey("DIRECT_"+t9Letters(key.value).substring(0,1));
        else if(!key.alternate.isEmpty())listener.key(key.alternate);
        else if(key.value.equals("SPACE")){cursorMode=true;cursorX=downX;}
        else if(!english && !numeric && !secure && (key.value.equals(",")||key.value.equals("，")))listener.longKey("APOSTROPHE");
        else listener.longKey(key.value);
    }
    private String accessibilityName(Key key) {
        switch(key.value) {
            case "SHIFT":return symbols?(secondSymbols?"数字符号页":"更多符号"):(capsLock?"大写已锁定":"切换大小写");
            case "SPLIT":return "拼音分词";
            case "CLEAR":return "清空当前拼音";
            case "SYMBOLS":return "符号键盘";
            case "NUMERIC":return "数字键盘";
            case "SPACE":return "空格";
            case "LANG":return secure?"安全输入，英文键盘":(english?"切换中文":"切换英文");
            case "ENTER":return enterLabel.equals("拼音")?"提交原始拼音":enterLabel.equals("↵")?"换行":enterLabel;
            case "⌫":return "删除";
            case "?123":return "数字和符号";
            case "ABC":return "字母键盘";
            case "，":return "逗号";
            case "。":return "句号";
            default:return key.value.startsWith("READING_")?"选择拼音 "+label(key):label(key);
        }
    }
    private String longHint(Key key) {
        if(isT9Digit(key))return "长按后滑动选择大小写字母或数字 "+key.value+"，松手输入";
        if(letterKey(key))return key.alternate.isEmpty()?"长按后左滑输入大写，右侧输入小写":"长按后左滑大写，中间小写，右滑数字 "+key.alternate;
        if(!key.alternate.isEmpty())return "长按输入 "+key.alternate;
        if(key.value.equals("SPACE"))return "长按进入光标操作，更多操作可左右移动光标";
        if(key.value.equals("LANG"))return "长按选择系统输入法";
        if(!english&&!numeric&&(key.value.equals("，")||key.value.equals(",")))return "长按输入拼音隔音符";
        if(key.value.equals("⌫"))return "长按删除上一词";
        return hasLongAction(key)?"支持长按":"";
    }
    private String directName(String value) {char letter=value.charAt(0);return "输入"+(Character.isDigit(letter)?"数字 ":Character.isUpperCase(letter)?"大写 ":"小写 ")+value;}
    private String virtualName(int id) {return id>=HOLD_NODE_BASE && holdChoices!=null && id<HOLD_NODE_BASE+holdChoices.length?directName(holdChoices[id-HOLD_NODE_BASE]):id>0 && id<=keys.size()?accessibilityName(keys.get(id-1)):"";}
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
                int choice=hitLetterChoice(event.getX(),event.getY());
                if(choice>=0)next=HOLD_NODE_BASE+choice;
                else {Key key=hit(event.getX(),event.getY());if(key!=null)next=keys.indexOf(key)+1;}
            }
            int previous=hoveredNode;
            if(next!=previous) {
                hoveredNode=next;
                if(next!=NO_ID)sendVirtualEvent(next,AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,virtualName(next));
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
                if(holdChoices!=null)for(int i=0;i<holdChoices.length;i++)node.addChild(KeyboardSurface.this,HOLD_NODE_BASE+i);
                return node;
            }
            if(holdChoices!=null && id>=HOLD_NODE_BASE && id<HOLD_NODE_BASE+holdChoices.length) {
                int index=id-HOLD_NODE_BASE;AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain();
                node.setSource(KeyboardSurface.this,id);node.setParent(KeyboardSurface.this);node.setPackageName(getContext().getPackageName());node.setClassName("android.widget.Button");
                node.setViewIdResourceName(getContext().getPackageName()+":id/hold_"+holdChoices[index]);node.setText(holdChoices[index]);node.setContentDescription(directName(holdChoices[index]));
                node.setEnabled(isEnabled());node.setFocusable(true);node.setClickable(isEnabled());node.setVisibleToUser(isShown());node.setSelected(index==holdChoice);node.setAccessibilityFocused(id==accessibilityFocus);
                letterChoiceBounds(index,choiceBounds);Rect bounds=new Rect();choiceBounds.roundOut(bounds);node.setBoundsInParent(bounds);
                int[] screen=new int[2];getLocationOnScreen(screen);bounds.offset(screen[0],screen[1]);node.setBoundsInScreen(bounds);
                node.addAction(id==accessibilityFocus?AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                if(isEnabled())node.addAction(AccessibilityNodeInfo.ACTION_CLICK);return node;
            }
            if(id<1||id>keys.size())return null;
            Key key=keys.get(id-1);AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain();
            node.setSource(KeyboardSurface.this,id);node.setParent(KeyboardSurface.this);
            node.setPackageName(getContext().getPackageName());node.setClassName("android.widget.Button");
            node.setViewIdResourceName(getContext().getPackageName()+":id/key_"+key.value);
            node.setText(label(key));node.setContentDescription(accessibilityName(key));node.setHintText(longHint(key));
            node.setEnabled(isEnabled()&&enabledKey(key)&&(!secure||!key.value.equals("LANG")));node.setFocusable(node.isEnabled());node.setClickable(node.isEnabled());
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
            if(isT9Digit(key)) {
                String lower=t9Letters(key.value),choices=lower.toUpperCase(java.util.Locale.ROOT)+lower+key.value;
                for(int i=0;i<choices.length();i++)node.addAction(new AccessibilityNodeInfo.AccessibilityAction(T9_DIRECT_ACTION_BASE+choices.charAt(i),directName(choices.substring(i,i+1))));
            }
            return node;
        }
        @Override public AccessibilityNodeInfo findFocus(int focus) {
            return focus==AccessibilityNodeInfo.FOCUS_ACCESSIBILITY&&accessibilityFocus!=NO_ID?createAccessibilityNodeInfo(accessibilityFocus):null;
        }
        @Override public boolean performAction(int id,int action,Bundle arguments) {
            if(id==HOST_VIEW_ID)return KeyboardSurface.this.performAccessibilityAction(action,arguments);
            boolean choice=holdChoices!=null && id>=HOLD_NODE_BASE && id<HOLD_NODE_BASE+holdChoices.length;
            if((!choice && (id<1||id>keys.size()))||!isShown()||!isEnabled())return false;
            if(action==AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                if(accessibilityFocus==id)return false;
                if(accessibilityFocus!=NO_ID)sendVirtualEvent(accessibilityFocus,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,null);
                accessibilityFocus=id;invalidate();sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,virtualName(id));return true;
            }
            if(action==AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                if(accessibilityFocus!=id)return false;
                accessibilityFocus=NO_ID;invalidate();sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,null);return true;
            }
            if(choice) {
                if(action!=AccessibilityNodeInfo.ACTION_CLICK)return false;
                String value=holdChoices[id-HOLD_NODE_BASE];cancelTouch();feedback();listener.longKey("DIRECT_"+value);
                sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_CLICKED,directName(value));return true;
            }
            Key key=keys.get(id-1);if(!enabledKey(key))return false;
            if(isT9Digit(key) && action>=T9_DIRECT_ACTION_BASE && action<T9_DIRECT_ACTION_BASE+128) {
                String value=String.valueOf((char)(action-T9_DIRECT_ACTION_BASE)),letters=t9Letters(key.value);
                if(!(letters+letters.toUpperCase(java.util.Locale.ROOT)+key.value).contains(value))return false;
                cancelTouch();feedback();listener.longKey("DIRECT_"+value);sendVirtualEvent(id,AccessibilityEvent.TYPE_VIEW_CLICKED,directName(value));return true;
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
            if(holdChoices!=null)for(int i=0;i<holdChoices.length;i++)if((id==HOST_VIEW_ID||id==HOLD_NODE_BASE+i)&&directName(holdChoices[i]).toLowerCase(java.util.Locale.ROOT).contains(needle))result.add(createAccessibilityNodeInfo(HOLD_NODE_BASE+i));
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
            if(x<0 || x>=getWidth() || y<0 || y>=getHeight()){cancelTouch();return true;}
            if(holdChoices!=null){selectLetterChoice(x,y);return true;}
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
            if(!longFired && !dragged) { Key next=hit(x,y); if(next!=null && next!=pressed){timer.removeCallbacks(longPress);pressed=next;invalidate();} }
            return true;
        }
        if(action==MotionEvent.ACTION_UP) {
            if(holdChoices!=null && x>=0 && x<getWidth() && y>=0 && y<getHeight()) {
                selectLetterChoice(x,y);String chosen=holdChoices[holdChoice];Key key=pressed;
                cancelTouch();released=key;releaseTime=android.os.SystemClock.uptimeMillis();
                listener.longKey("DIRECT_"+chosen);if(chosen.length()==1 && Character.isLetter(chosen.charAt(0)))consumedLetter();
                performClick();
            } else if(pressed!=null && !longFired && !dragged && !cursorMode && x>=0 && x<getWidth() && y>=0 && y<getHeight()) { releaseKey(pressed); performClick(); }
            cancelTouch(); return true;
        }
        if(action==MotionEvent.ACTION_CANCEL) { cancelTouch(); return true; }
        if(action==MotionEvent.ACTION_POINTER_DOWN) {
            if(holdChoices!=null){cancelTouch();return true;}
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
    void cancelTouch(){timer.removeCallbacks(repeat);timer.removeCallbacks(longPress);pressed=null;boolean hadChoices=holdChoices!=null;holdChoices=null;holdNine=false;cursorMode=false;if(hadChoices){if(accessibilityFocus>=HOLD_NODE_BASE)clearVirtualFocus();accessibilityChanged();}invalidate();}
    @Override protected void onDetachedFromWindow(){animate().cancel();setAlpha(1f);cancelTouch();released=null;clearVirtualFocus();super.onDetachedFromWindow();}
}

