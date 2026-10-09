package com.qingyu.ime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.SystemClock;
import android.text.InputType;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;

/** Exercises the real editing panel against the active Android editor. */
public final class ImeV7Instrumentation extends ImeV67Instrumentation {
    private boolean modelsAvailable,editorOnly;
    @Override public void onCreate(android.os.Bundle args){modelsAvailable=args!=null&&"true".equals(args.getString("models_available"));editorOnly=args!=null&&"true".equals(args.getString("editor_only"));super.onCreate(args);}
    @Override protected String successMarker(){return "ALL_V7_IME_CHECKS_PASS";}
    @Override protected void runChecks()throws Exception{
        if(debugTarget()){
            mainCheck(()->EditorHistoryCheck.run(editor));pass("session undo deltas preserve composition, replacement, emoji, selection, external changes and bounded history");
            EditPanelCheck.run(this,activity);pass("editing panel touch repetition, cancellation, compact geometry, status and password protection");
            newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);keyboardReady();
        }
        if(!editorOnly)super.runChecks();selectMode("中文 · 全键拼音");clear();
        type("kaifa");awaitCandidate("开发");clickCandidate("开发");awaitText("开发");
        type("xiangmu");awaitCandidate("项目");clickCandidate("项目");awaitText("开发项目");
        openEdit();nodeClick("edit_undo");awaitText("开发");nodeClick("edit_undo");awaitText("");
        pass("two actual candidate commits undo one at a time without restoring raw pinyin");

        closePanel();selectMode("English");clear();type("abc");key("SPACE");awaitText("abc ");
        openEdit();nodeClick("edit_delete");awaitText("abc");nodeClick("edit_undo");awaitText("abc ");nodeClick("edit_undo");awaitText("");
        pass("panel backspace shares keyboard deletion and ordered undo restores deletion then input");

        closePanel();setText("原文");openEdit();nodeClick("edit_select_all");nodeClick("edit_cut");awaitText("");nodeClick("edit_undo");awaitText("原文");
        runOnMainSync(()->((ClipboardManager)activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("test","粘贴内容")));
        nodeClick("edit_select_all");nodeClick("edit_paste");awaitText("粘贴内容");nodeClick("edit_undo");awaitText("原文");
        pass("actual cut and selected paste replace text and undo preserves original selection content");

        closePanel();setText("abcdefghijk😊");openEdit();panelHoldDelete(1800);awaitText("");
        for(int i=0;i<12;i++)nodeClick("edit_undo");awaitText("abcdefghijk😊");
        pass("held panel backspace repeats through Unicode code points and each deletion remains undoable");

        closePanel();selectMode("中文 · 全键拼音");clear();type("kaifa");awaitCandidate("开发");clickCandidate("开发");awaitText("开发");openEdit();
        runOnMainSync(()->editor.append("外部修改"));SystemClock.sleep(200);
        AccessibilityNodeInfo undo=find("edit_undo");check(undo!=null&&!undo.isEnabled(),"External modification retained unsafe undo");awaitText("开发外部修改");
        pass("external application text change invalidates IME undo without deleting external text");

        closePanel();setText("开发，项目 123😊\nEnglish");openEdit();nodeClick("edit_translate");awaitEditorTranslation("develop，project 123😊\nEnglish");screenshot("v7-editor.png");nodeClick("edit_undo");awaitText("开发，项目 123😊\nEnglish");
        pass("unselected editor translates Chinese only, preserves punctuation numbers emoji newline foreign text, and is undoable");
        closePanel();setText("前缀开发后缀");select(2,4);openEdit();nodeClick("edit_translate");awaitEditorTranslation("前缀develop后缀");nodeClick("edit_undo");awaitText("前缀开发后缀");
        pass("selected Chinese replaces only the anchored range rather than appending translation");
        closePanel();setText("AA开发 English 42😊\n项目ZZ");select(2,text().length()-2);openEdit();nodeClick("edit_translate");awaitEditorTranslation("AAdevelop English 42😊\nprojectZZ");
        pass("mixed selected text translates only Chinese while surrounding editor content and foreign tokens remain exact");
        if(modelsAvailable){
            String original="前缀This keyboard helps me write messages clearly every day. 123😊\n后缀";
            closePanel();setText(original);select(2,original.length()-2);openEdit();nodeClick("edit_translate");
            long until=SystemClock.uptimeMillis()+45000;while(text().equals(original)&&SystemClock.uptimeMillis()<until)SystemClock.sleep(50);
            String translated=text();check(translated.startsWith("前缀")&&translated.endsWith(". 123😊\n后缀")&&!translated.equals(original)&&translated.substring(2,translated.length()-2).matches("(?s).*\\p{IsHan}.*"),"Foreign selection was not replaced safely: "+translated);
            check(translated.chars().filter(c->c==' ').count()==original.chars().filter(c->c==' ').count(),"Foreign translation changed original spaces");
            nodeClick("edit_undo");awaitText(original);pass("installed English model detects a foreign selection, replaces only that range, preserves original separators and supports undo");
        }
        closePanel();setText("123😊\nEnglish https://example.org/中文");openEdit();nodeClick("edit_translate");awaitStatus("没有可翻译");awaitText("123😊\nEnglish https://example.org/中文");
        pass("unselected foreign or protected technical content is never translated or modified");

        closePanel();setText("你好，项目");select(0,2);openEdit();nodeClick("edit_translate");runOnMainSync(()->editor.append("外部"));
        SystemClock.sleep(700);check(text().endsWith("外部"),"Translation overwrote an external modification");
        pass("editor changes during translation retain the application's later content");

        closePanel();newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);keyboardReady();type("abc");openEdit();
        check(!awaitNode("edit_undo").isEnabled()&&!awaitNode("edit_translate").isEnabled()&&!awaitNode("edit_paste").isEnabled(),"Password exposes editing history or translation");nodeClick("edit_delete");awaitText("ab");
        pass("password editor disables translation undo and clipboard while retaining backspace");
        closePanel();newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);keyboardReady();
        browserChecks();newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);keyboardReady();
        if(debugTarget())learningChecks();
    }
    private void browserChecks()throws Exception{
        selectMode("English");launchBrowser("close");launchBrowser("typing");awaitWeb("");focusBrowser();
        type("hello");key("SPACE");awaitWeb("hello ");openEdit();nodeClick("edit_delete");awaitWeb("hello");nodeClick("edit_undo");awaitWeb("hello ");nodeClick("edit_undo");awaitWeb("");
        launchBrowser("close");SystemClock.sleep(200);launchBrowser("translate");awaitWeb("开发，123😊\nEnglish");focusBrowser();openEdit();nodeClick("edit_translate");awaitWeb("develop，123😊\nEnglish");nodeClick("edit_undo");awaitWeb("开发，123😊\nEnglish");
        launchBrowser("external");awaitWeb("开发，123😊\nEnglish外部");if(awaitNode("edit_undo").isEnabled())nodeClick("edit_undo");awaitWeb("开发，123😊\nEnglish外部");
        pass("separate-process Chromium textarea supports panel deletion, ordered undo, protected translation and external-change safety");
        launchBrowser("close");SystemClock.sleep(200);
    }
    private void launchBrowser(String fixture){getContext().startActivity(new android.content.Intent(getContext(),BrowserEditorActivity.class).putExtra("fixture",fixture).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));SystemClock.sleep(200);}
    private String webText(){for(android.view.accessibility.AccessibilityWindowInfo window:automation.getWindows()){AccessibilityNodeInfo root=window.getRoot();if(root!=null)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByViewId("android:id/text1"))if(node.getText()!=null&&node.getText().toString().startsWith("browser: "))return node.getText().toString().substring(9);}return null;}
    private void awaitWeb(String value){long until=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<until){if(value.equals(webText()))return;SystemClock.sleep(50);}throw new AssertionError("Browser expected "+value+" actual "+webText());}
    private void focusBrowser(){SystemClock.sleep(300);long now=SystemClock.uptimeMillis();touch(220,400,true,now);touch(220,400,false,now+40);keyboardReady();}
    private void openEdit(){nodeClick("toolbar_edit");awaitNode("edit_delete");SystemClock.sleep(160);}
    private void select(int start,int end){runOnMainSync(()->editor.setSelection(start,end));waitForIdleSync();SystemClock.sleep(120);}
    private void awaitEditorTranslation(String expected){long until=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<until){if(expected.equals(text()))return;SystemClock.sleep(40);}throw new AssertionError("Editor replacement expected "+expected+" actual "+text()+" status "+String.valueOf(awaitNode("edit_status").getText()));}
    private void awaitStatus(String text){long until=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<until){if(String.valueOf(awaitNode("edit_status").getText()).contains(text))return;SystemClock.sleep(40);}throw new AssertionError("Missing editor status "+text);}
    private void panelHoldDelete(long duration){android.graphics.Rect rect=new android.graphics.Rect();awaitNode("edit_delete").getBoundsInScreen(rect);long start=SystemClock.uptimeMillis();touch(rect.centerX(),rect.centerY(),true,start);SystemClock.sleep(duration);touch(rect.centerX(),rect.centerY(),false,SystemClock.uptimeMillis());SystemClock.sleep(250);}
    private void learningChecks()throws Exception{
        java.io.File file=new java.io.File(getTargetContext().getFilesDir(),"pinyin-learning-v1.tsv");int previousCount=0;
        if(file.isFile())for(String line:java.nio.file.Files.readAllLines(file.toPath(),java.nio.charset.StandardCharsets.UTF_8)){String[] fields=line.split("\t",-1);if(fields.length==4&&fields[0].equals("kd")&&fields[1].equals("快点"))previousCount=Integer.parseInt(fields[3]);}
        check(previousCount<988,"Personalization fixture lacks room for twelve fresh selections");
        getTargetContext().getSharedPreferences("qingyu",0).edit().putBoolean("learning",true).apply();
        runOnMainSync(()->{editor.setImeOptions(0);((InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).restartInput(editor);});SystemClock.sleep(200);keyboardReady();selectMode("中文 · 九键拼音");
        for(int i=0;i<12;i++){runOnMainSync(()->{editor.setText("");editor.setSelection(0);});SystemClock.sleep(120);type("53");selectReading("k");selectReading("d");awaitCandidate(null);findExpandedCandidate("快点");clickCandidate("快点");awaitText("快点");}
        shell("input keyevent KEYCODE_HOME");int actualCount=0;long savedUntil=SystemClock.uptimeMillis()+5000;
        while(SystemClock.uptimeMillis()<savedUntil){actualCount=0;if(file.isFile())for(String line:java.nio.file.Files.readAllLines(file.toPath(),java.nio.charset.StandardCharsets.UTF_8)){String[] fields=line.split("\t",-1);if(fields.length==4&&fields[0].equals("kd")&&fields[1].equals("快点"))actualCount=Integer.parseInt(fields[3]);}if(actualCount>=previousCount+12)break;SystemClock.sleep(50);}
        check(file.isFile(),"Nine-key selection never persisted personalization");
        check(actualCount>=previousCount+12,"Twelve actual nine-key choices did not reach durable learning: "+previousCount+"→"+actualCount);
        // SettingsActivity rebuilds its content on a new intent; attach a fresh test editor after reopening.
        activity=startActivitySync(new android.content.Intent(getTargetContext(),SettingsActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);keyboardReady();
        runOnMainSync(()->{editor.setText("");editor.setSelection(0);});SystemClock.sleep(100);type("53");selectReading("k");selectReading("d");awaitCandidate("快点");
        String id=candidate("快点").getViewIdResourceName();check(Integer.parseInt(id.substring(id.lastIndexOf('_')+1))<3,"Repeated actual nine-key choices do not improve candidate ranking");
        pass("twelve actual nine-key choices learn typed initials and persist when the keyboard hides: "+previousCount+"→"+actualCount+"; reopened candidate ranks in the leading three");
        clear();shell("input keyevent KEYCODE_HOME");SystemClock.sleep(700);
        java.io.File chineseFile=new java.io.File(getTargetContext().getFilesDir(),"chinese-learning-v2.tsv");String[] countKeys={"人民","中国\t人民"};int[] before=new int[2];
        if(chineseFile.isFile())for(String line:java.nio.file.Files.readAllLines(chineseFile.toPath(),java.nio.charset.StandardCharsets.UTF_8)){int split=line.lastIndexOf('\t');if(split<0)continue;for(int i=0;i<countKeys.length;i++)if(line.substring(0,split).equals(countKeys[i]))before[i]=Integer.parseInt(line.substring(split+1));}
        check(before[0]<999&&before[1]<999,"Prediction count fixture reached its persistence cap");
        activity=startActivitySync(new android.content.Intent(getTargetContext(),SettingsActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);keyboardReady();
        runOnMainSync(()->{editor.setImeOptions(0);((InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).restartInput(editor);});SystemClock.sleep(200);keyboardReady();selectMode("中文 · 全键拼音");setText("中国");awaitCandidate("人民");clickCandidate("人民");awaitText("中国人民");shell("input keyevent KEYCODE_HOME");
        int[] after=new int[2];long until=SystemClock.uptimeMillis()+5000;
        while(SystemClock.uptimeMillis()<until){java.util.Arrays.fill(after,0);if(chineseFile.isFile())for(String line:java.nio.file.Files.readAllLines(chineseFile.toPath(),java.nio.charset.StandardCharsets.UTF_8)){int split=line.lastIndexOf('\t');if(split<0)continue;for(int i=0;i<countKeys.length;i++)if(line.substring(0,split).equals(countKeys[i]))after[i]=Integer.parseInt(line.substring(split+1));}if(after[0]>before[0]&&after[1]>before[1])break;SystemClock.sleep(50);}
        check(after[0]==before[0]+1&&after[1]==before[1]+1,"One Chinese prediction commit learned more than once: global "+before[0]+"→"+after[0]+", context "+before[1]+"→"+after[1]);
        pass("one actual Chinese prediction selection increments global and contextual persistence exactly once");
        activity=startActivitySync(new android.content.Intent(getTargetContext(),SettingsActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);keyboardReady();
        getTargetContext().getSharedPreferences("qingyu",0).edit().putBoolean("learning",false).apply();clear();
    }
}
