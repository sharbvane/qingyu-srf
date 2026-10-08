package com.qingyu.ime;

import android.database.sqlite.SQLiteDatabase;
import com.qingyu.core.NineKeyCandidate;
import com.qingyu.core.PinyinEngine;
import com.qingyu.core.EngineSnapshot;
import com.qingyu.core.Candidate;
import com.qingyu.core.ChineseContextModel;
import java.lang.reflect.Field;
import java.util.List;
import java.io.File;
import java.nio.file.Files;

/** Android SQLite checks through app_process; no application test hooks. */
public final class InputDictionarySmoke {
    private static void check(boolean condition,String label) {if(!condition) throw new AssertionError(label);}
    public static void main(String[] args) throws Exception {
        LocalInputDictionary dictionary=new LocalInputDictionary();
        Field field=LocalInputDictionary.class.getDeclaredField("database");field.setAccessible(true);
        field.set(dictionary,SQLiteDatabase.openDatabase(args[0],null,SQLiteDatabase.OPEN_READONLY));
        check(args.length>=2,"Modern lexicon asset is required for this check");
        Field modern=LocalInputDictionary.class.getDeclaredField("pinyinDatabase");modern.setAccessible(true);modern.set(dictionary,SQLiteDatabase.openDatabase(args[1],null,SQLiteDatabase.OPEN_READONLY));
        Field contextual=LocalInputDictionary.class.getDeclaredField("contextModel");contextual.setAccessible(true);
        if(args.length>4)contextual.set(dictionary,ChineseContextModel.load(Files.newInputStream(new File(args[4]).toPath())));
        List<NineKeyCandidate> hello=dictionary.suggestNineKey("64426");
        check(hello.get(0).text.equals("你好") && hello.get(0).consumedDigits==5,"common nine-key exact reading");
        check(dictionary.suggestNineKey("52432").get(0).text.equals("开发"),"real full word dictionary");
        List<NineKeyCandidate> project=dictionary.suggestNineKey("524329426468");
        System.out.println("Nine-key sentence first="+project.get(0).text+" pinyin="+project.get(0).pinyin+" digits="+project.get(0).consumedDigits);
        check(project.get(0).text.equals("开发项目"),"multiword nine-key segmentation");
        check(dictionary.suggestNineKey("644").stream().anyMatch(candidate->candidate.text.equals("你好")),"partial pinyin completion");
        check(dictionary.suggestNineKey("111").isEmpty(),"digit trust boundary");
        check(LocalInputDictionary.nineKeyPreedit("64426",hello).equals("ni'hao"),"nine-key composition exposes pinyin, not key numbers");
        check(LocalInputDictionary.nineKeyPreedit("644",dictionary.suggestNineKey("644")).equals("ni'h"),"partial preedit must not invent untyped completion letters");
        check(LocalInputDictionary.nineKeyPending("644","64","ni").equals("nig"),"pending next key replaced already resolved pinyin");
        check(LocalInputDictionary.nineKeyPending("6442","64426","ni'hao").equals("ni'ha"),"pending delete replaced already resolved pinyin");
        check(LocalInputDictionary.nineKeyPending("64'","64","ni").equals("ni'")&&LocalInputDictionary.nineKeyPending("64","64'","ni'").equals("ni"),"pending separator insertion/deletion lost its boundary");
        check(LocalInputDictionary.nineKeyPending("54","64","ni").equals(LocalInputDictionary.nineKeyFallback("54")),"unrelated pending keys reused old spelling");
        for(String pending:new String[]{"2","5","548","64426","64'426","548'"}){
            String fallback=LocalInputDictionary.nineKeyFallback(pending),preedit=LocalInputDictionary.nineKeyPreedit(pending,dictionary.suggestNineKey(pending));
            check(fallback.matches("[a-z']+")&&fallback.replace("'","").length()==pending.replace("'","").length(),"cold/pending key fallback lost a key or displayed digits: "+pending);
            check(preedit.matches("[a-z']+")&&preedit.replace("'","").length()==pending.replace("'","").length(),"live preedit lost a key or displayed digits: "+pending);
        }
        List<NineKeyCandidate> nineKey=dictionary.suggestNineKey("5485426","jiu");
        check(nineKey.stream().anyMatch(candidate->candidate.text.equals("九键")&&candidate.pinyin.equals("jiu'jian")&&candidate.consumedDigits==7),"modern nine-key word is absent from bounded generic readings");
        check(nineKey.stream().allMatch(candidate->candidate.pinyin.split("'",2)[0].equals("jiu")),"selected side syllable leaked other first readings");
        List<String> readings=dictionary.nineKeyReadings("548");
        check(readings.contains("jiu")&&readings.contains("liu")&&readings.contains("ji")&&readings.contains("li")&&readings.size()<=6,"side syllables must preserve long and short alternatives");
        List<NineKeyCandidate> splitHello=dictionary.suggestNineKey("64'426");
        check(splitHello.get(0).text.equals("你好")&&splitHello.get(0).consumedDigits==6&&LocalInputDictionary.nineKeyPreedit("64'426",splitHello).equals("ni'hao"),"explicit split changed text/raw consumed length");
        List<NineKeyCandidate> splitNine=dictionary.suggestNineKey("548'5426","jiu");
        check(splitNine.stream().anyMatch(candidate->candidate.text.equals("九键")&&candidate.consumedDigits==8),"manual syllable split rejected a modern full reading");
        check(dictionary.suggestNineKey("6'4426").stream().noneMatch(candidate->candidate.text.equals("你好")),"explicit split was silently ignored");
        check(dictionary.suggestNineKey("64'").stream().anyMatch(candidate->candidate.text.equals("你")&&candidate.consumedDigits==3),"trailing split must be consumed with its preceding syllable");
        check(LocalInputDictionary.nineKeyPreedit("524329426468",java.util.Collections.singletonList(new NineKeyCandidate("开发","kai'fa",5))).equals("kai'fa'wgamgmt"),"partial candidate fallback must retain unconsumed keys");
        for(String bad:new String[]{"'64","64''426","64 426","64126","64\n426","2".repeat(65)})check(dictionary.suggestNineKey(bad).isEmpty()&&LocalInputDictionary.nineKeyFallback(bad).isEmpty(),"T9 input trust boundary "+bad);
        check(dictionary.suggestNineKey("5485426","JIU").isEmpty(),"side reading trust boundary");
        System.out.println("NINE_KEY_V065_CHECKS_PASS: dynamic pinyin, raw separators, bounded modern readings, explicit first syllable, cold fallback and retained suffix");
        String longDigits="64426".repeat(8);
        List<NineKeyCandidate> longSentence=dictionary.suggestNineKey(longDigits);
        check(!longSentence.isEmpty() && longSentence.get(0).consumedDigits==40,"long digit sentence retains every input key");
        check(dictionary.lookupEnglish("hello").equals("你好"),"reverse Chinese gloss");
        check(dictionary.lookupEnglish("developers").contains("开发"),"reverse gloss inflection");
        java.util.Map<String,String> tags=dictionary.partsOfSpeech(java.util.Arrays.asList("项目","开发","美丽","非常","的","hello","未知词性词组"));
        check(tags.get("项目").equals("n") && tags.get("开发").equals("v") && tags.get("美丽").equals("ns"),"exact source tags, including ambiguous lexical readings");
        check(tags.get("非常").equals("d") && tags.get("的").equals("uj"),"adverb/particle tags");
        check(!tags.containsKey("hello") && !tags.containsKey("未知词性词组"),"unknown and English words stay neutral");
        check(dictionary.partsOfSpeech(java.util.Arrays.asList("项目","未知词性词组")).get("项目").equals("n"),"cached POS batch");
        check(dictionary.predictChinese("中国").contains("人民"),"Chinese context continuation");
        for(String boundary:new String[]{"中国。","中国！","中国?","中国，","中国\n。","甲乙丙丁戊己","hello","123"})check(dictionary.predictChinese(boundary).isEmpty(),"unreliable/boundary Chinese prediction: "+boundary);
        check(dictionary.example("未知词性词组").isEmpty(),"unknown source example must be empty");
        dictionary.setLearningEnabled(false);dictionary.learnChinese("绝密内容","上下文");
        Field learning=LocalInputDictionary.class.getDeclaredField("chineseLearning");learning.setAccessible(true);
        check(((java.util.Map<?,?>)learning.get(dictionary)).isEmpty(),"private Chinese input disables learning");
        dictionary.setLearningEnabled(true);
        dictionary.learnChinese("单次选择","本地验证");check(!dictionary.predictChinese("本地验证").contains("单次选择"),"single accidental next word was promoted");dictionary.learnChinese("单次选择","本地验证");check(!dictionary.predictChinese("本地验证").contains("单次选择"),"two accidental next words were promoted");dictionary.learnChinese("单次选择","本地验证");check(dictionary.predictChinese("本地验证").contains("单次选择"),"repeated contextual preference is missing");
        for(int i=0;i<10;i++) dictionary.learnChinese("轻语","测试");
        check(dictionary.predictChinese("测试").get(0).equals("轻语"),"personal context continuation");
        ChineseContextModel contextModel=(ChineseContextModel)contextual.get(dictionary);
        if(contextModel!=null){
            Field continuations=ChineseContextModel.class.getDeclaredField("continuations");continuations.setAccessible(true);
            boolean checked=false;
            for(String history:new java.util.TreeSet<>(((java.util.Map<String,?>)continuations.get(contextModel)).keySet())){
                List<String> observed=contextModel.predict(history);if(observed.size()<2)continue;
                String preferred=observed.get(observed.size()-1);for(int i=0;i<3;i++)dictionary.learnChinese(preferred,history);
                check(dictionary.predictChinese(history).get(0).equals(preferred),"corpus score overwrote repeated personal prediction");checked=true;break;
            }
            check(checked,"corpus resource lacks a multiword prediction regression fixture");
        }
        for(String[] word:new String[][]{{"dangang","单杠"},{"anzhuo","安卓"},{"biancheng","编程"},{"shujuku","数据库"}})check(rank(dictionary.lookup(word[0],""),word[1])>=0&&rank(dictionary.lookup(word[0],""),word[1])<8,"modern indexed common word "+word[0]+" -> "+word[1]);
        check(rank(dictionary.lookup("dan'gang",""),"单杠")>=0,"explicit full-pinyin boundary");check(rank(dictionary.lookup("xi'an",""),"西安")>=0&&rank(dictionary.lookup("xi'an",""),"先")<0,"explicit apostrophe must not become another syllable");
        check(rank(dictionary.completePinyin("bianchen",""),"编程")>=0,"indexed modern incomplete-prefix completion");
        for(String bad:new String[]{"","'","a''b","a b","nihao1","nihao\n","a".repeat(65)})check(dictionary.lookup(bad,"").isEmpty(),"pinyin query boundary "+bad);
        List<PinyinEngine.Word> original=dictionary.lookup("kd","");int initial=rank(original,"快点");check(initial>=0,"initials contain common 快点");double score=original.get(initial).score;
        Field coded=LocalInputDictionary.class.getDeclaredField("pinyinLearning");coded.setAccessible(true);dictionary.setLearningEnabled(false);dictionary.learn("kd","快点","我");check(((java.util.Map<?,?>)coded.get(dictionary)).isEmpty(),"private field learned pinyin code or aliases");check(rank(dictionary.lookup("kd",""),"快点")==initial,"private field erased old rankings");dictionary.setLearningEnabled(true);
        dictionary.learn("kd","快点","");List<PinyinEngine.Word> once=dictionary.lookup("kd","");check(rank(once,"快点")==initial&&once.get(rank(once,"快点")).score==score,"one accidental choice strongly changed rank");dictionary.learn("kd","快点","");check(dictionary.lookup("kd","").get(rank(dictionary.lookup("kd",""),"快点")).score==score,"two accidental choices changed frequency");
        for(int i=2;i<12;i++)dictionary.learn("kd","快点","");List<PinyinEngine.Word> learned=dictionary.lookup("kd","");int after=rank(learned,"快点");check(after<initial&&after==0,"repeated initials choice did not move 快点 to first");check(dictionary.lookup("kuaidian","").get(0).text.equals("快点"),"full/initial aliases disagree");
        System.out.println("PERSONALIZATION kd 快点 initial="+(initial+1)+" one-choice="+(rank(once,"快点")+1)+" after-12="+(after+1));
        check(rank(dictionary.lookup("nihao",""),"拟好")>=0&&rank(dictionary.lookup("nohao",""),"拟好")<0,"wrong-spelling test needs a real modern word");
        dictionary.learn("nohao","拟好","测试","ni'hao");check(rank(dictionary.lookup("nohao","测试"),"拟好")<0,"single mistaken correction became exact");check(((java.util.Map<?,?>)coded.get(dictionary)).get("nohao\t拟好").equals(1),"one contextual choice counted twice");
        dictionary.learn("nohao","拟好","测试","ni'hao");check(rank(dictionary.lookup("nohao","测试"),"拟好")<0,"two mistaken corrections became exact");dictionary.learn("nohao","拟好","测试","ni'hao");check(rank(dictionary.lookup("nohao","测试"),"拟好")>=0,"repeated spelling preference not recalled");
        dictionary.learn("nohaoo","拟好","测试","ni'hao");
        // A selected full phrase can be recalled by either full letters or initials.
        for(int i=0;i<3;i++)dictionary.learn("nihaoqingyu","你好轻语","测试","ni'hao'qing'yu");check(rank(dictionary.lookup("nihaoqingyu","测试"),"你好轻语")>=0&&rank(dictionary.lookup("nhqy","测试"),"你好轻语")>=0,"new selected phrase canonical/full/initial recall");
        File learningFile=new File(new File(args[1]).getParentFile(),"test-pinyin-learning.tsv");Field file=LocalInputDictionary.class.getDeclaredField("pinyinLearningFile");file.setAccessible(true);file.set(dictionary,learningFile);dictionary.flush();
        LocalInputDictionary restored=new LocalInputDictionary();modern.set(restored,SQLiteDatabase.openDatabase(args[1],null,SQLiteDatabase.OPEN_READONLY));file.set(restored,learningFile);java.lang.reflect.Method load=LocalInputDictionary.class.getDeclaredMethod("loadPinyinLearning");load.setAccessible(true);load.invoke(restored);
        check(restored.lookup("kd","").get(0).text.equals("快点"),"personal rank did not survive flush/reopen");check(rank(restored.lookup("nhqy","测试"),"你好轻语")>=0,"new phrase did not survive flush/reopen");check(rank(restored.lookup("nohao","测试"),"拟好")>=0&&rank(restored.lookup("nohaoo","测试"),"拟好")<0,"reopened wrong-spelling aliases ignored three-choice threshold");int savedSize=((java.util.Map<?,?>)coded.get(restored)).size();restored.setLearningEnabled(false);restored.learn("kd","扩大","我");restored.flush();check(((java.util.Map<?,?>)coded.get(restored)).size()==savedSize&&restored.lookup("kd","").get(0).text.equals("快点"),"disabled learning changed persisted rank");
        System.out.println("PERSONALIZATION kd reopened=1; new phrase=你好轻语 / nihaoqingyu / nhqy; private learning unchanged");
        restored.close();Files.deleteIfExists(learningFile.toPath());
        Field cache=LocalInputDictionary.class.getDeclaredField("pinyinCache");cache.setAccessible(true);for(int i=0;i<400;i++)dictionary.lookup("zz"+(char)('a'+i%26)+(char)('a'+i/26),"");check(((java.util.Map<?,?>)cache.get(dictionary)).size()==256,"unbounded query cache");
        LocalInputDictionary bounded=new LocalInputDictionary();for(int i=0;i<2200;i++)bounded.learn("zz"+(char)('a'+i%26)+(char)('a'+i/26%26)+(char)('a'+i/676),"测试","","ce'shi");check(((java.util.Map<?,?>)coded.get(bounded)).size()==2048,"unbounded personal counters");Field index=LocalInputDictionary.class.getDeclaredField("learnedPinyin");index.setAccessible(true);check(((java.util.Map<?,?>)index.get(bounded)).size()<=2048,"pruned counters left unbounded personal word index");bounded.close();
        check(args.length>=4,"Native dictionary and isolated user file are required");segmentedLearning(args,modern,coded,file,load);
        long start=System.nanoTime();
        for(int i=0;i<200;i++) dictionary.suggestNineKey(new String[]{"64426","5485426","64'426","524329426468"}[i%4]);
        System.out.printf("ALL_INPUT_DICTIONARY_CHECKS_PASS: actual Android SQLite, 200 nine-key calls %.2f ms%n",(System.nanoTime()-start)/1e6);
        dictionary.close();
    }
    private static int rank(List<PinyinEngine.Word> words,String text){for(int i=0;i<words.size();i++)if(words.get(i).text.equals(text))return i;return -1;}
    private static Candidate candidate(EngineSnapshot state,String text){for(Candidate c:state.candidates)if(c.text.equals(text))return c;throw new AssertionError("missing segmented candidate "+text);}
    private static String chooseSegmented(PinyinEngine engine){
        EngineSnapshot state=engine.search("woyaokanduanshipin");Candidate prefix=candidate(state,"我要看");check(prefix.id>=0,"fixture must exercise native partial selection");state=engine.select(prefix.id);check(state.committedText.isEmpty(),"partial prefix committed too early");return engine.select(candidate(state,"短视频").id).committedText;
    }
    private static void segmentedLearning(String[] args,Field modern,Field coded,Field file,java.lang.reflect.Method load)throws Exception{
        File user=new File(args[3]),saved=new File(new File(args[1]).getParentFile(),"test-segmented-learning.tsv");Files.deleteIfExists(user.toPath());Files.deleteIfExists(saved.toPath());
        LocalInputDictionary dictionary=new LocalInputDictionary();modern.set(dictionary,SQLiteDatabase.openDatabase(args[1],null,SQLiteDatabase.OPEN_READONLY));if(args.length>4){Field model=LocalInputDictionary.class.getDeclaredField("contextModel");model.setAccessible(true);model.set(dictionary,ChineseContextModel.load(Files.newInputStream(new File(args[4]).toPath())));}file.set(dictionary,saved);PinyinEngine engine=new PinyinEngine();engine.open(args[2],user.getPath());engine.setLexicon(dictionary);
        try{
            check(rank(dictionary.lookup("woyaokanduanshipin",""),"我要看短视频")<0,"fixture needs a new composed sentence");
            EngineSnapshot initial=engine.search("woyaokanduanshipin");Candidate prefix=candidate(initial,"我要看");java.util.Map<?,?> counters=(java.util.Map<?,?>)coded.get(dictionary);java.util.Map<?,?> before=new java.util.LinkedHashMap<>(counters);check(engine.previewCandidate(prefix.id).equals("我要看短视频")&&counters.equals(before),"preview learned composed phrase");
            engine.setLearningEnabled(false);dictionary.setLearningEnabled(false);check(chooseSegmented(engine).equals("我要看短视频")&&counters.equals(before),"private partial selection learned phrase");engine.setLearningEnabled(true);dictionary.setLearningEnabled(true);
            for(int round=1;round<=3;round++){
                check(chooseSegmented(engine).equals("我要看短视频"),"segmented selection lost selected prefix");check(counters.get("duanshipin\t短视频").equals(round)&&counters.get("woyaokanduanshipin\t我要看短视频").equals(round),"suffix or whole sentence counted twice");
                if(round<3)check(rank(dictionary.lookup("woyaokanduanshipin",""),"我要看短视频")<0,"one/two phrase choices became exact");
            }
            check(rank(dictionary.lookup("woyaokanduanshipin",""),"我要看短视频")>=0&&rank(dictionary.lookup("wykdsp",""),"我要看短视频")>=0,"composed sentence missing full/initial aliases");dictionary.flush();
        }finally{engine.close();dictionary.close();Files.deleteIfExists(user.toPath());}
        LocalInputDictionary restored=new LocalInputDictionary();modern.set(restored,SQLiteDatabase.openDatabase(args[1],null,SQLiteDatabase.OPEN_READONLY));file.set(restored,saved);load.invoke(restored);
        try{check(rank(restored.lookup("woyaokanduanshipin",""),"我要看短视频")>=0&&rank(restored.lookup("wykdsp",""),"我要看短视频")>=0,"composed phrase did not survive reopen");System.out.println("PERSONALIZATION segmented 我要看 + 短视频: suffix/whole exactly 3; full/wykdsp persisted; preview/private unchanged");}finally{restored.close();Files.deleteIfExists(saved.toPath());}
    }
}
