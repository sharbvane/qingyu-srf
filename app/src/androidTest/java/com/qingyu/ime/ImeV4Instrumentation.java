package com.qingyu.ime;

import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

/** New behavior plus the existing actual-IME regression path. */
public class ImeV4Instrumentation extends ImeV3Instrumentation {
    private boolean v4Only,modelsAvailable;
    @Override public void onCreate(android.os.Bundle args){v4Only=args!=null&&"true".equals(args.getString("v4_only"));modelsAvailable=args!=null&&"true".equals(args.getString("models_available"));super.onCreate(args);}
    @Override protected String successMarker(){return "ALL_V4_IME_CHECKS_PASS";}
    @Override protected void runChecks() throws Exception {
        Rect idleKey=box("key_a");check(find("toolbar_more")!=null,"Idle navigation missing");screenshot("v4-idle.png");type("nihao");awaitCandidate("你好");SystemClock.sleep(160);
        awaitRawPinyin("nihao");Rect word=new Rect();candidate("你好").getBoundsInScreen(word);Rect raw=floatingBounds(word.top,true);
        check(raw.bottom==word.top&&raw.left<word.left+dp(24),"Pinyin must protrude directly from the candidate row: "+raw+" "+word);check(find("toolbar_more")==null&&idleKey.equals(box("key_a")),"Composition did not replace navigation while preserving key position");
        screenshot("v4-pinyin.png");key("ENTER");awaitCommitted("nihao");awaitNode("toolbar_more");floatingBounds(word.top,false);check(idleKey.equals(box("key_a")),"Restoring navigation moved keys");pass("compact idle candidates/navigation, attached pinyin and active candidates replace navigation without moving keys; Enter commits raw letters only");
        clear();type("zhongguorenmin");awaitCandidate("中国");clickCandidate("中国");awaitRawPinyin("zhongguorenmin");check(find("toolbar_more")==null&&idleKey.equals(box("key_a")),"Partial candidate selection restored navigation or moved keys");key("ENTER");awaitCommitted("zhongguorenmin");
        clear();type("xi");hold("，",620);type("an");awaitCandidate("西安");key("ENTER");awaitCommitted("xi'an");clear();type("nihao");key("ENTER");awaitCommitted("nihao");pass("raw Enter preserves selected-prefix letters, explicit apostrophe and fast queued input");
        clear();key("ENTER");awaitCommitted("\n");int[] sent={0};
        newEditor(InputType.TYPE_CLASS_TEXT);runOnMainSync(()->{editor.setSingleLine(true);editor.setImeOptions(EditorInfo.IME_ACTION_SEND|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);editor.setOnEditorActionListener((v,a,e)->{sent[0]++;return true;});((InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).restartInput(editor);});keyboardReady();
        type("kaifa");awaitCandidate("开发");key("ENTER");awaitCommitted("kaifa");check(sent[0]==0,"Active pinyin unexpectedly sent the editor action");key("ENTER");long until=SystemClock.uptimeMillis()+2000;while(sent[0]==0&&SystemClock.uptimeMillis()<until)SystemClock.sleep(30);check(sent[0]==1,"Empty composition lost the native editor action");pass("raw Enter suppresses send while composing and keeps normal newline/editor actions when empty");
        newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);type("shi");awaitCandidate(null);nodeClick("candidate_expand");SystemClock.sleep(200);Rect body=box("panel_host");
        check(find("candidate_next_page")==null&&find("candidate_previous_page")==null,"Old horizontal paging controls remain");swipe(body.centerX(),body.bottom-dp(32),body.centerX(),body.top+dp(24),250);SystemClock.sleep(400);check(text().equals("shi"),"Vertical browsing accidentally committed text");
        AccessibilityNodeInfo next=null;for(int i=12;i<100&&next==null;i++)next=find("candidate_"+i);check(next!=null,"Continuous browsing did not reveal further candidates");screenshot("v4-expanded.png");String chosen=next.getText().toString();next.performAction(AccessibilityNodeInfo.ACTION_CLICK);awaitCommitted(chosen);keyboardReady();pass("expanded candidates drag vertically with momentum and select a later visible word without paging or translation conflict");
        if(debugTarget()){CandidateV4Check.run(this,activity);pass("native vertical candidate drag/fling/accessibility, cancel, long-press and viewport bounds");}
        clear();type("kaifa");awaitCandidate("开发");clickCandidate("开发");awaitCommitted("开发");floatingBounds(word.top,false);
        newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);type("nihao");awaitText("nihao");floatingBounds(word.top,false);newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);pass("pinyin bubble clears on candidate commit and stays absent in password input");
        nodeClick("toolbar_more");buttonClick("检查更新");awaitNode("update_status");screenshot("v4-update.png");shell("input keyevent 4");SystemClock.sleep(180);
        newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);keyboardReady();pass("More check-update entry opens the in-app update surface and returns to a working IME");
        if(modelsAvailable){LanguageV4Check.run(this);}
        if(!v4Only){super.runChecks();}
    }
    private Rect box(String id){Rect r=new Rect();awaitNode(id).getBoundsInScreen(r);return r;}
    private int dp(float value){return Math.round(value*getTargetContext().getResources().getDisplayMetrics().density);}
    private void awaitRawPinyin(String raw){long until=SystemClock.uptimeMillis()+3000;while(SystemClock.uptimeMillis()<until){AccessibilityNodeInfo n=find("candidate_0");if(n!=null&&n.getParent()!=null&&String.valueOf(n.getParent().getContentDescription()).contains("原始拼音 · "+raw))return;SystemClock.sleep(25);}throw new AssertionError("Missing accessible raw pinyin "+raw);}
    // Android excludes non-touchable, non-focused popup windows from the accessibility inventory.
    // Inspect rendered pixels as well as the actual candidate host's accessible preedit description.
    private Rect floatingBounds(int candidateTop,boolean required){SystemClock.sleep(120);Bitmap shot=automation.takeScreenshot();check(shot!=null,"No screenshot for floating preview");Rect result=new Rect();int ink=0,fill=Color.parseColor("#EFF1EC"),text=Color.parseColor("#202B24");for(int y=Math.max(0,candidateTop-dp(64));y<candidateTop;y++)for(int x=0;x<Math.min(shot.getWidth(),dp(220));x++){int pixel=shot.getPixel(x,y);if(pixel==fill)result.union(x,y,x+1,y+1);if(pixel==text)ink++;}shot.recycle();check(required?!result.isEmpty()&&ink>5:result.isEmpty(),required?"Floating raw text is not painted":"Stale floating preedit");return result;}
    private void awaitCommitted(String value){long until=SystemClock.uptimeMillis()+3000;boolean[] complete={false};while(SystemClock.uptimeMillis()<until){runOnMainSync(()->complete[0]=value.contentEquals(editor.getText())&&BaseInputConnection.getComposingSpanStart(editor.getText())<0);if(complete[0])return;SystemClock.sleep(25);}throw new AssertionError("Uncommitted or wrong text: "+text()+" expected "+value);}
}
