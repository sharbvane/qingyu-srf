package com.qingyu.ime;

import android.os.SystemClock;
import android.text.InputType;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/** Actual IME UI checks; run only after the explicit model-download runner succeeds. */
final class LanguageV4Check {
    private static final String[][] LANGUAGES={{"en","英语"},{"ja","日语"},{"fr","法语"},{"de","德语"},{"ru","俄语"},{"es","西班牙语"}};
    static void run(ImeSmokeInstrumentation test)throws Exception {
        test.check(test instanceof ImeV2Instrumentation,"Language UI check requires existing candidate gesture helpers");
        ImeV2Instrumentation ime=(ImeV2Instrumentation)test;
        for(String language:new String[]{"de","ru","es"}) {
            test.clear();selectLanguage(test,language);test.type("wenhua");test.awaitCandidate("文化");
            String translated=modelDetail(ime);test.closePanel();test.keyboardReady();SystemClock.sleep(200);test.check(!String.valueOf(test.candidate("文化").getContentDescription()).contains("释义"),"Model output leaked into keyboard annotations");
            ime.upCandidate("文化");test.awaitNodeText("translation_detail","Google Translate · 端侧翻译");test.check(test.text().equals("wenhua"),"Model gesture committed before detail confirmation");test.buttonClick("Translate with Google");test.awaitText(translated);
            test.pass(languageName(language)+" model remains absent from keyboard and opens attributed details before matching translation commit");
        }

        test.clear();selectLanguage(test,"de");openManager(test);
        for(String[] language:LANGUAGES) {
            String name=language[1];awaitModelState(test,name,"模型已就绪");
            awaitSize(test,name,false);
        }
        test.pass("All six model rows report ready status and positive measured installed sizes");
        long oldRevision=test.getTargetContext().getSharedPreferences("qingyu",0).getLong("models_revision",0);
        clickEnabled(test,"删除 · 德语");awaitModelState(test,"德语","未下载");
        test.check(test.getTargetContext().getSharedPreferences("qingyu",0).getLong("models_revision",0)>oldRevision,"German deletion did not invalidate IME model state");
        awaitSize(test,"德语",true);
        test.screenshot("v4-models-deleted.png");leaveManager(test);
        test.type("wenhua");test.awaitCandidate("文化");SystemClock.sleep(450);
        test.check(!String.valueOf(test.candidate("文化").getContentDescription()).contains("释义"),"Deleted German model retained cached candidate gloss");
        ime.holdCandidate("文化");test.awaitNodeText("translation_detail","模型未下载");test.closePanel();test.keyboardReady();test.clickCandidate("文化");test.awaitText("文化");
        test.pass("German model deletion releases storage and cached gloss; normal Chinese input remains usable");

        test.clear();openManager(test);clickEnabled(test,"下载 · 德语");awaitModelState(test,"德语","模型已就绪");
        awaitSize(test,"德语",false);test.screenshot("v4-models-restored.png");leaveManager(test);
        test.type("wenhua");test.awaitCandidate("文化");String restored=modelDetail(ime);test.closePanel();test.keyboardReady();ime.upCandidate("文化");test.awaitNodeText("translation_detail","Google Translate · 端侧翻译");test.buttonClick("Translate with Google");test.awaitText(restored);
        test.pass("Explicit German model re-download restores detail translation and explicit commit");
        test.clear();selectLanguage(test,"en");test.newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);test.keyboardReady();
    }

    private static void selectLanguage(ImeSmokeInstrumentation test,String language) {
        String current=test.getTargetContext().getSharedPreferences("qingyu",0).getString("gloss_language","en");
        test.nodeClick("toolbar_more");test.buttonClick("释义显示语言 · "+languageName(current));test.buttonClick(languageName(language));test.closePanel();test.keyboardReady();
        test.check(language.equals(test.getTargetContext().getSharedPreferences("qingyu",0).getString("gloss_language","en")),"Language selection not saved: "+language);
    }
    private static String modelDetail(ImeV2Instrumentation test) {
        test.holdCandidate("文化");test.awaitNodeText("translation_detail","Google Translate · 端侧翻译");
        String value=test.awaitNode("translation_detail").getText().toString().split("\n",2)[0].trim();
        test.check(!value.isEmpty()&&!value.equals("文化"),"Optional language did not produce a translated word: "+value);test.check(test.text().equals("wenhua"),"Viewing model details unexpectedly committed the candidate");return value;
    }
    private static void openManager(ImeSmokeInstrumentation test) {
        String current=test.getTargetContext().getSharedPreferences("qingyu",0).getString("gloss_language","en");test.nodeClick("toolbar_more");test.buttonClick("释义显示语言 · "+languageName(current));test.buttonClick("翻译模型管理");awaitModelState(test,"英语","模型已就绪");
    }
    private static void leaveManager(ImeSmokeInstrumentation test)throws Exception {
        test.shell("input keyevent 4");SystemClock.sleep(180);test.newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);test.keyboardReady();
    }
    private static AccessibilityNodeInfo descriptionIn(AccessibilityNodeInfo node,String value) {
        if(node==null)return null;if(value.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=descriptionIn(node.getChild(i),value);if(found!=null)return found;}return null;
    }
    private static AccessibilityNodeInfo description(ImeSmokeInstrumentation test,String value) {
        for(AccessibilityWindowInfo window:test.automation.getWindows()){AccessibilityNodeInfo found=descriptionIn(window.getRoot(),value);if(found!=null)return found;}return null;
    }
    private static void awaitModelState(ImeSmokeInstrumentation test,String language,String expected) {
        long until=SystemClock.uptimeMillis()+90000;String actual="";
        while(SystemClock.uptimeMillis()<until){AccessibilityNodeInfo node=description(test,language+"模型状态");if(node!=null){actual=String.valueOf(node.getText());if(actual.contains(expected))return;if(actual.contains("模型不可用"))throw new AssertionError("Model operation failed: "+language+" "+actual);}SystemClock.sleep(60);}throw new AssertionError("Model state timeout: "+language+" expected "+expected+", actual "+actual);
    }
    private static String awaitSize(ImeSmokeInstrumentation test,String language,boolean missing) {
        long until=SystemClock.uptimeMillis()+5000;String actual="";
        while(SystemClock.uptimeMillis()<until){AccessibilityNodeInfo node=description(test,language+"模型占用");if(node!=null){actual=String.valueOf(node.getText());if(actual.matches(".*：[0-9]+(?:\\.[0-9]+)? MiB")){double size=Double.parseDouble(actual.substring(actual.indexOf('：')+1,actual.indexOf(" MiB")).trim());if(missing?size==0:size>0)return actual;}}SystemClock.sleep(40);}throw new AssertionError("Installed model size missing or unexpected: "+language+" "+actual);
    }
    private static void clickEnabled(ImeSmokeInstrumentation test,String title) {
        long until=SystemClock.uptimeMillis()+5000;AccessibilityNodeInfo node;
        while(((node=description(test,title))==null||!node.isEnabled())&&SystemClock.uptimeMillis()<until)SystemClock.sleep(40);
        test.check(node!=null&&node.isEnabled(),"Model action not enabled: "+title);test.buttonClick(title);
    }
    private static String languageName(String code){for(String[] language:LANGUAGES)if(language[0].equals(code))return language[1];throw new AssertionError("Unexpected selected language: "+code);}
}
