package com.qingyu.ime;

import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** v0.5 acceptance through the actual installed IME, followed by prior regressions. */
public final class ImeV5Instrumentation extends ImeV4Instrumentation {
    @Override protected String successMarker(){return "ALL_V5_IME_CHECKS_PASS";}
    @Override protected void runChecks() throws Exception {
        String[][] words={{"anzhuo","安卓"},{"dangang","单杠"},{"kuaidi","快递"},{"gaotie","高铁"},{"erweima","二维码"},{"rengongzhineng","人工智能"}};
        for(String[] word:words){clear();type(word[0]);try{awaitCandidate(word[1]);}catch(AssertionError failure){diagnoseEngine(word[0]);throw failure;}AccessibilityNodeInfo candidate=candidate(word[1]);String id=candidate.getViewIdResourceName();int rank=Integer.parseInt(id.substring(id.lastIndexOf('_')+1));check(rank<5,word[0]+" requires paging: "+rank);clickCandidate(word[1]);awaitText(word[1]);}
        pass("modern words, ambiguous dangang and multisyllable words appear in the first five actual IME candidates and commit correctly");
        for(String raw:new String[]{"nohao","nihaoo"}){
            clear();type(raw);awaitCandidate("你好");awaitText(raw);key("ENTER");awaitText(raw);
            clear();type(raw);awaitCandidate("你好");clickCandidate("你好");awaitText("你好");
        }
        pass("nearby-key and extra-letter corrections offer 你好 without rewriting raw letters; Enter preserves raw input and choosing commits only the corrected word");
        for(String[] sentence:new String[][]{{"woxiangqubeijing","我想去北京"},{"jintiantianqihenhao","今天天气很好"}}){
            clear();type(sentence[0]);awaitCandidate(sentence[1]);clickCandidate(sentence[1]);awaitText(sentence[1]);
        }
        pass("continuous sentence pinyin produces selectable complete Chinese sentences");
        getTargetContext().getSharedPreferences("qingyu",0).edit().putBoolean("learning",true).apply();
        try{
            newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            runOnMainSync(()->{editor.setImeOptions(0);((InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).restartInput(editor);});keyboardReady();
            for(int i=0;i<12;i++){clear();type("kd");AccessibilityNodeInfo learned=browseCandidate("快点");check(learned.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Learned candidate click failed");awaitText("快点");}
            clear();type("kd");awaitCandidate("快点");check(candidateRank(candidate("快点"))<3,"Repeated kd selection did not reach the leading candidates");key("ENTER");awaitText("kd");
            pass("twelve actual kd selections move 快点 into the leading three candidates while raw Enter remains kd");
        }finally{getTargetContext().getSharedPreferences("qingyu",0).edit().putBoolean("learning",false).apply();newEditor(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);keyboardReady();}
        File installed=new File(getTargetContext().getFilesDir(),"pinyin-v2.dat");
        check(installed.isFile(),"Missing versioned installed lexicon");
        try(InputStream asset=getTargetContext().getAssets().open("pinyin/dict_pinyin.dat");InputStream copy=new FileInputStream(installed)){check(MessageDigest.isEqual(hash(asset),hash(copy)),"Installed system lexicon still uses v0.4 data");}
        pass("installed versioned lexicon matches the final APK rather than the previous copied dictionary");
        clear();type("kaifa");awaitCandidate("开发");awaitGloss("开发","develop");check(find("candidate_attribution")==null&&find("translation_attribution")==null,"Brand strip present during normal typing");screenshot("v5-pinyin.png");nodeClick("candidate_expand");SystemClock.sleep(160);check(find("candidate_attribution")==null&&find("translation_attribution")==null,"Brand strip present in expanded candidates");screenshot("v5-expanded.png");nodeClick("candidate_expand");keyboardReady();clear();
        pass("local meanings remain available with no branding row in compact or expanded candidates");
        for(int i=0;i<12;i++){
            type("anzhuo");awaitCandidate("安卓");clickCandidate("安卓");awaitText("安卓");
            runOnMainSync(()->activity.startActivity(new Intent(Settings.ACTION_SETTINGS)));
            SystemClock.sleep(220);shell("input keyevent 4");SystemClock.sleep(120);keyboardReady();awaitText("安卓");clear();
        }
        pass("twelve foreground switches to Android Settings preserve committed editor text and restore a working keyboard");
        for(int i=0;i<40;i++){type("dangang");awaitCandidate("单杠");clickCandidate("单杠");awaitText("单杠");key("⌫");key("⌫");awaitText("");}
        pass("forty consecutive ambiguous-word input, selection and deletion cycles keep editor text and IME state synchronized");
        super.runChecks();
    }
    private void diagnoseEngine(String raw)throws Exception{
        if(!debugTarget())return;
        for(java.util.Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet())if(entry.getKey().getName().startsWith("qingyu")){report.append("Worker ").append(entry.getKey().getName()).append('\n');for(int i=0;i<Math.min(8,entry.getValue().length);i++)report.append(entry.getValue()[i]).append('\n');}
        Class<?> type=Class.forName("com.qingyu.core.PinyinEngine");java.lang.reflect.Field active=type.getDeclaredField("activeEngine");active.setAccessible(true);Object engine=active.get(null);
        if(engine==null){report.append("Engine absent\n");return;}
        for(String name:new String[]{"activeInput","context","current","lexicon"}){java.lang.reflect.Field field=type.getDeclaredField(name);field.setAccessible(true);Object value=field.get(engine);report.append("Engine ").append(name).append(": ").append(value).append('\n');if(name.equals("lexicon")&&value!=null){java.lang.reflect.Method lookup=value.getClass().getDeclaredMethod("lookup",String.class,String.class);lookup.setAccessible(true);report.append("Modern lookup count: ").append(((java.util.List<?>)lookup.invoke(value,raw,"")).size()).append('\n');}}
    }
    private AccessibilityNodeInfo browseCandidate(String word){
        awaitCandidate(null);AccessibilityNodeInfo node=candidate(word);if(node!=null)return node;
        nodeClick("candidate_expand");SystemClock.sleep(180);
        for(int page=0;page<20;page++){node=candidate(word);if(node!=null)return node;AccessibilityNodeInfo first=find("candidate_0");if(first==null){for(int i=1;i<128&&first==null;i++)first=find("candidate_"+i);}check(first!=null&&first.getParent().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),"Candidate not found while browsing: "+word);SystemClock.sleep(140);}
        throw new AssertionError("Candidate not found: "+word);
    }
    private static int candidateRank(AccessibilityNodeInfo node){String id=node.getViewIdResourceName();return Integer.parseInt(id.substring(id.lastIndexOf('_')+1));}
    private static byte[] hash(InputStream input)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[32768];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);return digest.digest();}
}
