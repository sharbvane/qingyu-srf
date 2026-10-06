package com.qingyu.ime;

import android.database.sqlite.SQLiteDatabase;
import com.qingyu.core.NineKeyCandidate;
import java.lang.reflect.Field;
import java.util.List;

/** Android SQLite checks through app_process; no application test hooks. */
public final class InputDictionarySmoke {
    private static void check(boolean condition,String label) {if(!condition) throw new AssertionError(label);}
    public static void main(String[] args) throws Exception {
        LocalInputDictionary dictionary=new LocalInputDictionary();
        Field field=LocalInputDictionary.class.getDeclaredField("database");field.setAccessible(true);
        field.set(dictionary,SQLiteDatabase.openDatabase(args[0],null,SQLiteDatabase.OPEN_READONLY));
        List<NineKeyCandidate> hello=dictionary.suggestNineKey("64426");
        check(hello.get(0).text.equals("你好") && hello.get(0).consumedDigits==5,"common nine-key exact reading");
        check(dictionary.suggestNineKey("52432").get(0).text.equals("开发"),"real full word dictionary");
        List<NineKeyCandidate> project=dictionary.suggestNineKey("524329426468");
        System.out.println("Nine-key sentence first="+project.get(0).text+" pinyin="+project.get(0).pinyin+" digits="+project.get(0).consumedDigits);
        check(project.get(0).text.equals("开发项目"),"multiword nine-key segmentation");
        check(dictionary.suggestNineKey("644").stream().anyMatch(candidate->candidate.text.equals("你好")),"partial pinyin completion");
        check(dictionary.suggestNineKey("111").isEmpty(),"digit trust boundary");
        String longDigits="64426".repeat(8);
        List<NineKeyCandidate> longSentence=dictionary.suggestNineKey(longDigits);
        check(!longSentence.isEmpty() && longSentence.get(0).consumedDigits==40,"long digit sentence retains every input key");
        check(dictionary.lookupEnglish("hello").equals("你好"),"reverse Chinese gloss");
        check(dictionary.lookupEnglish("developers").contains("开发"),"reverse gloss inflection");
        check(dictionary.predictChinese("中国").contains("人民"),"Chinese context continuation");
        dictionary.setLearningEnabled(false);dictionary.learnChinese("绝密内容","上下文");
        Field learning=LocalInputDictionary.class.getDeclaredField("chineseLearning");learning.setAccessible(true);
        check(((java.util.Map<?,?>)learning.get(dictionary)).isEmpty(),"private Chinese input disables learning");
        dictionary.setLearningEnabled(true);
        for(int i=0;i<10;i++) dictionary.learnChinese("轻语","测试");
        check(dictionary.predictChinese("测试").get(0).equals("轻语"),"personal context continuation");
        long start=System.nanoTime();
        for(int i=0;i<200;i++) dictionary.suggestNineKey(new String[]{"64426","52432","9426468","524329426468"}[i%4]);
        System.out.printf("ALL_INPUT_DICTIONARY_CHECKS_PASS: actual Android SQLite, 200 nine-key calls %.2f ms%n",(System.nanoTime()-start)/1e6);
        dictionary.close();
    }
}
