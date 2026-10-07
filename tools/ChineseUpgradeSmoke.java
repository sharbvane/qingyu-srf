package com.qingyu.core;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Real old-model learning, reopen and new-model compatibility; no APK test hook. */
public final class ChineseUpgradeSmoke {
    private static void check(boolean valid,String label){if(!valid)throw new AssertionError(label);}
    private static int id(EngineSnapshot state,String word){
        for(Candidate candidate:state.candidates)if(candidate.text.equals(word))return candidate.id;
        throw new AssertionError("Missing native choice "+word+" in "+state.composing);
    }
    public static void main(String[] args){
        try{run(args);}catch(Throwable error){error.printStackTrace(System.out);System.exit(1);}
    }
    private static void run(String[] args)throws Exception{
        Files.deleteIfExists(Paths.get(args[2]));
        PinyinEngine engine=new PinyinEngine();engine.open(args[0],args[2]);
        EngineSnapshot state=engine.search("nihaoqingyu");
        state=engine.select(id(state,"你好"));state=engine.select(id(state,"轻"));
        check(engine.select(id(state,"语")).committedText.equals("你好轻语"),"old native components create custom phrase");
        engine.flush();engine.close();
        byte[] learned=Files.readAllBytes(Paths.get(args[2]));check(learned.length>40,"old model writes actual learned lemma");
        engine.open(args[0],args[2]);engine.setLearningEnabled(false);
        state=engine.search("nihaoqingyu");check(state.candidates.get(0).text.equals("你好轻语"),"old learned phrase survives reopen");
        engine.close();
        engine.open(args[1],args[2]);engine.setLearningEnabled(false);
        state=engine.search("nihaoqingyu");check(state.candidates.get(0).text.equals("你好轻语"),"new model reads same old learned phrase");
        check(engine.previewCandidate(state.candidates.get(0).id).equals("你好轻语"),"old learned spelling and full commit preserved");
        check(engine.select(state.candidates.get(0).id).committedText.equals("你好轻语"),"new model commits old learned phrase");
        engine.flush();check(Arrays.equals(learned,Files.readAllBytes(Paths.get(args[2]))),"privacy and preview keep old learning bytes unchanged");
        boolean[] contextualSelection={false};
        engine.setLexicon(new PinyinEngine.Lexicon(){
            public List<PinyinEngine.Word> lookup(String code,String before){return Collections.emptyList();}
            public void learn(String code,String text,String before){
                if(text.equals("世界")){check(before.equals("你好"),"native fixed prefix is included in context learning");contextualSelection[0]=true;}
            }
        });
        engine.setLearningEnabled(true);state=engine.search("nihaoshijie");state=engine.select(id(state,"你好"));
        check(engine.select(id(state,"世界")).committedText.equals("你好世界"),"native partial phrase protocol remains usable");
        check(contextualSelection[0],"native selected phrase reaches platform learning once");
        engine.close();System.out.println("PASS native old learned phrase, new-model reopen, spelling/commit, privacy and fixed-prefix context");
        System.out.println("ALL_CHINESE_UPGRADE_CHECKS_PASS");
    }
}
