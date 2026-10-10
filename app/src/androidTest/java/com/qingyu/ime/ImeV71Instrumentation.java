package com.qingyu.ime;

import android.content.pm.ActivityInfo;
import android.os.SystemClock;
import android.view.inputmethod.InputMethodManager;

/** Candidate continuity, editor direction and repeated state-change regression on the real IME. */
public final class ImeV71Instrumentation extends ImeV7Instrumentation {
    @Override protected String successMarker(){return "ALL_V71_IME_CHECKS_PASS";}
    @Override protected void runChecks()throws Exception {
        if(debugTarget()){
            CandidateStabilityCheck.run(this,activity);
            pass("candidate snapshots, annotations, taps, long presses and fling survive pending preedit updates");
        }
        super.runChecks();closeAnyPanel();selectMode("中文 · 全键拼音");
        setText("abc\ndef\nghi");runOnMainSync(()->editor.setSelection(5));
        nodeClick("toolbar_edit");SystemClock.sleep(160);nodeClick("edit_up");awaitSelection(1,1);nodeClick("edit_down");awaitSelection(5,5);
        nodeClick("edit_home");awaitSelection(4,4);nodeClick("edit_end");awaitSelection(7,7);
        nodeClick("edit_select");nodeClick("edit_up");awaitSelection(3,7);nodeClick("edit_down");awaitSelection(7,7);nodeClick("edit_left");awaitSelection(6,7);nodeClick("edit_select");
        pass("direction dial moves by row and line boundary, and extends or contracts the anchored selection");
        closePanel();
        for(int round=0;round<4;round++){
            clear();type("xiangmu");awaitCandidate("项目");
            int orientation=round%2==0?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            runOnMainSync(()->activity.setRequestedOrientation(orientation));SystemClock.sleep(550);keyboardReady();awaitCandidate("项目");
            runOnMainSync(()->((InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).restartInput(editor));SystemClock.sleep(180);keyboardReady();awaitCandidate("项目");
            clear();toggleGloss();type("xiangmu");awaitCandidate("项目");clickCandidate("项目");awaitText("项目");
        }
        runOnMainSync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));SystemClock.sleep(550);keyboardReady();
        pass("four orientation and restart-input cycles followed by gloss toggles retain the latest full-pinyin candidates");
        for(String mode:new String[]{"中文 · 全键拼音","中文 · 九键拼音","English","中文 · 全键拼音"}){
            clear();selectMode(mode);String typed=mode.equals("English")?"proje":mode.contains("九键")?"64426":"nihao";
            type(typed.substring(0,2));awaitCandidate(null);
            for(int at=2;at<typed.length();at++){key(typed.substring(at,at+1));check(find("candidate_0")!=null,"Pending query cleared candidates in "+mode);}
            awaitCandidate(null);key("⌫");check(find("candidate_0")!=null,"Pending deletion cleared candidates in "+mode);key(typed.substring(typed.length()-1));awaitCandidate(null);
            if(!mode.equals("English")){awaitCandidate("你好");clickCandidate("你好");awaitText("你好");}else{awaitCandidate("project");clickCandidate("project");awaitText("project ");}
        }
        pass("full-pinyin, nine-key and English typing/deletion retain candidate continuity through mode changes");
        clear();type("shi");awaitCandidate(null);nodeClick("candidate_expand");SystemClock.sleep(200);check(find("key_a")==null,"Expanded panel did not replace keys");
        SystemClock.sleep(700);check(walk(awaitNode("panel_host"),"candidate_0")!=null,"Annotation completion closed or emptied expanded candidates");nodeClick("candidate_expand");keyboardReady();
        pass("asynchronous annotations leave expanded candidates and their panel intact");
        screenshot("v71-pinyin.png");clear();setText("轻语输入法\n输入自然，编辑直观");nodeClick("toolbar_edit");SystemClock.sleep(200);screenshot("v71-editor.png");closePanel();
    }
    private void closeAnyPanel(){if(find("edit_delete")!=null||find("toolbar_edit")!=null&&find("toolbar_edit").isSelected())closePanel();}
    private void awaitSelection(int start,int end){long until=SystemClock.uptimeMillis()+4000;int[] actual={-1,-1};while(SystemClock.uptimeMillis()<until){runOnMainSync(()->{actual[0]=editor.getSelectionStart();actual[1]=editor.getSelectionEnd();});if(Math.min(actual[0],actual[1])==start&&Math.max(actual[0],actual[1])==end)return;SystemClock.sleep(30);}throw new AssertionError("Cursor expected "+start+".."+end+", actual "+actual[0]+".."+actual[1]);}
}
