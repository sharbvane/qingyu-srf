package com.qingyu.ime;

import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/** v0.6 acceptance uses the installed IME, then retains the previous regression checks. */
public final class ImeV6Instrumentation extends ImeV5Instrumentation {
    @Override protected String successMarker(){return "ALL_V6_IME_CHECKS_PASS";}
    @Override protected void runChecks() throws Exception {
        try(java.io.DataInputStream asset=new java.io.DataInputStream(getTargetContext().getAssets().open("input/chinese-context-v1.bin"))){check(asset.readInt()==0x5159434d&&asset.readInt()==2,"Packaged Chinese context asset is missing or invalid");}
        super.runChecks();
        for(String[] sentence:new String[][]{{"woyaokanduanshipin","我要看短视频"},{"qingsaomazhifu","请扫码支付"},{"woxiangdawangyueche","我想打网约车"}}){
            clear();type(sentence[0]);awaitCandidate(sentence[1]);
            AccessibilityNodeInfo node=candidate(sentence[1]);String id=node.getViewIdResourceName();int rank=Integer.parseInt(id.substring(id.lastIndexOf('_')+1));
            check(rank<3,"Natural sentence requires paging: "+sentence[0]+" rank="+rank);
            if(sentence[0].equals("woyaokanduanshipin"))check(rank==0,"我要看短视频 is not the first candidate");
            nodeClick("candidate_expand");SystemClock.sleep(180);check(candidate(sentence[1])!=null,"Expanded sentence lost its complete text");
            screenshot("v6-expanded.png");nodeClick("candidate_expand");keyboardReady();clickCandidate(sentence[1]);awaitText(sentence[1]);
        }
        pass("natural continuous pinyin sentences rank in the first three, 我要看短视频 ranks first, expansion preserves full text and selection commits the entire sentence");
        clear();type("dan");key("SPLIT");type("gang");awaitCandidate("单杠");screenshot("v6-pinyin.png");clickCandidate("单杠");awaitText("单杠");
        clear();type("nihao");key("ENTER");awaitText("nihao");
        pass("manual pinyin segmentation remains selectable and Enter still preserves original letters");
        setText("这是一个完全结束的句子。");SystemClock.sleep(600);check(find("candidate_0")==null,"Terminal punctuation produced an unrelated next word");
        clear();type("kaifa");awaitCandidate("开发");holdCandidate("开发");awaitNodeText("translation_detail","develop");
        check(findButton("复制译文")!=null&&findButton("输入译文")!=null,"Detail actions are missing");screenshot("v6-detail.png");
        buttonClick("复制译文");awaitClip("develop");check(text().equals("kaifa"),"Copy committed the candidate");buttonClick("输入译文");awaitText("develop");
        pass("detail hierarchy exposes concise copy/input actions; copying preserves composition and input commits only the concise translation");
        clear();
    }
}
