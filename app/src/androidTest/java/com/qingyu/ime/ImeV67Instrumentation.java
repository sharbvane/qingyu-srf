package com.qingyu.ime;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/** T9 matching and rail choices run against the installed, signed IME. */
public final class ImeV67Instrumentation extends ImeV66Instrumentation {
    @Override protected String successMarker(){return "ALL_V67_IME_CHECKS_PASS";}
    @Override protected void runChecks() throws Exception {
        if(debugTarget()){KeyboardTouchCheck.run(this,activity);pass("actual touch and accessibility checks cover split digit 1, all ABC rail choices, cancellation and existing keyboard gestures");}
        super.runChecks();
        selectMode("中文 · 九键拼音");
        for(String option:new String[]{"A","B","C","a","b","c","2"}){
            clear();key("2");awaitCandidate(null);selectReading(option);
            if(option.matches("[a-z]")){
                check(awaitPinyin("").equals(option),"Single letter did not constrain its raw key: "+option);
                check(text().equals(composing()),"Selecting a lowercase letter committed early");
            }else{
                check(composing().equals(option),"Explicit rail character missing: "+option+" got "+composing());
                key("ENTER");awaitText(option);awaitNoComposition();
            }
        }
        pass("ABC rail offers all A/B/C/a/b/c/2 entries through scrolling; lowercase constrains pinyin and explicit characters confirm without duplication");

        clear();type("773");awaitFirst("输入法");
        check(!awaitPinyin("").matches(".*[2-9].*"),"Initials leaked the numeric code");screenshot("nine-initials.png");
        for(String letter:new String[]{"s","r","f"})selectReading(letter);
        check(awaitPinyin("").equals("s'r'f"),"Initial choices did not advance one raw key at a time: "+composing());
        check(text().equals(composing()),"Initial choices committed early");
        key("READING_BACK");selectReading("f");awaitFirst("输入法");key("ENTER");awaitText("输入法");awaitNoComposition();
        pass("773 ranks 输入法 first; s/r/f choices advance, backtrack and retain the original raw keys until confirmation");

        clear();type("64426");
        for(String letter:new String[]{"n","i","h","a","o"})selectReading(letter);
        awaitCandidate("你好");check(text().equals(composing()),"Individual full-pinyin letters committed early");
        clickCandidate("你好");awaitText("你好");awaitNoComposition();
        pass("individual n/i/h/a/o choices join into full syllables while the same controls also accept initials");

        for(String code:new String[]{"74873","7732","7487832"}){
            clear();type(code);awaitCandidate("输入法");findExpandedCandidate("输入法");clickCandidate("输入法");awaitText("输入法");awaitNoComposition();
        }
        clear();type("74873");selectReading("shu");selectReading("r");selectReading("f");
        check(awaitPinyin("").equals("shu'r'f"),"Mixed spelling lost the selected segments");
        awaitCandidate("输入法");clickCandidate("输入法");awaitText("输入法");awaitNoComposition();
        pass("full syllables, initials and mixed spelling match the same modern word and consume exactly the selected raw input");

        clear();type("995377");
        for(String letter:new String[]{"w","y","k","d","s","p"})selectReading(letter);
        findExpandedCandidate("我要看短视频");screenshot("nine-initials-sentence.png");clickCandidate("我要看短视频");awaitText("我要看短视频");awaitNoComposition();
        pass("long-sentence initials use the same syllable graph and context composition; selecting the full sentence leaves no raw tail");

        clear();type("23");awaitCandidate(null);selectReading("A");
        check(composing().startsWith("A")&&composing().length()>1,"Explicit A discarded the unselected tail: "+composing());
        key("READING_BACK");awaitPinyin("");selectReading("b");selectReading("3");
        check(composing().equals("b3"),"Explicit number lost a chosen lowercase prefix: "+composing());
        key("READING_BACK");check(awaitPinyin("").startsWith("b"),"Back did not restore the prefix and raw tail");
        key("CLEAR");awaitText("");awaitNoComposition();
        pass("literal rail choices preserve unselected keys and selected spelling in composing; back restores the original segment and clear commits nothing");

        Rect split=bounds("key_SPLIT");long start=SystemClock.uptimeMillis();touch(split.centerX(),split.centerY(),true,start);SystemClock.sleep(620);awaitNode("hold_1");
        check(text().isEmpty(),"Split hold entered 1 before release");screenshot("nine-split-one.png");endNineHold(start,"1",false);awaitText("1");awaitNoComposition();
        clear();start=SystemClock.uptimeMillis();touch(split.centerX(),split.centerY(),true,start);SystemClock.sleep(620);awaitNode("hold_1");endNineHold(start,"1",true);awaitText("");
        type("64");key("SPLIT");type("426");awaitCandidate("你好");clickCandidate("你好");awaitText("你好");awaitNoComposition();
        pass("split has a numeric 1 hold picker that commits only on release and cancels cleanly; short press still separates pinyin");
        clear();
    }
    private void awaitFirst(String word){
        long until=SystemClock.uptimeMillis()+5000;
        while(SystemClock.uptimeMillis()<until){AccessibilityNodeInfo first=find("candidate_0");if(first!=null&&word.contentEquals(first.getText()))return;SystemClock.sleep(35);}
        throw new AssertionError("Expected first candidate "+word+"; first="+String.valueOf(awaitNode("candidate_0").getText())+" editor="+text());
    }
}
