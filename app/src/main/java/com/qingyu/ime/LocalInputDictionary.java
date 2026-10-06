package com.qingyu.ime;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.qingyu.core.EnglishEngine;
import com.qingyu.core.NineKeyCandidate;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Own serial worker: asset installation, queries and learning never run on UI thread. */
final class LocalInputDictionary implements AutoCloseable {
    private SQLiteDatabase database;
    private EnglishEngine english;
    private File learningFile;
    private File chineseLearningFile;
    private boolean learningEnabled=true;
    private final LinkedHashMap<String,Integer> chineseLearning=new LinkedHashMap<>();
    private final Map<String,List<Row>> nineCache=new LinkedHashMap<>();
    private final Map<String,String> glossCache=new LinkedHashMap<>();

    static LocalInputDictionary open(Context context) throws Exception {
        LocalInputDictionary dictionary=new LocalInputDictionary();
        File target=new File(context.getFilesDir(),"input-v2.db");
        if(!target.exists()) {
            File temp=new File(context.getFilesDir(),"input-v2.tmp");
            try(InputStream in=context.getAssets().open("input/input-v2.db");FileOutputStream out=new FileOutputStream(temp)) {
                byte[] buffer=new byte[32768];int count;
                while((count=in.read(buffer))!=-1) out.write(buffer,0,count);
                out.getFD().sync();
            }
            if(!temp.renameTo(target)) throw new java.io.IOException("Input dictionary installation failed");
        }
        try {
            dictionary.database=SQLiteDatabase.openDatabase(target.getPath(),null,SQLiteDatabase.OPEN_READONLY);
            dictionary.english=new EnglishEngine(context.getAssets().open("input/english-words.tsv"),context.getAssets().open("input/english-bigrams.tsv"));
            dictionary.learningFile=new File(context.getFilesDir(),"english-learning-v2.tsv");
            if(dictionary.learningFile.exists()) try(InputStreamReader in=new InputStreamReader(new FileInputStream(dictionary.learningFile),StandardCharsets.UTF_8)) {
                dictionary.english.loadLearning(in);
            }
            dictionary.chineseLearningFile=new File(context.getFilesDir(),"chinese-learning-v2.tsv");
            if(dictionary.chineseLearningFile.exists()) try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(dictionary.chineseLearningFile),StandardCharsets.UTF_8))) {
                String line;
                while((line=in.readLine())!=null && dictionary.chineseLearning.size()<2048) {
                    String[] fields=line.split("\t");
                    String key="";String number="";
                    if(fields.length==2 && fields[0].matches("[\u3400-\u9fff]{1,24}")) {key=fields[0];number=fields[1];}
                    else if(fields.length==3 && fields[0].matches("[\u3400-\u9fff]{1,6}") && fields[1].matches("[\u3400-\u9fff]{1,24}")) {key=fields[0]+"\t"+fields[1];number=fields[2];}
                    try {if(!key.isEmpty()) dictionary.chineseLearning.put(key,Math.max(1,Math.min(1000,Integer.parseInt(number))));}
                    catch(NumberFormatException ignored) { }
                }
            }
            return dictionary;
        } catch(Exception failure) { dictionary.close();throw failure; }
    }
    EnglishEngine english() { return english; }
    void setLearningEnabled(boolean enabled) {
        learningEnabled=enabled;if(english!=null) english.setLearningEnabled(enabled);
    }

    synchronized String lookupEnglish(String word) {
        String key=word==null?"":word.toLowerCase(java.util.Locale.ROOT);
        if(database==null || key.isEmpty() || key.length()>64) return "";
        if(glossCache.containsKey(key)) return glossCache.get(key);
        String answer=lookupExact(key);
        if(answer.isEmpty()) {
            List<String> stems=new ArrayList<>();
            if(key.endsWith("ies") && key.length()>4) stems.add(key.substring(0,key.length()-3)+"y");
            for(String suffix:new String[]{"ing","ed","es","s"}) if(key.endsWith(suffix) && key.length()>suffix.length()+2) {
                String stem=key.substring(0,key.length()-suffix.length());stems.add(stem);stems.add(stem+"e");
                if(stem.length()>2 && stem.charAt(stem.length()-1)==stem.charAt(stem.length()-2)) stems.add(stem.substring(0,stem.length()-1));
            }
            for(String stem:stems) {answer=lookupExact(stem);if(!answer.isEmpty()) break;}
        }
        glossCache.put(key,answer);
        if(glossCache.size()>1024) glossCache.remove(glossCache.keySet().iterator().next());
        return answer;
    }
    private String lookupExact(String word) {
        try(Cursor cursor=database.rawQuery("SELECT zh FROM english_gloss WHERE word=?",new String[]{word})) {
            return cursor.moveToFirst()?cursor.getString(0):"";
        }
    }

    List<NineKeyCandidate> suggestNineKey(String digits) {
        if(database==null || digits==null || digits.isEmpty() || digits.length()>64 || !digits.matches("[2-9]+")) return Collections.emptyList();
        LinkedHashMap<String,NineKeyCandidate> result=new LinkedHashMap<>();
        List<Row> exact=rows(digits);
        List<Row> ranked=new ArrayList<>(exact);
        ranked.sort((a,b)->Double.compare(rowScore(b),rowScore(a)));
        for(Row row:ranked) result.put(row.text,new NineKeyCandidate(row.text,row.pinyin,digits.length()));
        if(exact.isEmpty() && digits.length()>=8) {
            NineKeyCandidate sentence=compose(digits);
            if(sentence!=null) result.put(sentence.text,sentence);
        }
        // Complete partial syllables without inventing a digit mapping.
        try(Cursor c=database.rawQuery("SELECT text,pinyin,digits FROM nine WHERE digits>=? AND digits<? ORDER BY weight DESC LIMIT 48",new String[]{digits,digits+":"})) {
            while(c.moveToNext()) {
                String text=c.getString(0);
                result.putIfAbsent(text,new NineKeyCandidate(text,c.getString(1),digits.length()));
            }
        }
        // A phrase may end before the rest of a sentence. Its exact consumed
        // count lets the service retain and decode every remaining key.
        for(int size=Math.min(24,digits.length()-1);size>=2 && result.size()<64;size--) {
            for(Row row:rows(digits.substring(0,size))) result.putIfAbsent(row.text,new NineKeyCandidate(row.text,row.pinyin,size));
        }
        List<NineKeyCandidate> candidates=new ArrayList<>(result.values());
        return Collections.unmodifiableList(candidates.subList(0,Math.min(64,candidates.size())));
    }

    private List<Row> rows(String digits) {
        List<Row> cached=nineCache.get(digits);if(cached!=null) return cached;
        List<Row> found=new ArrayList<>();
        try(Cursor c=database.rawQuery("SELECT text,pinyin,weight FROM nine WHERE digits=? ORDER BY weight DESC LIMIT 16",new String[]{digits})) {
            while(c.moveToNext()) found.add(new Row(c.getString(0),c.getString(1),c.getInt(2)));
        }
        nineCache.put(digits,found);
        if(nineCache.size()>256) nineCache.remove(nineCache.keySet().iterator().next());
        return found;
    }
    private NineKeyCandidate compose(String digits) {
        // ponytail: bounded unigram segmentation, not a neural T9 language
        // model; replace ranking when a measured ambiguity corpus warrants it.
        int length=digits.length();
        double[] score=new double[length+1];java.util.Arrays.fill(score,Double.NEGATIVE_INFINITY);score[0]=0;
        String[] text=new String[length+1],pinyin=new String[length+1];text[0]="";pinyin[0]="";
        for(int start=0;start<length;start++) {
            if(text[start]==null) continue;
            for(int end=start+2;end<=Math.min(length,start+24);end++) {
                List<Row> possible=rows(digits.substring(start,end));
                if(possible.isEmpty()) continue;
                Row row=possible.get(0);
                for(Row option:possible) if(rowScore(option)+contextBonus(text[start],option.text)>rowScore(row)+contextBonus(text[start],row.text)) row=option;
                // A unigram probability penalty favors a few common words;
                // the existing continuation map resolves everyday collisions.
                double value=score[start]+rowScore(row)-15+contextBonus(text[start],row.text);
                if(value>score[end]) {
                    score[end]=value;text[end]=text[start]+row.text;
                    pinyin[end]=pinyin[start]+(pinyin[start].isEmpty()?"":"'")+row.pinyin;
                }
            }
        }
        return text[length]==null?null:new NineKeyCandidate(text[length],pinyin[length],length);
    }

    List<String> predictChinese(String context) {
        if(database==null) return Collections.emptyList();
        String tail=chineseTail(context);if(tail.isEmpty()) return Collections.emptyList();
        Map<String,Double> scores=new HashMap<>();
        for(String key:chineseLearning.keySet()) {
            String[] fields=key.split("\t");
            if(fields.length==2 && tail.endsWith(fields[0])) scores.merge(fields[1],500.0+chineseLearning.get(key)*25,Math::max);
        }
        for(int size=Math.min(6,tail.length());size>0;size--) {
            String prefix=tail.substring(tail.length()-size);
            String curated=PAIRS.get(prefix);
            if(curated!=null) {
                String[] words=curated.split(" ");
                for(int index=0;index<words.length;index++) scores.merge(words[index],250.0-index*5+size*15,Math::max);
            }
            try(Cursor c=database.rawQuery("SELECT text,weight FROM chinese WHERE text>=? AND text<? ORDER BY weight DESC LIMIT 24",new String[]{prefix,prefix+"\uffff"})) {
                while(c.moveToNext()) {
                    String whole=c.getString(0);if(whole.length()<=prefix.length()) continue;
                    String rest=whole.substring(prefix.length());
                    scores.merge(rest,Math.log1p(c.getInt(1))*15+size*20,Math::max);
                }
            }
        }
        List<String> result=new ArrayList<>(scores.keySet());
        result.sort((a,b)->{int difference=Double.compare(scores.get(b),scores.get(a));return difference==0?a.compareTo(b):difference;});
        if(result.size()>12) result.subList(12,result.size()).clear();
        return Collections.unmodifiableList(result);
    }
    void learnChinese(String word,String context) {
        if(!learningEnabled || word==null || !word.matches("[\u3400-\u9fff]{1,24}")) return;
        learnChineseKey(word);
        String tail=chineseTail(context);if(!tail.isEmpty()) learnChineseKey(tail+"\t"+word);
    }
    private void learnChineseKey(String key) {
        int previous=chineseLearning.getOrDefault(key,0);
        chineseLearning.remove(key);chineseLearning.put(key,Math.min(1000,previous+1));
        if(chineseLearning.size()>2048) chineseLearning.remove(chineseLearning.keySet().iterator().next());
    }
    private double rowScore(Row row) {return Math.log1p(row.weight)+Math.log1p(chineseLearning.getOrDefault(row.text,0))*2;}
    private double contextBonus(String context,String word) {
        String tail=chineseTail(context);double bonus=0;
        for(int length=1;length<=tail.length();length++) {
            String prefix=tail.substring(tail.length()-length),following=PAIRS.get(prefix);
            if(following!=null && java.util.Arrays.asList(following.split(" ")).contains(word)) bonus=Math.max(bonus,4.5+length*.1);
            bonus=Math.max(bonus,Math.min(10,Math.log1p(chineseLearning.getOrDefault(prefix+"\t"+word,0))*3));
        }
        return bonus;
    }
    private static String chineseTail(String context) {
        if(context==null) return "";
        int end=context.length();while(end>0 && Character.isWhitespace(context.charAt(end-1))) end--;
        int start=end;while(start>0 && end-start<6 && context.charAt(start-1)>='\u3400' && context.charAt(start-1)<='\u9fff') start--;
        return context.substring(start,end);
    }
    void flush() throws Exception {
        if(english==null || learningFile==null) return;
        File temp=new File(learningFile.getParentFile(),"english-learning-v2.tmp");
        try(FileOutputStream bytes=new FileOutputStream(temp);OutputStreamWriter out=new OutputStreamWriter(bytes,StandardCharsets.UTF_8)) {
            english.saveLearning(out);out.flush();bytes.getFD().sync();
        }
        java.nio.file.Files.move(temp.toPath(),learningFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if(chineseLearningFile!=null) {
            File chineseTemp=new File(chineseLearningFile.getParentFile(),"chinese-learning-v2.tmp");
            try(FileOutputStream bytes=new FileOutputStream(chineseTemp);OutputStreamWriter out=new OutputStreamWriter(bytes,StandardCharsets.UTF_8)) {
                for(Map.Entry<String,Integer> entry:chineseLearning.entrySet()) out.write(entry.getKey()+"\t"+entry.getValue()+"\n");
                out.flush();bytes.getFD().sync();
            }
            java.nio.file.Files.move(chineseTemp.toPath(),chineseLearningFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
    @Override public synchronized void close() {
        try{flush();}catch(Exception ignored){} // Learning persistence can never suppress input.
        if(database!=null) {database.close();database=null;}
        english=null;nineCache.clear();glossCache.clear();chineseLearning.clear();
    }
    private static final class Row {
        final String text,pinyin;final int weight;
        Row(String text,String pinyin,int weight) {this.text=text;this.pinyin=pinyin;this.weight=weight;}
    }
    private static final Map<String,String> PAIRS=new HashMap<>();
    static {
        String[][] pairs={{"你好","呀 朋友 很高兴"},{"谢谢","你 您 大家 帮助"},{"我","是 想 要 在 觉得 喜欢 可以"},
            {"我是","一个 学生 中国人"},{"我想","去 要 学习 问"},{"你","好 是 在 觉得 可以"},{"你是","谁 一个"},
            {"我们","一起 去 可以 需要"},{"今天","晚上 天气 很 想"},{"明天","见 早上 一起"},{"晚上","好 吃饭 见"},
            {"早上","好 起床 吃饭"},{"中国","人民 文化 语言"},{"工作","顺利 计划 时间"},{"项目","开发 设计 进度"},
            {"开发","项目 软件 应用"},{"学习","英语 中文 编程"},{"喜欢","你 这个 学习"},{"可以","的 吗 帮忙"},
            {"请","问 帮我 稍等"},{"请问","一下 您"},{"请帮我","看看 翻译"},{"需要","帮助 时间 更新"},
            {"一起","去 吃饭 学习"},{"很","好 开心 高兴"},{"非常","好 感谢 高兴"},{"好的","谢谢 明白"},
            {"再见","朋友 明天见"},{"生日","快乐"},{"新年","快乐"},{"祝你","快乐 成功 好运"},{"没","关系 问题"},
            {"没有","问题 时间"},{"输入","法 文字 内容"},{"轻语","输入法"},{"打字","体验 速度"},{"打开","设置 文件"},
            {"发送","消息 邮件 文件"},{"收到","了 谢谢"},{"现在","就去 可以 正在"},{"什么时候","见面 开始 回来"}};
        for(String[] pair:pairs) PAIRS.put(pair[0],pair[1]);
    }
}
