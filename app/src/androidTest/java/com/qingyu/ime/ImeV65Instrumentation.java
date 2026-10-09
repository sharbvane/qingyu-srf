package com.qingyu.ime;

import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.InputType;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.BaseInputConnection;

/** Nine-key acceptance exercises the installed IME with real editor and pointer events. */
public class ImeV65Instrumentation extends ImeV2Instrumentation {
    @Override protected String successMarker(){return "ALL_V65_IME_CHECKS_PASS";}
    @Override protected void runChecks() throws Exception {
        selectMode("中文 · 九键拼音");
        assertNineLayout();check(composing().isEmpty()&&find("candidate_0")==null,"Idle nine-key retains composition");
        for(String nav:new String[]{"toolbar_more","toolbar_edit","toolbar_emoji","toolbar_mode","toolbar_hide"})check(find(nav)!=null,"Idle navigation missing "+nav);
        Rect body=bounds("panel_host"),two=bounds("key_2");screenshot("nine-idle.png");
        pass("nine-key idle has five columns, left punctuation, segmentation, clear, tall confirm and normal navigation");

        type("5485426");awaitCandidate(null);awaitPinyin("");
        check(body.equals(bounds("panel_host"))&&two.equals(bounds("key_2")),"Nine-key candidates resized key geometry");
        check("确认".contentEquals(awaitNode("key_ENTER").getText()),"Composing nine-key has no confirm label");
        screenshot("nine-pinyin.png");
        pass("5485426 displays live alphabetic pinyin in both preedit and real composing span; fixed candidate/key geometry");

        selectReading("jiu");String restricted=awaitPinyin("");check(restricted.startsWith("jiu"),"Side reading failed to restrict the leading syllable: "+restricted);
        findExpandedCandidate("九键");clickCandidate("九键");awaitText("九键");awaitNoComposition();
        pass("side jiu restricts ambiguity; 九键 is selectable and selection removes the composing bubble");

        clear();key("5");String shortReading=awaitPinyin("");check(!shortReading.isEmpty(),"Incomplete input has no pinyin");key("4");awaitPinyin("");key("⌫");awaitPinyin("");key("⌫");awaitText("");awaitNoComposition();
        pass("incomplete nine-key input and deletion show letters without leaking the digit code");

        clear();type("524329426468");awaitCandidate(null);findExpandedCandidate("开发");clickCandidate("开发");
        String remainder=awaitPinyin("开发");check(!remainder.isEmpty(),"Partial pick lost the remaining pinyin");findExpandedCandidate("项目");clickCandidate("项目");awaitText("开发项目");awaitNoComposition();
        pass("partial candidate pick preserves the selected Chinese prefix and readable remaining pinyin until complete commit");

        clear();key("SPLIT");awaitText("");type("64");awaitPinyin("");key("SPLIT");String split=awaitPinyin("");check(split.endsWith("'"),"Nine-key segmentation did not show a boundary: "+split);
        key("SPLIT");check(!awaitPinyin("").contains("''"),"Repeated segmentation inserted duplicate boundaries");type("426");awaitCandidate("你好");clickCandidate("你好");awaitText("你好");
        pass("segmentation ignores empty/repeated separators and 64 plus 426 still selects 你好");

        setText("已输入");type("64426");awaitCandidate("你好");key("CLEAR");awaitText("已输入");awaitNoComposition();key("CLEAR");awaitText("已输入");
        pass("clear removes current composition and candidates while preserving committed editor text");

        clear();type("5485426");awaitCandidate(null);awaitPinyin("");String first=awaitNode("candidate_0").getText().toString();key("ENTER");awaitText(first);awaitNoComposition();
        pass("nine-key confirm selects its actual first full candidate rather than submitting digits");

        clear();long hold=beginNineHold("2");check(text().isEmpty()&&composing().isEmpty(),"Nine-key hold committed before release");
        Rect keyboard=bounds("key_2");awaitNode("key_2").getParent().getBoundsInScreen(keyboard);
        for(String choice:new String[]{"A","B","C","a","b","c","2"}){Rect option=bounds("hold_"+choice);check(keyboard.contains(option),"Letter picker escaped the keyboard: "+choice);}
        screenshot("nine-letter-picker.png");endNineHold(hold,"C",false);awaitText("C");awaitNoComposition();
        hold=beginNineHold("2");check(text().equals("C"),"Lowercase hold committed early");endNineHold(hold,"b",false);awaitText("Cb");awaitNoComposition();
        hold=beginNineHold("2");endNineHold(hold,"2",false);awaitText("Cb2");awaitNoComposition();
        pass("nine-key picker exposes all upper/lower letters and number, highlights a slide, commits exactly once on release without pinyin");

        hold=beginNineHold("2");endNineHold(hold,"A",true);awaitText("Cb2");check(find("hold_A")==null,"Cancelled picker remains visible");
        hold=beginNineHold("2");Rect oldKey=bounds("key_2");selectMode("English");
        MotionEvent up=MotionEvent.obtain(hold,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,oldKey.centerX(),oldKey.centerY(),0);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);check(automation.injectInputEvent(up,true),"Mode-change release failed");up.recycle();SystemClock.sleep(160);awaitText("Cb2");check(find("hold_A")==null,"Mode switch retained the picker");
        pass("pointer cancellation and switching keyboard while holding discard the pending character");

        clear();type("hel");awaitCandidate("hello");clickCandidate("hello");awaitText("hello ");clear();key("SHIFT");type("hel");awaitCandidate("Hello");clickCandidate("Hello");awaitText("Hello ");
        clear();selectMode("中文 · 全键拼音");type("dan");key("SPLIT");type("gang");awaitCandidate("单杠");key("ENTER");awaitText("dan'gang");
        pass("English completion and Shift remain usable; full-pinyin Enter still commits the original segmented letters");

        clear();selectMode("中文 · 九键拼音");key("NUMERIC");keyboardReady();type("123");awaitText("123");key("ABC");keyboardReady();assertNineLayout();
        clear();key("SYMBOLS");keyboardReady();key("1");awaitText("1");key("ABC");keyboardReady();assertNineLayout();
        setText("abcdefghijk");hold("⌫",1200);awaitText("");type("64426");awaitCandidate("你好");hold("⌫",1200);awaitText("");awaitNoComposition();
        pass("explicit number/symbol entry returns to nine-key; held delete still repeats through composition and committed text");

        getTargetContext().getSharedPreferences("qingyu",0).edit().putString("theme","dark").apply();clear();assertNineLayout();type("64426");awaitCandidate("你好");awaitPinyin("");screenshot("nine-dark.png");
        runOnMainSync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));SystemClock.sleep(500);keyboardReady();assertNineLayout();awaitPinyin("");awaitCandidate("你好");screenshot("nine-landscape.png");clickCandidate("你好");awaitText("你好");
        runOnMainSync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));SystemClock.sleep(500);keyboardReady();
        getTargetContext().getSharedPreferences("qingyu",0).edit().putString("theme","light").apply();clear();
        newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);keyboardReady();check(find("key_a")!=null&&find("key_2")==null,"Password field exposed Chinese nine-key");type("abc");awaitText("abc");check(find("candidate_0")==null,"Password field exposed candidates");
        newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);keyboardReady();assertNineLayout();clear();
        pass("dark and landscape nine-key preserve geometry and composition; private password input remains direct English without candidates");
    }
    protected Rect bounds(String id){Rect r=new Rect();awaitNode(id).getBoundsInScreen(r);return r;}
    private void assertNineLayout(){
        Rect split=bounds("key_SPLIT"),two=bounds("key_2"),three=bounds("key_3"),four=bounds("key_4"),five=bounds("key_5"),six=bounds("key_6"),seven=bounds("key_7"),eight=bounds("key_8"),nine=bounds("key_9"),delete=bounds("key_⌫"),clear=bounds("key_CLEAR"),enter=bounds("key_ENTER"),body=bounds("panel_host");
        check(split.centerX()<two.centerX()&&two.centerX()<three.centerX()&&three.centerX()<delete.centerX(),"Nine-key top row is not segmentation/ABC/DEF/delete");
        check(split.centerY()==two.centerY()&&two.centerY()==three.centerY()&&three.centerY()==delete.centerY(),"Nine-key top row is misaligned");
        check(four.centerY()==five.centerY()&&five.centerY()==six.centerY()&&six.centerY()==clear.centerY()&&four.centerX()<five.centerX()&&five.centerX()<six.centerX(),"Nine-key middle row is misaligned");
        check(seven.centerY()==eight.centerY()&&eight.centerY()==nine.centerY()&&seven.centerX()<eight.centerX()&&eight.centerX()<nine.centerX(),"Nine-key lower letters are misaligned");
        check(clear.centerX()>six.centerX()&&enter.centerX()>nine.centerX()&&enter.top<=nine.top&&enter.height()>nine.height()*1.5f,"Clear and tall confirm are not in the right column");
        if(composing().isEmpty())for(String symbol:new String[]{"，","。","？","！"})check(bounds("key_"+symbol).right<=split.left,"Idle punctuation is not in the left auxiliary column");
        for(String key:new String[]{"SPLIT","2","3","4","5","6","7","8","9","⌫","CLEAR","ENTER","SYMBOLS","LANG","SPACE","NUMERIC"})check(body.contains(bounds("key_"+key)),"Nine-key escaped keyboard bounds: "+key);
    }
    protected String composing(){String[] value={""};runOnMainSync(()->{int start=BaseInputConnection.getComposingSpanStart(editor.getText()),end=BaseInputConnection.getComposingSpanEnd(editor.getText());if(start>=0&&end>=start)value[0]=editor.getText().subSequence(start,end).toString();});return value[0];}
    protected String awaitPinyin(String prefix){
        long until=SystemClock.uptimeMillis()+5000;String actual="";
        while(SystemClock.uptimeMillis()<until){actual=accessiblePinyin();if(actual.matches("[a-z' ]+")&&composing().equals(prefix+actual))return actual;SystemClock.sleep(35);}
        throw new AssertionError("Pinyin preedit/composition mismatch: bubble="+actual+" editor="+text()+" composing="+composing()+" expected prefix="+prefix);
    }
    // Android omits non-touchable popup windows; the candidate host exposes the same spelling.
    private String accessiblePinyin(){for(AccessibilityWindowInfo window:automation.getWindows()){String reading=pinyinDescription(window.getRoot());if(reading!=null)return reading;}return "";}
    private String pinyinDescription(AccessibilityNodeInfo node){if(node==null)return null;String description=String.valueOf(node.getContentDescription()),marker="原始拼音 · ";int at=description.indexOf(marker);if(at>=0)return description.substring(at+marker.length());for(int i=0;i<node.getChildCount();i++){String reading=pinyinDescription(node.getChild(i));if(reading!=null)return reading;}return null;}
    protected void awaitNoComposition(){long until=SystemClock.uptimeMillis()+4000;while(SystemClock.uptimeMillis()<until){if(accessiblePinyin().isEmpty()&&composing().isEmpty())return;SystemClock.sleep(35);}throw new AssertionError("Composition survived commit: "+composing());}
    protected void findExpandedCandidate(String word){
        awaitCandidate(null);
        if(candidate(word)!=null)return;nodeClick("candidate_expand");SystemClock.sleep(180);
        for(int attempt=0;attempt<12&&candidate(word)==null;attempt++){AccessibilityNodeInfo host=awaitNode("panel_host"),visible=null;for(int i=0;i<128&&visible==null;i++)visible=walk(host,"candidate_"+i);check(visible!=null,"Expanded nine-key candidates unavailable");if(!visible.getParent().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))break;SystemClock.sleep(160);}
        check(candidate(word)!=null,"Missing expanded nine-key candidate "+word+"; editor="+text());
    }
    protected AccessibilityNodeInfo findReading(AccessibilityNodeInfo node){
        String id=node.getViewIdResourceName();if(id!=null&&id.contains("key_READING_")&&!id.endsWith("BACK"))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null){AccessibilityNodeInfo found=findReading(child);if(found!=null)return found;}}return null;
    }
    protected void selectReading(String reading){
        String id="key_READING_"+reading;
        long until=SystemClock.uptimeMillis()+5000;boolean forward=true;
        while(SystemClock.uptimeMillis()<until){
            if(android.os.Build.VERSION.SDK_INT>=33)automation.clearCache();
            AccessibilityNodeInfo choice=find(id);
            // Rail positions are reused after each selection; discard cached virtual nodes before acting.
            if(choice!=null&&choice.performAction(AccessibilityNodeInfo.ACTION_CLICK)){SystemClock.sleep(160);return;}
            AccessibilityNodeInfo first=findReading(awaitNode("panel_host"));
            if(first!=null){AccessibilityNodeInfo host=first.getParent();int action=forward?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;if(!host.performAction(action))forward=!forward;}
            SystemClock.sleep(100);
        }
        throw new AssertionError("No selectable side pinyin: "+reading+" composing="+composing());
    }
}
