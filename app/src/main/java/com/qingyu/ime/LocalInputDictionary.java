package com.qingyu.ime;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.qingyu.core.EnglishEngine;
import com.qingyu.core.NineKeyCandidate;
import com.qingyu.core.PinyinEngine;
import com.qingyu.core.ChineseContextModel;
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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.function.Consumer;

/** Own serial worker: asset installation, queries and learning never run on UI thread. */
final class LocalInputDictionary implements AutoCloseable, PinyinEngine.Lexicon {
    private SQLiteDatabase database;
    private SQLiteDatabase pinyinDatabase;
    private ChineseContextModel contextModel;
    private volatile EnglishEngine english;
    private File learningFile;
    private File chineseLearningFile;
    private File pinyinLearningFile;
    private volatile boolean learningEnabled=true;
    private final LinkedHashMap<String,Integer> chineseLearning=new LinkedHashMap<>();
    private final LinkedHashMap<String,Integer> pinyinLearning=new LinkedHashMap<>();
    private final Map<String,Integer> pinyinReferences=new HashMap<>();
    private final Map<String,LinkedHashMap<String,String>> learnedPinyin=new HashMap<>();
    private final Map<String,List<PinyinRow>> pinyinCache=new LinkedHashMap<>(64,.75f,true);
    private boolean pinyinDirty;
    private final Map<String,List<Row>> nineCache=new LinkedHashMap<>();
    private final Map<String,String> glossCache=new LinkedHashMap<>();
    private final Map<String,String> posCache=new LinkedHashMap<>();

    static LocalInputDictionary open(Context context) throws Exception {
        return open(context,null);
    }
    static LocalInputDictionary open(Context context,Consumer<LocalInputDictionary> chineseReady) throws Exception {
        long start=android.os.SystemClock.uptimeMillis();
        LocalInputDictionary dictionary=new LocalInputDictionary();
        // The filename is the asset schema version; v0.2's installed database
        // cannot be reused because it does not contain lexical tags.
        File target=installAsset(context,"input/input-v3.db","input-v3.db");
        try {
            dictionary.database=SQLiteDatabase.openDatabase(target.getPath(),null,SQLiteDatabase.OPEN_READONLY);
            android.util.Log.i("QingyuData","Input metadata ready in "+(android.os.SystemClock.uptimeMillis()-start)+" ms");
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
            // Optional modern data must never take English/T9 or native pinyin down.
            try{File modern=installAsset(context,"pinyin/lexicon-v2.db","lexicon-v2.db");dictionary.pinyinDatabase=SQLiteDatabase.openDatabase(modern.getPath(),null,SQLiteDatabase.OPEN_READONLY);}catch(Exception ignored){}
            dictionary.pinyinLearningFile=new File(context.getFilesDir(),"pinyin-learning-v1.tsv");
            try{dictionary.loadPinyinLearning();}catch(Exception ignored){} // Malformed personal data is not an input failure.
            android.util.Log.i("QingyuData","Modern Chinese + personal data ready in "+(android.os.SystemClock.uptimeMillis()-start)+" ms");
            long modelStart=android.os.SystemClock.uptimeMillis();
            try{dictionary.contextModel=ChineseContextModel.loadUncompressed(context.getAssets().open("input/chinese-context-v1.bin"));}catch(Exception failure){android.util.Log.w("QingyuData","Chinese context unavailable; native and modern words remain ready",failure);}
            android.util.Log.i("QingyuData","Chinese context phase "+(android.os.SystemClock.uptimeMillis()-modelStart)+" ms; Chinese ready "+(android.os.SystemClock.uptimeMillis()-start)+" ms; context="+dictionary.contextReady());
        } catch(Exception failure) { dictionary.close();throw failure; }
        if(chineseReady!=null)chineseReady.accept(dictionary);
        // The existing worker finishes English separately. Only a fully built
        // and restored engine is published, never its mutating internal maps.
        long englishStart=android.os.SystemClock.uptimeMillis();
        try {
            EnglishEngine loaded=new EnglishEngine(context.getAssets().open("input/english-words.tsv"),context.getAssets().open("input/english-bigrams.tsv"));
            dictionary.learningFile=new File(context.getFilesDir(),"english-learning-v2.tsv");
            if(dictionary.learningFile.exists())try(InputStreamReader in=new InputStreamReader(new FileInputStream(dictionary.learningFile),StandardCharsets.UTF_8)){loaded.loadLearning(in);}
            synchronized(dictionary){loaded.setLearningEnabled(dictionary.learningEnabled);dictionary.english=loaded;}
        }catch(Exception failure){android.util.Log.w("QingyuData","English initialization unavailable; Chinese remains ready",failure);}
        android.util.Log.i("QingyuData","English phase "+(android.os.SystemClock.uptimeMillis()-englishStart)+" ms; total "+(android.os.SystemClock.uptimeMillis()-start)+" ms");
        return dictionary;
    }
    private static File installAsset(Context context,String asset,String name)throws Exception{
        File target=new File(context.getFilesDir(),name);if(target.exists())return target;
        File temp=new File(context.getFilesDir(),name+".tmp");
        try{try(InputStream in=context.getAssets().open(asset);FileOutputStream out=new FileOutputStream(temp)){byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);out.getFD().sync();}if(!temp.renameTo(target))throw new java.io.IOException("Input dictionary installation failed");}finally{if(temp.exists())temp.delete();}return target;
    }
    EnglishEngine english() { return english; }
    synchronized void setLearningEnabled(boolean enabled) {
        learningEnabled=enabled;if(english!=null) english.setLearningEnabled(enabled);
    }

    /** Indexed exact full-pinyin/initials; spelling boundaries remain the engine's choice. */
    @Override public synchronized List<PinyinEngine.Word> lookup(String code,String context){return pinyinSuggestions(code,context,false);}
    @Override public boolean contextReady(){return contextModel!=null;}
    @Override public synchronized double transitionScore(String before,String text){return (contextModel==null?0:contextModel.score(before,text))+personalContextBonus(before,text);}
    @Override public synchronized double transitionScore(String before,PinyinEngine.Word word){
        double value=transitionScore(before,word.text);String tail=chineseTail(before),code=pinyinCode(word.pinyin);
        if(!tail.isEmpty()&&!code.isEmpty())value+=.45*pinyinBonus(pinyinLearning.getOrDefault(code+"\t"+word.text+"\t"+tail,0));
        return value;
    }
    @Override public List<String> predict(String context){return predictChinese(context);}
    @Override public synchronized String sourceExample(String text){return contextModel==null?"":contextModel.example(text);}
    synchronized String example(String text){return sourceExample(text);}
    synchronized List<PinyinEngine.Word> completePinyin(String code,String context){return pinyinSuggestions(code,context,true);}
    private List<PinyinEngine.Word> pinyinSuggestions(String code,String context,boolean prefix){
        String raw=pinyinCode(code);if(raw.isEmpty())return Collections.emptyList();
        String key=(prefix?"p:":"e:")+code.toLowerCase(Locale.ROOT);List<PinyinRow> rows=pinyinCache.get(key);
        if(rows==null){
            LinkedHashMap<String,PinyinRow> found=new LinkedHashMap<>();
            if(pinyinDatabase!=null)try{
                String predicate=prefix?"raw>=? AND raw<?":"raw=?";String[] args=prefix?new String[]{raw,raw+"{"}:new String[]{raw};
                try(Cursor c=pinyinDatabase.rawQuery("SELECT text,pinyin,weight FROM words WHERE "+predicate+" ORDER BY weight DESC LIMIT 64",args)){while(c.moveToNext()){PinyinRow row=new PinyinRow(c.getString(0),c.getString(1),c.getInt(2));if(matchesBoundaries(code,row.pinyin,false))found.put(row.text+"\t"+row.pinyin,row);}}
                if(!prefix)try(Cursor c=pinyinDatabase.rawQuery("SELECT text,pinyin,weight FROM words WHERE initials=? ORDER BY weight DESC LIMIT 64",new String[]{raw})){while(c.moveToNext()){PinyinRow row=new PinyinRow(c.getString(0),c.getString(1),c.getInt(2));if(matchesBoundaries(code,row.pinyin,true))found.putIfAbsent(row.text+"\t"+row.pinyin,row);}}
            }catch(android.database.SQLException ignored){} // Base native input remains available.
            if(!prefix){LinkedHashMap<String,String> learned=learnedPinyin.get(raw);if(learned!=null)for(Map.Entry<String,String> entry:learned.entrySet()){
                String reading=entry.getValue();if(reading.isEmpty()||!matchesBoundaries(code,reading,pinyinInitials(reading).equals(raw)))continue;
                if(!raw.equals(pinyinCode(reading))&&!raw.equals(pinyinInitials(reading))&&pinyinLearning.getOrDefault(raw+"\t"+entry.getKey(),0)<3)continue;
                String identity=entry.getKey()+"\t"+reading;if(found.containsKey(identity))continue;
                PinyinRow row=pinyinRow(entry.getKey(),reading);
                if(row!=null)found.put(identity,row);else if(pinyinLearning.getOrDefault(raw+"\t"+entry.getKey(),0)>=3)found.put(identity,new PinyinRow(entry.getKey(),reading,5000));
            }}
            rows=new ArrayList<>(found.values());pinyinCache.put(key,rows);if(pinyinCache.size()>256)pinyinCache.remove(pinyinCache.keySet().iterator().next());
        }
        String tail=chineseTail(context);Map<String,PinyinEngine.Word> ranked=new HashMap<>();
        for(PinyinRow row:rows){double score=Math.log1p(Math.max(1,row.weight))+pinyinBonus(pinyinLearning.getOrDefault(raw+"\t"+row.text,0));
            if(!tail.isEmpty()){score+=.45*pinyinBonus(pinyinLearning.getOrDefault(raw+"\t"+row.text+"\t"+tail,0));score+=.15*contextBonus(tail,row.text);
                if(contextModel!=null)score+=Math.max(-3,Math.min(3,contextModel.score(tail,row.text)-contextModel.score("",row.text)));}
            PinyinEngine.Word previous=ranked.get(row.text);if(previous==null||score>previous.score)ranked.put(row.text,new PinyinEngine.Word(row.text,row.pinyin,score));
        }
        List<PinyinEngine.Word> result=new ArrayList<>(ranked.values());result.sort((a,b)->{int order=Double.compare(b.score,a.score);return order==0?a.text.compareTo(b.text):order;});if(result.size()>64)result.subList(64,result.size()).clear();return Collections.unmodifiableList(result);
    }
    private static double pinyinBonus(int count){return count<3?0:.8*Math.log1p(count-2);}
    private static String pinyinCode(String code){if(code==null||code.isEmpty()||code.length()>127)return "";String normalized=code.toLowerCase(Locale.ROOT);if(!normalized.matches("[a-z]+(?:'[a-z]+)*"))return "";String raw=normalized.replace("'","");return raw.length()>64?"":raw;}
    private static String pinyinInitials(String reading){if(pinyinCode(reading).isEmpty())return "";StringBuilder initials=new StringBuilder();for(String part:reading.split("'"))initials.append(part.charAt(0));return initials.toString();}
    private static boolean matchesBoundaries(String code,String reading,boolean initials){
        String normalized=code.toLowerCase(Locale.ROOT);if(normalized.indexOf('\'')<0)return true;
        String canonical=reading;if(initials){StringBuilder shortReading=new StringBuilder();for(String part:reading.split("'")){if(shortReading.length()>0)shortReading.append('\'');shortReading.append(part.charAt(0));}canonical=shortReading.toString();}
        int position=0;for(int i=0;i<normalized.length();i++){if(normalized.charAt(i)!='\''){position++;continue;}int letters=0;boolean boundary=false;for(int j=0;j<canonical.length();j++){if(canonical.charAt(j)=='\''){if(letters==position){boundary=true;break;}}else letters++;}if(!boundary)return false;}return true;
    }
    private PinyinRow pinyinRow(String text,String reading){
        if(pinyinDatabase==null)return null;try(Cursor c=pinyinDatabase.rawQuery("SELECT text,pinyin,weight FROM words WHERE raw=? AND text=? AND pinyin=? LIMIT 1",new String[]{pinyinCode(reading),text,reading})){return c.moveToFirst()?new PinyinRow(c.getString(0),c.getString(1),c.getInt(2)):null;}catch(android.database.SQLException ignored){return null;}
    }
    @Override public synchronized void learn(String code,String text,String context){learn(code,text,context,"");}
    @Override public synchronized void learn(String code,String text,String context,String selectedPinyin){
        String raw=pinyinCode(code);if(!learningEnabled||raw.isEmpty()||text==null||!text.matches("[\u3400-\u9fff]{1,64}"))return;
        String reading=pinyinCode(selectedPinyin).isEmpty()?"":selectedPinyin.toLowerCase(Locale.ROOT);
        if(reading.isEmpty()&&pinyinDatabase!=null)try{
            try(Cursor c=pinyinDatabase.rawQuery("SELECT pinyin FROM words WHERE raw=? AND text=? ORDER BY weight DESC LIMIT 1",new String[]{raw,text})){if(c.moveToFirst())reading=c.getString(0);}
            if(reading.isEmpty())try(Cursor c=pinyinDatabase.rawQuery("SELECT pinyin FROM words WHERE initials=? AND text=? ORDER BY weight DESC LIMIT 1",new String[]{raw,text})){if(c.moveToFirst())reading=c.getString(0);}
            if(reading.isEmpty())try(Cursor c=pinyinDatabase.rawQuery("SELECT pinyin FROM words WHERE text=? ORDER BY weight DESC LIMIT 1",new String[]{text})){if(c.moveToFirst())reading=c.getString(0);}
        }catch(android.database.SQLException ignored){}
        if(reading.isEmpty()&&(code.indexOf('\'')>=0||text.length()==1))reading=code.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> codes=new LinkedHashSet<>();codes.add(raw);String canonical=pinyinCode(reading);if(!canonical.isEmpty()){codes.add(canonical);codes.add(pinyinInitials(reading));}
        String tail=chineseTail(context);for(String alias:codes){learnPinyinKey(alias+"\t"+text);if(!tail.isEmpty())learnPinyinKey(alias+"\t"+text+"\t"+tail);indexPinyin(alias,text,reading);}
        pinyinCache.clear();pinyinDirty=true;
    }
    private static String codeWord(String key){int split=key.indexOf('\t',key.indexOf('\t')+1);return split<0?key:key.substring(0,split);}
    private void learnPinyinKey(String key){int count=pinyinLearning.getOrDefault(key,0);if(count==0)pinyinReferences.merge(codeWord(key),1,Integer::sum);pinyinLearning.remove(key);pinyinLearning.put(key,Math.min(1000,count+1));while(pinyinLearning.size()>2048){String removed=pinyinLearning.keySet().iterator().next();pinyinLearning.remove(removed);String identity=codeWord(removed);int refs=pinyinReferences.getOrDefault(identity,1)-1;if(refs>0)pinyinReferences.put(identity,refs);else{pinyinReferences.remove(identity);String[] parts=identity.split("\t",2);Map<String,String> words=learnedPinyin.get(parts[0]);if(words!=null){words.remove(parts[1]);if(words.isEmpty())learnedPinyin.remove(parts[0]);}}}}
    private void indexPinyin(String code,String text,String reading){LinkedHashMap<String,String> words=learnedPinyin.computeIfAbsent(code,ignored->new LinkedHashMap<>());words.remove(text);words.put(text,reading);if(words.size()>32)words.remove(words.keySet().iterator().next());}
    private void loadPinyinLearning()throws Exception{
        if(pinyinLearningFile==null||!pinyinLearningFile.isFile()||pinyinLearningFile.length()>1024*1024)return;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(pinyinLearningFile),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null&&pinyinLearning.size()<2048){String[] fields=line.split("\t",-1);if(fields.length!=4&&fields.length!=5)continue;String code=pinyinCode(fields[0]),word=fields[1],context=fields.length==5?fields[2]:"",reading=fields[fields.length-2];if(code.isEmpty()||!code.equals(fields[0])||!word.matches("[\u3400-\u9fff]{1,64}")||!context.matches("[\u3400-\u9fff]{0,6}")||!reading.isEmpty()&&pinyinCode(reading).isEmpty())continue;try{int count=Integer.parseInt(fields[fields.length-1]);if(count<=0)continue;String key=code+"\t"+word+(context.isEmpty()?"":"\t"+context);if(!pinyinLearning.containsKey(key))pinyinReferences.merge(codeWord(key),1,Integer::sum);pinyinLearning.put(key,Math.min(1000,count));indexPinyin(code,word,reading);}catch(NumberFormatException ignored){}}}
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
    /** Batch lookup on the existing data/gloss worker; unknown words stay neutral. */
    synchronized Map<String,String> partsOfSpeech(List<String> words) {
        if(database==null||words==null||words.isEmpty())return Collections.emptyMap();
        List<String> missing=new ArrayList<>();Map<String,String> result=new HashMap<>();
        for(String word:words) {
            if(word==null||word.isEmpty()||word.length()>64||missing.size()>=128)continue;
            String cached=posCache.get(word);
            if(cached!=null){if(!cached.isEmpty())result.put(word,cached);}
            else if(!missing.contains(word))missing.add(word);
        }
        if(!missing.isEmpty()) {
            String placeholders=String.join(",",Collections.nCopies(missing.size(),"?"));
            try(Cursor cursor=database.rawQuery("SELECT text,pos FROM chinese WHERE text IN ("+placeholders+") AND pos<>''",missing.toArray(new String[0]))) {
                while(cursor.moveToNext())result.put(cursor.getString(0),cursor.getString(1));
                for(String word:missing)posCache.put(word,result.getOrDefault(word,""));
                while(posCache.size()>1024)posCache.remove(posCache.keySet().iterator().next());
            }catch(android.database.SQLException ignored){return Collections.emptyMap();}
        }
        return result;
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

    synchronized List<String> predictChinese(String context) {
        String tail=chineseTail(context);if(tail.isEmpty()) return Collections.emptyList();
        Map<String,Double> scores=new HashMap<>();
        for(String key:chineseLearning.keySet()) {
            String[] fields=key.split("\t");
            int count=chineseLearning.get(key);
            if(fields.length==2 && tail.endsWith(fields[0])&&count>=3) scores.merge(fields[1],500.0+Math.log1p(count-2)*25,Math::max);
        }
        if(contextModel!=null){List<String> observed=contextModel.predict(context);for(int i=0;i<observed.size();i++)scores.merge(observed.get(i),300.0-i*5,Math::max);}
        for(int size=Math.min(6,tail.length());size>0;size--) {
            String prefix=tail.substring(tail.length()-size);
            String curated=PAIRS.get(prefix);
            if(curated!=null) {
                String[] words=curated.split(" ");
                for(int index=0;index<words.length;index++) scores.merge(words[index],250.0-index*5+size*15,Math::max);
            }
            // The audited legacy phrase pairs remain useful for everyday chat.
            // Arbitrary dictionary-prefix tails are not evidence of a next word.
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
            bonus=Math.max(bonus,Math.min(10,pinyinBonus(chineseLearning.getOrDefault(prefix+"\t"+word,0))*3));
        }
        return bonus;
    }
    private double personalContextBonus(String context,String word){String tail=chineseTail(context);double result=0;for(int n=1;n<=tail.length();n++)result=Math.max(result,pinyinBonus(chineseLearning.getOrDefault(tail.substring(tail.length()-n)+"\t"+word,0)));return Math.min(4,result);}
    private static String chineseTail(String context) {
        if(context==null) return "";
        int end=context.length();while(end>0 && Character.isWhitespace(context.charAt(end-1))) end--;
        int start=end;while(start>0 && end-start<6 && context.charAt(start-1)>='\u3400' && context.charAt(start-1)<='\u9fff') start--;
        return context.substring(start,end);
    }
    synchronized void flush() throws Exception {
        if(english!=null && learningFile!=null) {
        File temp=new File(learningFile.getParentFile(),"english-learning-v2.tmp");
        try(FileOutputStream bytes=new FileOutputStream(temp);OutputStreamWriter out=new OutputStreamWriter(bytes,StandardCharsets.UTF_8)) {
            english.saveLearning(out);out.flush();bytes.getFD().sync();
        }
        java.nio.file.Files.move(temp.toPath(),learningFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if(chineseLearningFile!=null) {
            File chineseTemp=new File(chineseLearningFile.getParentFile(),"chinese-learning-v2.tmp");
            try(FileOutputStream bytes=new FileOutputStream(chineseTemp);OutputStreamWriter out=new OutputStreamWriter(bytes,StandardCharsets.UTF_8)) {
                for(Map.Entry<String,Integer> entry:chineseLearning.entrySet()) out.write(entry.getKey()+"\t"+entry.getValue()+"\n");
                out.flush();bytes.getFD().sync();
            }
            java.nio.file.Files.move(chineseTemp.toPath(),chineseLearningFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if(pinyinLearningFile!=null&&pinyinDirty){
            File temp=new File(pinyinLearningFile.getParentFile(),"pinyin-learning-v1.tmp");
            try(FileOutputStream bytes=new FileOutputStream(temp);OutputStreamWriter out=new OutputStreamWriter(bytes,StandardCharsets.UTF_8)){for(Map.Entry<String,Integer> entry:pinyinLearning.entrySet()){String[] parts=entry.getKey().split("\t");Map<String,String> words=learnedPinyin.get(parts[0]);String reading=words==null?"":words.getOrDefault(parts[1],"");out.write(entry.getKey()+"\t"+reading+"\t"+entry.getValue()+"\n");}out.flush();bytes.getFD().sync();}
            java.nio.file.Files.move(temp.toPath(),pinyinLearningFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);pinyinDirty=false;
        }
    }
    @Override public synchronized void close() {
        try{flush();}catch(Exception ignored){} // Learning persistence can never suppress input.
        if(database!=null) {database.close();database=null;}
        if(pinyinDatabase!=null){pinyinDatabase.close();pinyinDatabase=null;}
        contextModel=null;english=null;nineCache.clear();glossCache.clear();posCache.clear();chineseLearning.clear();pinyinCache.clear();pinyinLearning.clear();pinyinReferences.clear();learnedPinyin.clear();
    }
    private static final class PinyinRow{final String text,pinyin;final int weight;PinyinRow(String text,String pinyin,int weight){this.text=text;this.pinyin=pinyin;this.weight=weight;}}
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
