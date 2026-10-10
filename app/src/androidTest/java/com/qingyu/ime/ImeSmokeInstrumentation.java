package com.qingyu.ime;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.HashMap;
import java.util.Map;

/** Real Android IME integration tests using actual touch + InputConnection, no test hooks in production. */
public class ImeSmokeInstrumentation extends Instrumentation {
    protected UiAutomation automation;
    protected Activity activity;
    protected EditText editor;
    protected final Map<String,Rect> keys=new HashMap<>();
    protected final StringBuilder report=new StringBuilder();
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            automation=getUiAutomation();AccessibilityServiceInfo info=automation.getServiceInfo();
            info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;automation.setServiceInfo(info);
            // Starting instrumentation may stop an already-running target IME and
            // make Android select its fallback. Select after the runner is alive.
            shell("ime enable com.qingyu.ime/.QingyuImeService");shell("ime set com.qingyu.ime/.QingyuImeService");
            getTargetContext().getSharedPreferences("qingyu",0).edit().putBoolean("english",false).putString("keyboard_mode","full").putString("gloss_language","en").putFloat("height",1f).putBoolean("translation",true).putBoolean("learning",false).putBoolean("haptic",true).putBoolean("clipboard",true).putString("style","classic").putString("theme","light").apply();
            Intent launch=new Intent(getTargetContext(),SettingsActivity.class);launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=startActivitySync(launch);
            newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            shell("ime set com.qingyu.ime/.QingyuImeService");
            keyboardReady();

            runChecks();
            result.putString("stream","\n"+successMarker()+"\n"+report);finish(Activity.RESULT_OK,result);
        }catch(Throwable error){
            try{report.append("Failure editor: '").append(text()).append("'\n");for(int i=0;i<128;i++){AccessibilityNodeInfo n=find("candidate_"+i);if(n!=null){Rect bounds=new Rect();n.getBoundsInScreen(bounds);report.append("Visible candidate ").append(i).append(": ").append(n.getContentDescription()).append(" ").append(bounds).append('\n');}}screenshot("ime-failure.png");}catch(Throwable diagnostic){report.append("Failure snapshot unavailable: ").append(diagnostic).append('\n');}
            result.putString("stream","\nIME_CHECK_FAILED: "+error+"\n"+report+"\n"+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);
        }
    }
    protected String successMarker(){return "ALL_IME_CHECKS_PASS";}
    protected boolean debugTarget(){return (getTargetContext().getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;}
    protected void runChecks() throws Exception{
            Rect idleKey=new Rect();awaitNode("key_a").getBoundsInScreen(idleKey);type("kaifa");awaitCandidate("开发");Rect typingKey=new Rect();awaitNode("key_a").getBoundsInScreen(typingKey);check(idleKey.equals(typingKey),"First candidate changed keyboard geometry");awaitGloss("开发","develop");screenshot("ime-light.png");clickCandidate("开发");awaitText("开发");pass("candidate click commits Chinese only and first candidate preserves key geometry");
            clear();type("kaifa");awaitCandidate("开发");
            runOnMainSync(()->activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));SystemClock.sleep(500);keyboardReady();awaitCandidate("开发");clickCandidate("开发");awaitText("开发");
            runOnMainSync(()->activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));SystemClock.sleep(500);keyboardReady();pass("orientation change preserves composition");
            clear();type("xiangmu");awaitCandidate("项目");awaitGloss("项目","project");
            Rect original=new Rect();candidate("项目").getBoundsInScreen(original);clear();toggleGloss();type("xiangmu");awaitCandidate("项目");SystemClock.sleep(150);
            AccessibilityNodeInfo unchanged=candidate("项目");Rect withoutGloss=new Rect();unchanged.getBoundsInScreen(withoutGloss);
            check(original.equals(withoutGloss),"gloss toggle moved Chinese candidate");check(!String.valueOf(unchanged.getContentDescription()).contains("project"),"gloss remained after disabling");
            clickCandidate("项目");awaitText("项目");toggleGloss();pass("local gloss toggle preserves candidate layout and Chinese input");
            clear();type("zhongguorenmin");key("SPACE");awaitText("中国人民");pass("sentence composition");

            clear();type("shi");awaitCandidate(null);nodeClick("candidate_expand");SystemClock.sleep(160);
            AccessibilityNodeInfo gridHost=awaitNode("panel_host"),gridCandidate=walk(gridHost,"candidate_0");check(gridCandidate!=null,"expanded candidates missing");
            int lastVisible=0;for(int i=1;i<128;i++)if(walk(gridHost,"candidate_"+i)!=null)lastVisible=i;
            check(gridCandidate.getParent().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),"continuous candidate scroll failed");SystemClock.sleep(250);
            AccessibilityNodeInfo scrolled=null;gridHost=awaitNode("panel_host");for(int i=lastVisible+1;i<128&&scrolled==null;i++)scrolled=walk(gridHost,"candidate_"+i);
            check(scrolled!=null,"scroll did not reveal a later candidate");String chosen=scrolled.getText().toString();scrolled.performAction(AccessibilityNodeInfo.ACTION_CLICK);awaitText(chosen);keyboardReady();pass("candidate expand, vertical scroll and choose with variable-height complete gloss rows");

            clear();type("nihao");key("SPACE");key("LANG");type("abc");key("?123");SystemClock.sleep(100);collectKeys();key("1");key("2");key("ABC");SystemClock.sleep(100);collectKeys();
            awaitText("你好abc12");pass("rapid Chinese/English/number commit order");
            key("LANG");clear();keyboardReady();

            type("nihao");key("⌫");key("o");key("SPACE");awaitText("你好");pass("composition delete and resume");
            setText("😊X");key("⌫");key("⌫");awaitText("");pass("Unicode code point backspace");
            setText("abcdefghijk");hold("⌫",1200);awaitText("");pass("held backspace repeats");
            holdSlide("q",1,false);awaitText("1");pass("long press number");
            clear();type("xi");hold("，",620);type("an");awaitCandidate("西安");clickCandidate("西安");awaitText("西安");pass("explicit pinyin syllable separator");
            clear();String repeated=new String(new char[70]).replace('\0','a');type(repeated);key("SPACE");
            long longInputUntil=SystemClock.uptimeMillis()+4000;while(text().length()!=70&&SystemClock.uptimeMillis()<longInputUntil)SystemClock.sleep(30);
            check(text().length()==70&&!text().contains("a"),"long input lost keystrokes: "+text());pass("70 character continuous input segments without loss");

            clear();key("LANG");keyboardReady();type("abc");awaitText("abc");Rect space=keys.get("SPACE");
            swipe(space.centerX(),space.centerY(),space.centerX()-80,space.centerY(),180);SystemClock.sleep(120);
            type("x");String cursorText=text();check(cursorText.contains("x")&&!cursorText.endsWith("x"),"space swipe did not move cursor: "+cursorText);pass("space swipe cursor");

            newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);keyboardReady();key("LANG");type("kaifa");awaitText("kaifa");check(find("candidate_0")==null,"password produced Chinese candidates");pass("password direct input, language locked");
            key("?123");SystemClock.sleep(100);collectKeys();key("!");key("SHIFT");SystemClock.sleep(100);collectKeys();key("*");key("=");key("\"");awaitText("kaifa!*=\"");pass("password ASCII punctuation accessible");

            newEditor(InputType.TYPE_CLASS_NUMBER);keyboardReady();key("1");key("2");key("3");awaitText("123");pass("numeric field keypad");

            newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);keyboardReady();
            getTargetContext().getSharedPreferences("qingyu",0).edit().putBoolean("english",false).putString("theme","dark").apply();
            runOnMainSync(()->{editor.clearFocus();editor.requestFocus();((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).restartInput(editor);});
            SystemClock.sleep(200);keyboardReady();type("sheji");awaitCandidate("设计");awaitGloss("设计","design");screenshot("ime-dark.png");clickCandidate("设计");awaitText("设计");pass("dark theme restart and input");
    }
    protected void newEditor(int type){
        runOnMainSync(()->{
            LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);view.setPadding(30,150,30,30);
            editor=new EditText(activity);editor.setTextSize(24);editor.setInputType(type);editor.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);view.addView(editor,new LinearLayout.LayoutParams(-1,220));activity.setContentView(view);editor.requestFocus();
            ((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);
        });waitForIdleSync();SystemClock.sleep(150);
        runOnMainSync(()->{InputMethodManager imm=(InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);imm.restartInput(editor);imm.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);});SystemClock.sleep(200);
    }
    protected void keyboardReady(){long until=SystemClock.uptimeMillis()+5000,showAt=0;while(SystemClock.uptimeMillis()<until){if(find("key_SPACE")!=null||find("key_1")!=null){SystemClock.sleep(450);collectKeys();return;}if(SystemClock.uptimeMillis()>=showAt){runOnMainSync(()->((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT));showAt=SystemClock.uptimeMillis()+250;}SystemClock.sleep(40);}throw new AssertionError("Keyboard did not become accessible");}
    protected AccessibilityNodeInfo walk(AccessibilityNodeInfo root,String id){if(root==null)return null;String name=root.getViewIdResourceName();if(("com.qingyu.ime:id/"+id).equals(name))return root;for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo child=root.getChild(i);AccessibilityNodeInfo found=walk(child,id);if(found!=null)return found;}return null;}
    protected AccessibilityNodeInfo find(String id){for(AccessibilityWindowInfo w:automation.getWindows()){AccessibilityNodeInfo found=walk(w.getRoot(),id);if(found!=null)return found;}return null;}
    protected void collect(AccessibilityNodeInfo node){if(node==null)return;String id=node.getViewIdResourceName();if(id!=null&&id.startsWith("com.qingyu.ime:id/key_")){Rect rect=new Rect();node.getBoundsInScreen(rect);keys.put(id.substring(id.indexOf("key_")+4),rect);}for(int i=0;i<node.getChildCount();i++)collect(node.getChild(i));}
    protected void collectKeys(){keys.clear();for(AccessibilityWindowInfo w:automation.getWindows())collect(w.getRoot());}
    protected void touch(float x,float y,boolean down,long when){MotionEvent event=MotionEvent.obtain(when,when,down?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_UP,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);check(automation.injectInputEvent(event,true),"Touch injection failed");event.recycle();}
    protected void key(String value){Rect r=keys.get(value);AccessibilityNodeInfo current=find("key_"+value);if(current!=null){Rect fresh=new Rect();current.getBoundsInScreen(fresh);r=fresh;keys.put(value,fresh);}if(r==null){collectKeys();r=keys.get(value);}check(r!=null,"Missing key "+value);long t=SystemClock.uptimeMillis();touch(r.centerX(),r.centerY(),true,t);touch(r.centerX(),r.centerY(),false,t+8);}
    protected void type(String s){for(int i=0;i<s.length();i++)key(s.substring(i,i+1));}
    protected void hold(String value,long ms){Rect r=keys.get(value);check(r!=null,"Missing hold key "+value);long t=SystemClock.uptimeMillis();touch(r.centerX(),r.centerY(),true,t);SystemClock.sleep(ms);touch(r.centerX(),r.centerY(),false,SystemClock.uptimeMillis());}
    protected void holdSlide(String value,int direction,boolean cancel){Rect r=new Rect();awaitNode("key_"+value).getBoundsInScreen(r);long start=SystemClock.uptimeMillis();touch(r.centerX(),r.centerY(),true,start);SystemClock.sleep(620);float endX=r.centerX()+direction*Math.max(r.width()*.45f,45f);MotionEvent move=MotionEvent.obtain(start,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,endX,r.centerY(),0);move.setSource(InputDevice.SOURCE_TOUCHSCREEN);check(automation.injectInputEvent(move,true),"Long-slide move failed");move.recycle();SystemClock.sleep(60);MotionEvent end=MotionEvent.obtain(start,SystemClock.uptimeMillis(),cancel?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP,endX,r.centerY(),0);end.setSource(InputDevice.SOURCE_TOUCHSCREEN);check(automation.injectInputEvent(end,true),"Long-slide end failed");end.recycle();}
    protected void closePanel(){for(String id:new String[]{"toolbar_more","toolbar_edit","toolbar_emoji","toolbar_mode"}){AccessibilityNodeInfo n=find(id);if(n!=null&&n.isSelected()){check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Panel navigation close failed");SystemClock.sleep(180);return;}}throw new AssertionError("Open panel has no selected owning navigation icon");}
    protected void swipe(float x,float y,float endX,float endY,long duration){long start=SystemClock.uptimeMillis();touch(x,y,true,start);for(int i=1;i<=5;i++){MotionEvent m=MotionEvent.obtain(start,start+duration*i/5,MotionEvent.ACTION_MOVE,x+(endX-x)*i/5,y+(endY-y)*i/5,0);m.setSource(InputDevice.SOURCE_TOUCHSCREEN);automation.injectInputEvent(m,true);m.recycle();}touch(endX,endY,false,start+duration);}
    protected void toggleGloss(){nodeClick("toolbar_more");buttonClick("释义显示语言 · 英语");long until=SystemClock.uptimeMillis()+4000;String title=null;while(SystemClock.uptimeMillis()<until){if(findButton("隐藏释义")!=null){title="隐藏释义";break;}if(findButton("显示释义")!=null){title="显示释义";break;}AccessibilityNodeInfo panel=find("panel_host");if(panel!=null)scrollPanelForward(panel);SystemClock.sleep(100);}check(title!=null,"Gloss toggle not reachable");buttonClick(title);closePanel();keyboardReady();}
    protected AccessibilityNodeInfo buttonIn(AccessibilityNodeInfo node,String title){
        if(node==null)return null;String value=String.valueOf(node.getText()),description=String.valueOf(node.getContentDescription());
        if("android.widget.Button".equals(String.valueOf(node.getClassName()))&&node.isClickable()&&node.isVisibleToUser()&&(title.equals(value)||title.equals(description)))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=buttonIn(node.getChild(i),title);if(found!=null)return found;}return null;
    }
    protected AccessibilityNodeInfo findButton(String title){for(AccessibilityWindowInfo window:automation.getWindows()){AccessibilityNodeInfo found=buttonIn(window.getRoot(),title);if(found!=null)return found;}return null;}
    protected AccessibilityNodeInfo anyButton(AccessibilityNodeInfo node,String title){if(node==null)return null;if("android.widget.Button".equals(String.valueOf(node.getClassName()))&&node.isClickable()&&(title.contentEquals(node.getText()==null?"":node.getText())||title.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo match=anyButton(node.getChild(i),title);if(match!=null)return match;}return null;}
    protected void buttonClick(String title){long until=SystemClock.uptimeMillis()+4000;boolean clicked=false;while(SystemClock.uptimeMillis()<until){AccessibilityNodeInfo node=findButton(title);if(node!=null&&node.isEnabled()&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){clicked=true;break;}if(node==null)for(AccessibilityWindowInfo window:automation.getWindows()){AccessibilityNodeInfo offscreen=anyButton(window.getRoot(),title);if(offscreen!=null)offscreen.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());}if(node==null){AccessibilityNodeInfo panel=find("panel_host");if(panel!=null)scrollPanelForward(panel);}SystemClock.sleep(80);}check(clicked,"Button unavailable or failed "+title);waitForIdleSync();SystemClock.sleep(130);}
    private boolean scrollPanelForward(AccessibilityNodeInfo node){if("android.widget.ScrollView".contentEquals(node.getClassName()==null?"":node.getClassName())&&node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null&&scrollPanelForward(child))return true;}return false;}
    protected AccessibilityNodeInfo awaitNode(String id){long until=SystemClock.uptimeMillis()+4000;AccessibilityNodeInfo node;while((node=find(id))==null&&SystemClock.uptimeMillis()<until)SystemClock.sleep(30);check(node!=null,"Missing node "+id);return node;}
    protected void awaitNodeText(String id,String expected){long until=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<until){AccessibilityNodeInfo node=find(id);if(node!=null&&String.valueOf(node.getText()).contains(expected))return;SystemClock.sleep(30);}throw new AssertionError("Missing text "+expected+" in "+id);}
    protected void nodeClick(String id){AccessibilityNodeInfo node=find(id);check(node!=null,"Missing node "+id);check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Node click failed "+id);}
    protected AccessibilityNodeInfo candidate(String text){if(text==null)return find("candidate_0");for(AccessibilityWindowInfo window:automation.getWindows()){AccessibilityNodeInfo found=candidateText(window.getRoot(),text);if(found!=null)return found;}return null;}
    private AccessibilityNodeInfo candidateText(AccessibilityNodeInfo node,String text){if(node==null)return null;String id=node.getViewIdResourceName();if(id!=null&&id.startsWith("com.qingyu.ime:id/candidate_")&&text.contentEquals(node.getText()==null?"":node.getText()))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=candidateText(node.getChild(i),text);if(found!=null)return found;}return null;}
    protected void awaitCandidate(String text){long until=SystemClock.uptimeMillis()+4000;while(SystemClock.uptimeMillis()<until){AccessibilityNodeInfo node=candidate(text);if(node!=null){AccessibilityNodeInfo host=node.getParent();if(host!=null&&!String.valueOf(host.getContentDescription()).contains("候选更新中"))return;}SystemClock.sleep(30);}throw new AssertionError("Missing ready candidate "+text+"; editor="+text());}
    protected void awaitGloss(String word,String gloss){long until=SystemClock.uptimeMillis()+4000;String actual="no candidate";while(SystemClock.uptimeMillis()<until){AccessibilityNodeInfo n=candidate(word);if(n!=null){actual=String.valueOf(n.getContentDescription());if(actual.contains(gloss))return;}SystemClock.sleep(40);}throw new AssertionError("Missing gloss "+gloss+"; editor="+text()+"; candidate="+actual);}
    protected void clickCandidate(String word){AccessibilityNodeInfo n=candidate(word);check(n!=null,"Missing candidate "+word);check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Candidate click failed");}
    protected String text(){final String[] text={""};runOnMainSync(()->text[0]=editor.getText().toString());return text[0];}
    protected void awaitText(String expected){long until=SystemClock.uptimeMillis()+3000;while(SystemClock.uptimeMillis()<until){if(expected.equals(text()))return;SystemClock.sleep(25);}throw new AssertionError("Expected '"+expected+"', actual '"+text()+"'");}
    protected void awaitNonEmpty(){long until=SystemClock.uptimeMillis()+2000;while(SystemClock.uptimeMillis()<until){if(!text().isEmpty())return;SystemClock.sleep(25);}throw new AssertionError("No committed text");}
    protected void setText(String text){runOnMainSync(()->{editor.setText(text);editor.setSelection(text.length());((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).restartInput(editor);});SystemClock.sleep(150);keyboardReady();}
    protected void clear(){setText("");}
    protected void pass(String name){report.append("PASS ").append(name).append('\n');Bundle b=new Bundle();b.putString("stream","PASS "+name+"\n");sendStatus(0,b);}
    protected void mainCheck(Runnable action){Throwable[] failure={null};runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError("Main-thread check failed",failure[0]);}
    protected void screenshot(String name)throws Exception{
        // Accessibility state can update before the invalidated Canvas is drawn.
        // Allow rendered frames to catch up before exporting visual evidence.
        waitForIdleSync();SystemClock.sleep(250);
        java.io.File file=new java.io.File(getTargetContext().getExternalFilesDir(null),name);android.graphics.Bitmap bitmap=automation.takeScreenshot();try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    protected void shell(String command)throws Exception{try(android.os.ParcelFileDescriptor fd=automation.executeShellCommand(command);java.io.FileInputStream input=new java.io.FileInputStream(fd.getFileDescriptor())){byte[] b=new byte[512];while(input.read(b)!=-1){}}}
    protected static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
