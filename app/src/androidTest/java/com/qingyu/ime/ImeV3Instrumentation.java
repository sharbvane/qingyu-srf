package com.qingyu.ime;

import android.app.Activity;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;

/** v0.3 regressions use the actual IME window and editor; helpers exercise native touch paths. */
public class ImeV3Instrumentation extends ImeV2Instrumentation {
    private boolean modelsOnly;
    @Override public void onCreate(android.os.Bundle arguments){modelsOnly=arguments!=null&&"true".equals(arguments.getString("models_only"));super.onCreate(arguments);}
    @Override protected String successMarker(){return modelsOnly?"ALL_MODEL_MANAGEMENT_CHECKS_PASS":"ALL_V3_IME_CHECKS_PASS";}
    @Override protected void runChecks() throws Exception {
        if(modelsOnly){checkModelManagement();return;}
        if(debugTarget()){
            KeyboardTouchCheck.run(this);pass("letter hold-slide, case, cancellation, cursor and repeat touch state check");
            mainCheck(()->CandidateLayoutCheck.run(getTargetContext()));pass("fixed candidate/grid bounds, prediction X and readable POS colors");
            mainCheck(()->PanelLayoutCheck.run(getTargetContext()));pass("all panel owners, continuous height and constrained window geometry");
        }
        type("kaifa");awaitCandidate("开发");
        Rect idleBody=bounds("panel_host"),candidateBounds=new Rect();candidate("开发").getBoundsInScreen(candidateBounds);
        assertReservedArea(candidateBounds);
        Rect header=new Rect();candidate("开发").getParent().getBoundsInScreen(header);nodeClick("candidate_expand");SystemClock.sleep(200);
        check(find("key_SPACE")==null&&find("candidate_8")!=null,"Expanded grid missing or keyboard touch nodes still exposed");
        Rect expandedHeader=new Rect();candidate("开发").getParent().getBoundsInScreen(expandedHeader);check(idleBody.equals(bounds("panel_host"))&&header.equals(expandedHeader),"Expansion changed reserved keyboard geometry");
        Rect grid=new Rect();awaitNode("candidate_8").getBoundsInScreen(grid);check(idleBody.contains(grid),"Expanded candidate escapes keyboard body");
        screenshot("v3-expanded.png");nodeClick("candidate_expand");keyboardReady();check(idleBody.equals(bounds("panel_host")),"Collapse changed keyboard height");
        pass("candidate expansion fills fixed keyboard body, reserves app space and collapses safely");
        for(int i=0;i<12;i++){nodeClick("candidate_expand");SystemClock.sleep(35);nodeClick("candidate_expand");SystemClock.sleep(35);}
        keyboardReady();clickCandidate("开发");awaitText("开发");
        clear();type("shi");awaitCandidate(null);nodeClick("candidate_expand");SystemClock.sleep(160);
        runOnMainSync(()->{editor.setText("");editor.setSelection(0);});keyboardReady();check(find("candidate_0")==null,"Empty preedit did not clear expanded candidates");
        type("xiangmu");awaitCandidate("项目");clickCandidate("项目");awaitText("项目");pass("rapid expand/collapse and cleared composition never lose the keyboard");

        clear();key("SHIFT");key("a");awaitText("A");check(find("candidate_expand")==null,"Chinese temporary uppercase composed pinyin");
        type("kaifa");awaitCandidate("开发");clickCandidate("开发");awaitText("A开发");
        clear();key("SHIFT");key("SHIFT");type("az");awaitText("AZ");check(find("candidate_expand")==null,"Chinese caps lock composed pinyin");key("SHIFT");type("nihao");awaitCandidate("你好");clickCandidate("你好");awaitText("AZ你好");
        clear();type("kaifa");awaitCandidate("开发");key("SHIFT");key("a");awaitText("开发A");
        pass("Chinese Shift once outputs one literal uppercase; double Shift locks; lowercase resumes pinyin");
        clear();holdSlide("s",-1,false);awaitText("S");holdSlide("s",1,false);awaitText("Ss");holdSlide("q",0,false);awaitText("Ssq");holdSlide("q",1,false);awaitText("Ssq1");holdSlide("q",-1,false);awaitText("Ssq1Q");holdSlide("s",-1,true);awaitText("Ssq1Q");
        screenshot("v3-letters.png");pass("real letter hold-slide commits upper/lower/digit once and cancellation commits nothing");

        clear();for(String nav:new String[]{"toolbar_more","toolbar_edit","toolbar_emoji","toolbar_mode"}){
            Rect original=bounds("panel_host");nodeClick(nav);SystemClock.sleep(170);check(find("key_SPACE")==null,"Panel did not open "+nav);check(awaitNode(nav).isSelected(),"Panel owner not selected "+nav);check(findButton("返回键盘")==null,"Legacy panel return button remains");check(original.equals(bounds("panel_host")),"Panel changed body height "+nav);nodeClick(nav);keyboardReady();check(original.equals(bounds("panel_host")),"Same icon failed to close "+nav);
        }
        nodeClick("toolbar_more");buttonClick("键盘高度");check(awaitNode("toolbar_more").isSelected(),"Height lost More ownership");nodeClick("toolbar_more");keyboardReady();
        nodeClick("toolbar_edit");buttonClick("剪贴板");nodeClick("toolbar_edit");keyboardReady();
        type("kaifa");awaitCandidate("开发");holdCandidate("开发");nodeClick("toolbar_more");keyboardReady();clickCandidate("开发");awaitText("开发");
        pass("all navigation icons toggle their panels; height, clipboard and detail retain owner without return buttons");

        clear();selectMode("English");type("hel");awaitCandidate("hello");clickCandidate("hello");awaitText("hello ");
        for(int round=0;round<3;round++){awaitCandidate(null);check(find("candidate_clear")!=null,"Prediction round lacks X "+round);AccessibilityNodeInfo n=awaitNode("candidate_0");String word=n.getText().toString();String before=text();nodeClick("candidate_0");awaitText(before+word+" ");}
        SystemClock.sleep(350);check(find("candidate_0")==null&&find("candidate_clear")==null,"Prediction continued after third pick");
        type("hel");awaitCandidate("hello");clickCandidate("hello");awaitCandidate(null);nodeClick("candidate_clear");String committed=text();SystemClock.sleep(350);check(find("candidate_0")==null&&committed.equals(text()),"X failed to clear without changing committed text");
        runOnMainSync(()->editor.setSelection(Math.max(0,editor.length()-1)));SystemClock.sleep(250);check(find("candidate_0")==null,"Selection callback resurrected dismissed predictions");runOnMainSync(()->editor.setSelection(editor.length()));type("hel");awaitCandidate("hello");
        pass("English predictions stop after 3 picks; X cancels pending predictions until manual input");
        clear();key("SHIFT");type("hel");awaitCandidate("Hello");clickCandidate("Hello");awaitText("Hello ");clear();key("SHIFT");key("SHIFT");type("hel");awaitCandidate("HELLO");clickCandidate("HELLO");awaitText("HELLO ");key("SHIFT");
        pass("English temporary uppercase and caps lock preserve candidate casing");
        clear();type("hel");awaitCandidate("hello");clickCandidate("hello");awaitText("hello ");
        for(int round=0;round<3;round++){awaitCandidate("world");String before=text();upCandidate("world");awaitText(before+"世界");}
        SystemClock.sleep(350);check(find("candidate_0")==null&&find("candidate_clear")==null,"Translated prediction gesture bypassed the 3-round limit");pass("upward translated prediction picks obey the same 3-round limit");
        clear();selectMode("中文 · 全键拼音");type("zhongguo");awaitCandidate("中国");clickCandidate("中国");awaitCandidate("人");nodeClick("candidate_clear");SystemClock.sleep(250);check(find("candidate_0")==null&&text().equals("中国"),"Chinese prediction X failed");type("kaifa");awaitCandidate("开发");
        screenshot("v3-pinyin.png");pass("Chinese prediction X clears immediately and manual pinyin reactivates candidates");
        clear();
        super.runChecks();
        checkModelManagement();
    }
    private Rect bounds(String id){Rect bounds=new Rect();awaitNode(id).getBoundsInScreen(bounds);return bounds;}
    private void assertReservedArea(Rect candidate){int[] imeTop={-1};runOnMainSync(()->{View decor=activity.getWindow().getDecorView();WindowInsets insets=decor.getRootWindowInsets();int[] location=new int[2];decor.getLocationOnScreen(location);imeTop[0]=location[1]+decor.getHeight()-insets.getInsets(WindowInsets.Type.ime()).bottom;});check(imeTop[0]>=0&&candidate.top>=imeTop[0],"Candidate overlaps unreserved app content: "+candidate+" IME top="+imeTop[0]);}
    private void checkModelManagement()throws Exception{
        clear();setLanguage("日语");type("wenhua");awaitCandidate("文化");holdCandidate("文化");awaitNodeText("translation_detail","Google Translate · 端侧翻译");closePanel();keyboardReady();clear();openManager();
        for(String language:new String[]{"英语","日语","法语"}){check(description(language+"模型状态")!=null,"Missing language model state "+language);check(description(language+"模型占用")!=null,"Missing installed file size "+language);}
        awaitModelState("日语","模型已就绪");long oldRevision=getTargetContext().getSharedPreferences("qingyu",0).getLong("models_revision",0);clickEnabled("删除 · 日语");awaitModelState("日语","未下载");check(getTargetContext().getSharedPreferences("qingyu",0).getLong("models_revision",0)>oldRevision,"SDK deletion did not publish model revision");screenshot("v3-models.png");leaveManager();
        type("wenhua");awaitCandidate("文化");SystemClock.sleep(450);check(!String.valueOf(candidate("文化").getContentDescription()).contains("释义"),"Deleted Japanese model retained cached candidate gloss");holdCandidate("文化");awaitNodeText("translation_detail","模型未下载");closePanel();keyboardReady();clickCandidate("文化");awaitText("文化");
        pass("model manager shows 3 languages and actual sizes; SDK deletion clears cached Japanese gloss without blocking Chinese");
        clear();openManager();clickEnabled("下载 · 日语");awaitModelState("日语","模型已就绪");leaveManager();type("wenhua");awaitCandidate("文化");holdCandidate("文化");awaitNodeText("translation_detail","Google Translate · 端侧翻译");closePanel();keyboardReady();clickCandidate("文化");awaitText("文化");clear();setLanguage("英语");
        pass("user-initiated model download restores Japanese candidate/detail translation after deletion");
        setLanguage("法语");type("womenmingtianqubeijing");awaitCandidate("我们明天去北京");holdCandidate("我们明天去北京");awaitNodeText("translation_detail","Google Translate · 端侧翻译");String sentence=awaitNode("translation_detail").getText().toString().split("\n",2)[0];check(!sentence.isEmpty()&&!sentence.equals("我们明天去北京"),"French sentence translation missing");closePanel();keyboardReady();upCandidate("我们明天去北京");awaitNodeText("translation_detail","Google Translate · 端侧翻译");buttonClick("Translate with Google");awaitText(sentence);clear();setLanguage("英语");pass("complete seven-syllable French model translation remains in details and commits on confirmation");
    }
    private void openManager(){nodeClick("toolbar_more");buttonClick("释义显示语言 · 日语");buttonClick("翻译模型管理");long until=SystemClock.uptimeMillis()+5000;while(description("日语模型状态")==null&&SystemClock.uptimeMillis()<until)SystemClock.sleep(30);check(description("日语模型状态")!=null,"Model manager intent did not open settings page");}
    private void leaveManager()throws Exception{shell("input keyevent 4");SystemClock.sleep(180);newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);keyboardReady();}
    private AccessibilityNodeInfo descriptionIn(AccessibilityNodeInfo node,String value){if(node==null)return null;if(value.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo match=descriptionIn(node.getChild(i),value);if(match!=null)return match;}return null;}
    private AccessibilityNodeInfo description(String value){for(android.view.accessibility.AccessibilityWindowInfo window:automation.getWindows()){AccessibilityNodeInfo match=descriptionIn(window.getRoot(),value);if(match!=null)return match;}return null;}
    private void awaitModelState(String language,String expected){long until=SystemClock.uptimeMillis()+90000;String actual="";while(SystemClock.uptimeMillis()<until){AccessibilityNodeInfo node=description(language+"模型状态");if(node!=null){actual=String.valueOf(node.getText());if(actual.contains(expected))return;if(actual.contains("模型不可用"))throw new AssertionError("Model operation failed "+language+": "+actual);}SystemClock.sleep(60);}throw new AssertionError("Model state timeout "+language+" expected "+expected+", actual "+actual);}
    private void clickEnabled(String title){long until=SystemClock.uptimeMillis()+5000;AccessibilityNodeInfo node;while(((node=description(title))==null||!node.isEnabled())&&SystemClock.uptimeMillis()<until)SystemClock.sleep(40);check(node!=null&&node.isEnabled(),"Model action unavailable: "+title);check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Model action failed "+title);SystemClock.sleep(130);}
}
