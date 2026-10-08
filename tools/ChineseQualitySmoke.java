package com.qingyu.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Actual Java/JNI decoder regression, not a lookup test of the source lexicon. */
public final class ChineseQualitySmoke {
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private static int rank(EngineSnapshot snapshot, String text) {
        for (int index=0; index<snapshot.candidates.size(); index++) if (text.equals(snapshot.candidates.get(index).text)) return index+1;
        return 0;
    }
    public static void main(String[] args) throws Exception {
        try{run(args);}catch(Throwable error){error.printStackTrace(System.out);System.exit(1);}
    }
    private static void run(String[] args) throws Exception {
        boolean baseline=args.length>3 && args[3].equals("baseline");
        boolean acceptanceOnly=args.length>3 && args[3].equals("acceptance");
        PinyinEngine engine=new PinyinEngine(); engine.open(args[0],args[1]); engine.setLearningEnabled(false);
        if(args.length>4&&!baseline) {
            Class<?> dictionaryClass=Class.forName("com.qingyu.ime.LocalInputDictionary");
            java.lang.reflect.Constructor<?> constructor=dictionaryClass.getDeclaredConstructor();constructor.setAccessible(true);
            Object dictionary=constructor.newInstance();
            Class<?> sqlite=Class.forName("android.database.sqlite.SQLiteDatabase");
            java.lang.reflect.Method open=sqlite.getMethod("openDatabase",String.class,Class.forName("android.database.sqlite.SQLiteDatabase$CursorFactory"),int.class);
            Object database=open.invoke(null,args[4],null,1);
            java.lang.reflect.Field field=dictionaryClass.getDeclaredField("pinyinDatabase");field.setAccessible(true);field.set(dictionary,database);
            if(args.length>5){java.lang.reflect.Field model=dictionaryClass.getDeclaredField("contextModel");model.setAccessible(true);model.set(dictionary,ChineseContextModel.load(Files.newInputStream(Paths.get(args[5]))));}
            engine.setLexicon((PinyinEngine.Lexicon)dictionary);
        }
        int total=0,top1=0,top5=0,top10=0,found=0,strictFailures=0;
        int requiredTotal=0,requiredFailures=0;
        List<Long> timings=new ArrayList<>();
        for(String line:Files.readAllLines(Paths.get(args[2]),StandardCharsets.UTF_8)) {
            if(line.isEmpty()||line.startsWith("#")) continue;
            String[] fields=line.split("\t"); check(fields.length==4||fields.length==5,"four corpus fields with optional assessment scope");
            boolean assessment=fields.length==5&&fields[4].equals("assessment");
            check(fields.length==4||assessment,"unknown corpus acceptance scope");
            String input=fields[0],expected=fields[1],group=fields[2];int limit=Integer.parseInt(fields[3]);
            engine.reset(); EngineSnapshot state=EngineSnapshot.empty();
            for(int length=1;length<=input.length();length++) {
                long start=System.nanoTime();state=engine.search(input.substring(0,length));timings.add(System.nanoTime()-start);
                check(state.rawPinyin.equals(input.substring(0,length)),"no raw keystroke loss: "+input);
            }
            int actual=rank(state,expected);
            // A prefix bearing the same text is not a complete correction:
            // e.g. old nihhao lists 你好 but preview still contains the extra 好.
            if(actual>0&&!engine.previewCandidate(state.candidates.get(actual-1).id).equals(expected))actual=0;
            total++;if(actual==1)top1++;if(actual>0&&actual<=5)top5++;if(actual>0&&actual<=10)top10++;if(actual>0)found++;
            if(actual==0||actual>limit) strictFailures++;
            if(!assessment){requiredTotal++;if(actual==0||actual>limit)requiredFailures++;}
            StringBuilder first=new StringBuilder();for(int i=0;i<Math.min(5,state.candidates.size());i++) {if(i>0)first.append('|');first.append(state.candidates.get(i).text);}
            System.out.printf("CASE\t%s\t%s\t%s\t%d\t%d\t%s%n",input,expected,group,actual,limit,first);
            if(actual>0) {
                int id=state.candidates.get(actual-1).id;
                check(engine.previewCandidate(id).equals(expected),"preview equals exact selected candidate: "+input);
                check(engine.search(input)==state,"preview restores exact snapshot: "+input);
                check(engine.select(id).committedText.equals(expected),"selected word commits unchanged: "+input);
            }
            engine.reset();for(int length=1;length<=input.length();length++)state=engine.search(input.substring(0,length));
            for(int length=input.length();length>0;length--)state=engine.backspace();
            check(state.rawPinyin.isEmpty()&&state.composing.isEmpty(),"continuous deletion clears: "+input);
        }
        Collections.sort(timings);
        System.out.printf("QUALITY total=%d top1=%d top5=%d top10=%d found=%d failures=%d; %d incremental searches p50=%.3fms p95=%.3fms max=%.3fms%n",total,top1,top5,top10,found,strictFailures,timings.size(),timings.get(timings.size()/2)/1e6,timings.get(timings.size()*95/100)/1e6,timings.get(timings.size()-1)/1e6);
        System.out.printf("DAILY_ACCEPTANCE total=%d failures=%d; ASSESSMENT total=%d failures=%d (all CASE rows retained)%n",requiredTotal,requiredFailures,total-requiredTotal,strictFailures-requiredFailures);
        engine.close();
        if(!baseline&&requiredFailures==0)System.out.println("ALL_DAILY_CHINESE_ACCEPTANCE_CHECKS_PASS");
        if(!baseline)check(acceptanceOnly?requiredFailures==0:strictFailures==0,"expected candidate must reach each corpus rank limit; failures="+(acceptanceOnly?requiredFailures:strictFailures));
        System.out.println(baseline?"BASELINE_CHINESE_QUALITY_RECORDED":acceptanceOnly?"CHINESE_FULL_ASSESSMENT_RECORDED":"ALL_CHINESE_QUALITY_CHECKS_PASS");
    }
}
