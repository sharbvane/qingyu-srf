package com.qingyu.ime;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Rect;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;

/** v0.2 real editor, real IME window, touch gestures and system clipboard. */
public class ImeV2Instrumentation extends ImeSmokeInstrumentation {
    private boolean modelsAvailable;
    @Override public void onCreate(android.os.Bundle arguments){modelsAvailable=arguments!=null&&"true".equals(arguments.getString("models_available"));super.onCreate(arguments);}
    @Override protected String successMarker(){return "ALL_V2_IME_CHECKS_PASS";}
    @Override protected void runChecks() throws Exception{
        if(debugTarget()){mainCheck(()->AttributionCheck.run(getTargetContext()));pass("model attribution reserves stable geometry and preserves local source");}
        for(String id:new String[]{"toolbar_more","toolbar_edit","toolbar_emoji","toolbar_mode","toolbar_hide"})check(find(id)!=null,"Missing navigation "+id);
        check(find("punctuation_0")==null&&find("annotation_toggle")==null,"Legacy punctuation/control row is still visible");
        check(find("candidate_expand")==null,"Expand visible without composing candidates");
        pass("five icon navigation actions, no persistent punctuation or empty expand");

        type("kaifa");awaitCandidate("开发");awaitGloss("开发","develop");
        Rect expanded=new Rect();awaitNode("candidate_expand").getBoundsInScreen(expanded);
        Rect wordBounds=new Rect();candidate("开发").getBoundsInScreen(wordBounds);
        check(expanded.centerY()>=wordBounds.top&&expanded.centerY()<=wordBounds.bottom,"Expand is outside candidate row");
        screenshot("v2-pinyin.png");clickCandidate("开发");awaitText("开发");check(find("candidate_expand")==null,"Prediction exposes expand");
        pass("expand only within an active candidate row");

        clear();selectMode("中文 · 九键拼音");type("52432");awaitCandidate("开发");clickCandidate("开发");awaitText("开发");
        clear();hold("2",620);awaitText("2");screenshot("v2-nine.png");
        pass("nine-key 52432 selects 开发, long press commits digit without pinyin");
        selectMode("中文 · 全键拼音");clear();

        selectMode("English");type("hel");awaitCandidate("hello");awaitGloss("hello","你好");awaitText("hel");clickCandidate("hello");awaitText("hello ");
        awaitCandidate("world");awaitGloss("world","世界");check(find("candidate_expand")==null,"English next-word prediction expands");
        clickCandidate("world");awaitText("hello world ");
        pass("English completion and contextual next word both show Chinese gloss");
        clear();type("helo");awaitCandidate("hello");awaitText("helo");clickCandidate("hello");awaitText("hello ");
        pass("English correction is explicit and never replaces typed spelling implicitly");
        clear();type("zzqxv");key("SPACE");awaitText("zzqxv ");
        pass("unlisted English spelling survives a normal space commit");

        clear();selectMode("中文 · 全键拼音");type("zhongguo");awaitCandidate("中国");clickCandidate("中国");awaitText("中国");
        awaitCandidate("人");awaitGloss("人","person");check(find("candidate_expand")==null,"Chinese next-word prediction exposes expand");clickCandidate("人");awaitText("中国人");
        pass("Chinese contextual prediction displays a gloss and continues 中国 to 中国人");

        clear();type("kaifa");awaitCandidate("开发");holdCandidate("开发");
        awaitNodeText("translation_detail","develop");awaitNodeText("translation_detail","CC-CEDICT · 英文词典释义");check(text().equals("kaifa"),"Long press committed text");
        screenshot("v2-detail.png");closePanel();keyboardReady();upCandidate("开发");awaitText("develop");
        pass("candidate hold opens full definition, upward swipe inputs translated word");
        clear();type("xiangmu");awaitCandidate("项目");horizontalCandidate("项目");SystemClock.sleep(180);awaitText("xiangmu");clear();
        pass("horizontal candidate browsing never selects a word");

        if(!modelsAvailable){
        clear();setLanguage("日语");type("wenhua");awaitCandidate("文化");upCandidate("文化");
        awaitNode("translation_detail");long statusUntil=SystemClock.uptimeMillis()+5000;String status="";
        while(SystemClock.uptimeMillis()<statusUntil){status=String.valueOf(find("translation_detail").getText());if(status.contains("模型未下载")||status.contains("模型不可用"))break;SystemClock.sleep(40);}
        check(status.contains("模型未下载")||status.contains("模型不可用"),"Expected undownloaded model status, actual "+status);
        check(text().equals("wenhua"),"Unavailable translation changed composing text");closePanel();keyboardReady();clickCandidate("文化");awaitText("文化");
        pass("undownloaded or unavailable translation model leaves normal Chinese input usable");
        }else{
        clear();setLanguage("日语");type("wenhua");awaitCandidate("文化");holdCandidate("文化");
        awaitNodeText("translation_detail","Google Translate · 端侧翻译");
        check(awaitNode("translation_attribution").isVisibleToUser(),"Downloaded model detail attribution is not visible");
        check(findButton("Translate with Google")!=null,"Downloaded model detail is missing the attributed commit action");
        check(text().equals("wenhua"),"Viewing downloaded model translation changed composing text");
        closePanel();keyboardReady();upCandidate("文化");awaitText("文化");
        pass("downloaded Japanese model definition shows attribution and upward gesture commits its translation");
        }

        clear();setLanguage("英语");nodeClick("toolbar_more");buttonClick("键盘高度");
        AccessibilityNodeInfo slider=awaitNode("height_slider");android.os.Bundle progress=new android.os.Bundle();progress.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,419f);
        check(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),progress),"Continuous height slider action failed");SystemClock.sleep(180);
        float height=getTargetContext().getSharedPreferences("qingyu",0).getFloat("height",0);
        check(Math.abs(height-(.78f+.46f*.419f))<.002f,"Slider did not save continuous height: "+height);
        closePanel();keyboardReady();type("sheji");awaitCandidate("设计");clickCandidate("设计");awaitText("设计");
        pass("continuous 97.3 percent keyboard height persists and remains usable");
        nodeClick("toolbar_more");buttonClick("键盘风格");buttonClick("清简平面");closePanel();keyboardReady();
        check(getTargetContext().getSharedPreferences("qingyu",0).getString("style","").equals("flat"),"Flat style not saved");clear();type("nihao");clickCandidateAfter("你好");awaitText("你好");
        nodeClick("toolbar_more");buttonClick("键盘风格");buttonClick("柔和圆角");closePanel();keyboardReady();
        pass("both quiet keyboard styles switch without losing input");
        nodeClick("toolbar_more");buttonClick("打字震动 · 开");check(!getTargetContext().getSharedPreferences("qingyu",0).getBoolean("haptic",true),"Haptic toggle did not change preference");buttonClick("打字震动 · 关");closePanel();keyboardReady();
        pass("typing haptic preference toggles within More");

        clear();nodeClick("toolbar_emoji");buttonClick("😊");awaitText("😊");keyboardReady();key("⌫");awaitText("");
        pass("Emoji panel inserts one Unicode emoji and backspace removes it");

        setText("hello world");nodeClick("toolbar_edit");buttonClick("全选");awaitSelection(0,11);buttonClick("复制");awaitClip("hello world");
        buttonClick("剪切");awaitText("");buttonClick("粘贴");awaitText("hello world");closePanel();keyboardReady();
        pass("editor select all, copy, cut and paste use actual system InputConnection");
        setText("abc");nodeClick("toolbar_edit");buttonClick("选择");buttonClick("←");awaitSelection(2,3);buttonClick("复制");awaitClip("c");buttonClick("结束选择");closePanel();keyboardReady();
        pass("selection mode extends with the cursor and copies only the selection");

        nodeClick("toolbar_edit");buttonClick("剪贴板");buttonClick("清空历史");awaitHistoryCount(0);closePanel();keyboardReady();
        ClipboardManager manager=(ClipboardManager)getTargetContext().getSystemService(Activity.CLIPBOARD_SERVICE);
        for(int i=0;i<105;i++){String value=String.format(java.util.Locale.ROOT,"qingyu-qa-%03d",i);runOnMainSync(()->manager.setPrimaryClip(ClipData.newPlainText("UI test",value)));SystemClock.sleep(35);}
        JSONArray history=awaitHistoryCount(100);
        check(history.getString(0).equals("qingyu-qa-104")&&history.getString(99).equals("qingyu-qa-005"),"History did not retain the latest 100 clips");
        pass("actual system clipboard retains the most recent 100 distinct copied texts");
        ClipData sensitive=ClipData.newPlainText("Private","qingyu-sensitive-never-retain");PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);sensitive.getDescription().setExtras(extras);
        runOnMainSync(()->manager.setPrimaryClip(sensitive));SystemClock.sleep(450);check(!readHistory().toString().contains("qingyu-sensitive"),"Sensitive clip retained");
        newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);keyboardReady();
        runOnMainSync(()->manager.setPrimaryClip(ClipData.newPlainText("Private editor","qingyu-password-never-retain")));SystemClock.sleep(450);check(!readHistory().toString().contains("qingyu-password"),"Password-field clip retained");
        pass("sensitive system clips and password-field clips are excluded from history");
        newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);keyboardReady();
        nodeClick("toolbar_edit");buttonClick("剪贴板");screenshot("v2-clipboard.png");buttonClick("qingyu-qa-104");awaitText("qingyu-qa-104");keyboardReady();
        nodeClick("toolbar_edit");buttonClick("剪贴板");buttonClick("清空历史");awaitHistoryCount(0);closePanel();keyboardReady();
        nodeClick("toolbar_edit");buttonClick("剪贴板");check(findButton("qingyu-qa-104")==null,"Cleared history resurrected current clipboard");closePanel();keyboardReady();
        InputMethodManager imm=(InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);String fallback="";
        for(InputMethodInfo method:imm.getInputMethodList())if(!method.getPackageName().equals("com.qingyu.ime")){fallback=method.getId();break;}
        check(!fallback.isEmpty(),"No fallback IME for persistence test");shell("ime set "+fallback);SystemClock.sleep(650);shell("ime set com.qingyu.ime/.QingyuImeService");SystemClock.sleep(650);
        runOnMainSync(()->{imm.restartInput(editor);imm.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);});keyboardReady();
        nodeClick("toolbar_edit");buttonClick("剪贴板");check(findButton("qingyu-qa-104")==null&&readHistory().length()==0,"Cleared history restored after IME service restart");closePanel();keyboardReady();
        pass("clipboard item pastes, clear survives reopening and IME service restart");

        nodeClick("toolbar_hide");SystemClock.sleep(350);check(find("key_SPACE")==null,"Hide navigation did not dismiss keyboard");
        runOnMainSync(()->imm.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT));keyboardReady();clear();type("kaifa");awaitCandidate("开发");clickCandidate("开发");awaitText("开发");
        pass("hide and reopen retain a working IME");
        getTargetContext().getSharedPreferences("qingyu",0).edit().putString("theme","dark").apply();setText("");type("sheji");awaitCandidate("设计");awaitGloss("设计","design");screenshot("v2-dark.png");clickCandidate("设计");awaitText("设计");
        pass("dark theme with new navigation and candidate learning gestures");
    }
    protected void selectMode(String title){nodeClick("toolbar_mode");buttonClick(title);keyboardReady();}
    protected void setLanguage(String title){String value=getTargetContext().getSharedPreferences("qingyu",0).getString("gloss_language","en");String current=value.equals("ja")?"日语":value.equals("fr")?"法语":"英语";nodeClick("toolbar_more");buttonClick("释义显示语言 · "+current);buttonClick(title);closePanel();keyboardReady();}
    private void clickCandidateAfter(String word){awaitCandidate(word);clickCandidate(word);}
    protected void holdCandidate(String word){Rect bounds=new Rect();candidate(word).getBoundsInScreen(bounds);long start=SystemClock.uptimeMillis();touch(bounds.centerX(),bounds.centerY(),true,start);SystemClock.sleep(620);touch(bounds.centerX(),bounds.centerY(),false,SystemClock.uptimeMillis());}
    protected void upCandidate(String word){Rect bounds=new Rect();candidate(word).getBoundsInScreen(bounds);swipe(bounds.centerX(),bounds.centerY(),bounds.centerX(),bounds.centerY()-110,180);}
    private void horizontalCandidate(String word){Rect bounds=new Rect();candidate(word).getBoundsInScreen(bounds);swipe(bounds.centerX(),bounds.centerY(),bounds.centerX()+90,bounds.centerY(),180);}
    private void awaitSelection(int from,int to){long until=SystemClock.uptimeMillis()+3000;int[] selection={-1,-1};while(SystemClock.uptimeMillis()<until){runOnMainSync(()->{selection[0]=editor.getSelectionStart();selection[1]=editor.getSelectionEnd();});if(Math.min(selection[0],selection[1])==from&&Math.max(selection[0],selection[1])==to)return;SystemClock.sleep(30);}throw new AssertionError("Expected selection "+from+".."+to+", actual "+selection[0]+".."+selection[1]+"; editor="+text());}
    private void awaitClip(String value){long until=SystemClock.uptimeMillis()+3000;ClipboardManager manager=(ClipboardManager)getTargetContext().getSystemService(Activity.CLIPBOARD_SERVICE);while(SystemClock.uptimeMillis()<until){String[] actual={""};runOnMainSync(()->{ClipData clip=manager.getPrimaryClip();if(clip!=null&&clip.getItemCount()>0)actual[0]=String.valueOf(clip.getItemAt(0).getText());});if(value.equals(actual[0]))return;SystemClock.sleep(30);}throw new AssertionError("Clipboard did not receive "+value);}
    private JSONArray readHistory()throws Exception{java.io.File file=new java.io.File(getTargetContext().getFilesDir(),"clipboard-history.json");return file.exists()?new JSONArray(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)):new JSONArray();}
    private JSONArray awaitHistoryCount(int count)throws Exception{long until=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<until){JSONArray history=readHistory();if(history.length()==count)return history;SystemClock.sleep(40);}throw new AssertionError("Expected clipboard history count "+count+", actual "+readHistory().length());}
}
