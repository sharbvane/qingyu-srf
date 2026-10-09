package com.qingyu.ime;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/** Sequential T9 choices exercise the installed keyboard, not a mock decoder. */
public class ImeV66Instrumentation extends ImeV65Instrumentation {
    @Override protected String successMarker(){return "ALL_V66_IME_CHECKS_PASS";}
    @Override protected void runChecks() throws Exception {
        super.runChecks();
        selectMode("中文 · 九键拼音");
        setText("前文");type("64426744543");awaitPinyin("");
        Rect body=bounds("panel_host"),two=bounds("key_2");
        String prefix="";
        for(String syllable:new String[]{"ni","hao","shi","jie"}){
            selectReading(syllable);prefix+=prefix.isEmpty()?syllable:"'"+syllable;
            String pinyin=awaitPinyin("");check(pinyin.startsWith(prefix),"Selected syllables changed: "+pinyin+" expected "+prefix);
            check(text().equals("前文"+composing()),"A syllable choice committed text prematurely: "+text());
            check(body.equals(bounds("panel_host"))&&two.equals(bounds("key_2")),"Rail update moved the keyboard");
        }
        check(awaitPinyin("").equals("ni'hao'shi'jie"),"Complete pinyin did not retain all choices");
        awaitNode("key_READING_BACK");screenshot("nine-sequential.png");
        pass("four successive syllable choices advance the rail, retain original editor text and never commit early or resize keys");

        key("READING_BACK");selectReading("jie");check(awaitPinyin("").equals("ni'hao'shi'jie"),"Backtrack changed raw input");
        key("⌫");selectReading("jie");check(awaitPinyin("").equals("ni'hao'shi'jie"),"Fully selected delete removed raw input instead of undoing the final choice");
        findExpandedCandidate("你好世界");clickCandidate("你好世界");awaitText("前文你好世界");awaitNoComposition();
        pass("rail back and fully selected delete reopen the last syllable without deleting digits; only candidate selection commits 你好世界");

        clear();type("7426");awaitCandidate("前");awaitPinyin("");
        AccessibilityNodeInfo first=firstReading(),host=first.getParent();Rect railCell=new Rect();first.getBoundsInScreen(railCell);
        check(host.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),"Reading rail has no accessible downward scroll");SystemClock.sleep(120);
        check(find("key_READING_BACK")==null&&!composing().isEmpty()&&text().equals(composing()),"Scrolling the rail selected or committed a syllable");
        screenshot("nine-rail-scroll.png");selectReading("qiao");check(awaitPinyin("").startsWith("qiao"),"Offscreen qiao did not select");
        check(body.equals(bounds("panel_host"))&&two.equals(bounds("key_2")),"Scrollable readings escaped the keyboard");
        key("READING_BACK");awaitPinyin("");first=firstReading();first.getBoundsInScreen(railCell);
        swipe(railCell.centerX(),railCell.bottom-4,railCell.centerX(),railCell.top-100,220);SystemClock.sleep(120);
        check(!composing().isEmpty()&&!composing().matches(".*[2-9].*"),"Rail drag leaked raw digits");
        key("CLEAR");awaitText("");awaitNoComposition();
        pass("more than four actual pinyin options scroll in a fixed rail; scrolling alone does not select and an offscreen qiao is selectable");

        type("64426");selectReading("ni");key("⌫");awaitPinyin("");type("6");selectReading("hao");
        findExpandedCandidate("你好");clickCandidate("你好");awaitText("你好");awaitNoComposition();
        pass("editing an unselected raw tail preserves the selected ni and restores the next syllable on retyping");
        clear();
    }
    private AccessibilityNodeInfo firstReading(){
        AccessibilityNodeInfo panel=awaitNode("panel_host");
        AccessibilityNodeInfo found=findReading(panel);check(found!=null,"No visible reading in the rail");return found;
    }
}
