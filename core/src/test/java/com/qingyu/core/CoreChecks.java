package com.qingyu.core;

import com.qingyu.core.translation.TranslationBatch;
import com.qingyu.core.translation.TranslationProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class CoreChecks {
    public static void main(String[] args) {
        TranslationProvider unstable=new TranslationProvider(){
            public String targetLanguage(){return "en";}
            public String lookup(String chinese)throws Exception{
                if(chinese.equals("失败"))throw new java.io.IOException("dictionary unavailable");
                if(chinese.equals("开发"))return "develop";
                return null;
            }
            public void close(){}
        };
        List<String> candidates=Arrays.asList("失败","开发","无匹配");
        Map<String,String> result=TranslationBatch.lookup(unstable,candidates);
        check(result.size()==1 && "develop".equals(result.get("开发")),"one failed lookup must not suppress later glosses");
        check(candidates.equals(Arrays.asList("失败","开发","无匹配")),"translation must not mutate Chinese candidates");
        try{result.put("乱序","bad");throw new AssertionError("mutable translation result");}catch(UnsupportedOperationException expected){}
        List<Candidate> mutable=new ArrayList<>();mutable.add(new Candidate(3,"开发"));
        EngineSnapshot snapshot=new EngineSnapshot("kaifa","kaifa",mutable,"");mutable.clear();
        check(snapshot.candidates.size()==1,"background snapshot changed after caller mutation");
        check(snapshot.committedText.isEmpty(),"a gloss cannot become committed input");
        System.out.println("ALL_CORE_CHECKS_PASS: translation failure isolation, missing gloss, immutable cross-thread snapshots");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
