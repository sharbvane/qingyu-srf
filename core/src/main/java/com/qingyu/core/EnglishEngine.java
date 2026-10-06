package com.qingyu.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline completion, spelling suggestions and contextual next words. One worker only. */
public final class EnglishEngine {
    private static final Pattern TOKEN = Pattern.compile("[a-z]+(?:'[a-z]+)?");
    private static final int LIMIT = 12;
    private final Map<String,Integer> frequencies = new HashMap<>();
    private final Map<String,Map<String,Integer>> next = new HashMap<>();
    private final LinkedHashMap<String,Integer> learned = new LinkedHashMap<>();
    private final String[] sortedWords;
    private final List<String> common;
    private boolean learningEnabled = true;

    public EnglishEngine(InputStream wordsTsv, InputStream bigramsTsv) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(wordsTsv, StandardCharsets.UTF_8))) {
            String line;
            while ((line=in.readLine())!=null) {
                String[] fields=line.split("\t");
                if(fields.length==2 && valid(fields[0])) frequencies.put(fields[0],positive(fields[1]));
            }
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(bigramsTsv, StandardCharsets.UTF_8))) {
            String line;
            while((line=in.readLine())!=null) {
                String[] fields=line.split("\t");
                if(fields.length==3 && valid(fields[0]) && valid(fields[1]))
                    next.computeIfAbsent(fields[0],ignored->new HashMap<>()).put(fields[1],positive(fields[2]));
            }
        }
        sortedWords=frequencies.keySet().toArray(new String[0]);
        Arrays.sort(sortedWords);
        common=new ArrayList<>(frequencies.keySet());
        common.sort((a,b)->Integer.compare(frequencies.get(b),frequencies.get(a)));
        if(common.size()>5000) common.subList(5000,common.size()).clear();
    }

    /** Suggestions never replace entered text implicitly; caller chooses what to commit. */
    public List<String> suggest(String prefix, String context) {
        String original=prefix==null?"":prefix;
        String query=original.toLowerCase(Locale.ROOT);
        if(!query.isEmpty() && !valid(query)) return Collections.singletonList(original);
        String previous=lastWord(context);
        Map<String,Double> scores=new HashMap<>();
        if(query.isEmpty()) {
            for(String word:next.getOrDefault(previous,Collections.emptyMap()).keySet()) add(scores,word,previous,0);
            for(String key:learned.keySet()) {
                String[] fields=key.split("\t");
                if(fields.length==2 && fields[0].equals(previous)) add(scores,fields[1],previous,0);
            }
            for(int i=0;i<Math.min(40,common.size());i++) add(scores,common.get(i),previous,0);
        } else {
            int start=Arrays.binarySearch(sortedWords,query);
            if(start<0) start=-start-1;
            // ponytail: linear scan of a prefix range on the serial worker;
            // replace with top-prefix buckets only if measured typing latency warrants it.
            for(int i=start;i<sortedWords.length && sortedWords[i].startsWith(query);i++)
                add(scores,sortedWords[i],previous,sortedWords[i].equals(query)?120:0);
            for(String key:learned.keySet()) if(key.indexOf('\t')<0 && key.startsWith(query)) add(scores,key,previous,30);
            if(query.length()>=3) {
                for(String word:oneEdit(query)) if(frequencies.containsKey(word) || learned.containsKey(word)) add(scores,word,previous,-45);
                if(scores.size()<3 && query.length()>=5) {
                    for(String word:common) if(Math.abs(word.length()-query.length())<=2 && distanceAtMostTwo(query,word)<=2)
                        add(scores,word,previous,-90);
                }
            }
        }
        List<String> result=new ArrayList<>(scores.keySet());
        result.sort((a,b)->{int compare=Double.compare(scores.get(b),scores.get(a));return compare==0?a.compareTo(b):compare;});
        if(result.size()>LIMIT) result.subList(LIMIT,result.size()).clear();
        if(!query.isEmpty() && !result.contains(query)) {
            if(result.size()==LIMIT) result.remove(result.size()-1);
            result.add(query); // Always allow a new name or intentional spelling.
        }
        for(int i=0;i<result.size();i++) result.set(i,caseLike(result.get(i),original));
        return Collections.unmodifiableList(result);
    }

    public void setLearningEnabled(boolean enabled) { learningEnabled=enabled; }
    public void learn(String word, String context) {
        if(!learningEnabled) return;
        String normalized=word==null?"":word.toLowerCase(Locale.ROOT);
        if(!valid(normalized)) return;
        increment(normalized);
        String previous=lastWord(context);
        if(!previous.isEmpty()) increment(previous+"\t"+normalized);
    }

    /** TSV numeric counters only: no complete editor text is retained. */
    public void loadLearning(Reader reader) throws IOException {
        BufferedReader in=new BufferedReader(reader);
        String line;
        while((line=in.readLine())!=null && learned.size()<4096) {
            String[] fields=line.split("\t");
            if(fields.length==2 && valid(fields[0])) learned.put(fields[0],Math.min(1000,positive(fields[1])));
            else if(fields.length==3 && valid(fields[0]) && valid(fields[1])) learned.put(fields[0]+"\t"+fields[1],Math.min(1000,positive(fields[2])));
        }
    }
    public void saveLearning(Writer writer) throws IOException {
        for(Map.Entry<String,Integer> entry:learned.entrySet()) writer.write(entry.getKey()+"\t"+entry.getValue()+"\n");
        writer.flush();
    }
    public int wordCount() { return frequencies.size(); }

    private void increment(String key) {
        int value=learningCount(key);
        learned.remove(key); // Recent entries survive the bounded history.
        learned.put(key,Math.min(1000,value+1));
        while(learned.size()>4096) learned.remove(learned.keySet().iterator().next());
    }
    private int learningCount(String key) { return learned.getOrDefault(key,0); }
    private void add(Map<String,Double> scores,String word,String previous,double extra) {
        double score=20*Math.log1p(frequencies.getOrDefault(word,1))+extra;
        score+=55*Math.log1p(next.getOrDefault(previous,Collections.emptyMap()).getOrDefault(word,0));
        score+=25*Math.log1p(learningCount(word))+85*Math.log1p(learningCount(previous+"\t"+word));
        scores.merge(word,score,Math::max);
    }
    private static List<String> oneEdit(String word) {
        List<String> out=new ArrayList<>(word.length()*55);
        for(int i=0;i<word.length();i++) {
            out.add(word.substring(0,i)+word.substring(i+1));
            if(i+1<word.length()) out.add(word.substring(0,i)+word.charAt(i+1)+word.charAt(i)+word.substring(i+2));
            for(char ch='a';ch<='z';ch++) out.add(word.substring(0,i)+ch+word.substring(i+1));
        }
        for(int i=0;i<=word.length();i++) for(char ch='a';ch<='z';ch++) out.add(word.substring(0,i)+ch+word.substring(i));
        return out;
    }
    private static int distanceAtMostTwo(String a,String b) {
        int[] row=new int[b.length()+1];
        for(int j=0;j<row.length;j++) row[j]=j;
        for(int i=1;i<=a.length();i++) {
            int diagonal=row[0];row[0]=i;int minimum=i;
            for(int j=1;j<=b.length();j++) {
                int above=row[j];
                row[j]=Math.min(Math.min(above+1,row[j-1]+1),diagonal+(a.charAt(i-1)==b.charAt(j-1)?0:1));
                diagonal=above;minimum=Math.min(minimum,row[j]);
            }
            if(minimum>2) return 3;
        }
        return row[b.length()];
    }
    private static String lastWord(String context) {
        if(context==null || context.isEmpty()) return "";
        String tail=context.substring(Math.max(0,context.length()-128)).toLowerCase(Locale.ROOT);
        Matcher matcher=TOKEN.matcher(tail);String previous="";
        while(matcher.find()) previous=matcher.group();
        return previous;
    }
    private static boolean valid(String word) { return word.length()<=48 && TOKEN.matcher(word).matches(); }
    private static int positive(String number) {
        try{return Math.max(1,Integer.parseInt(number));}catch(NumberFormatException ignored){return 1;}
    }
    private static String caseLike(String word,String prefix) {
        if(word.equals("i")) return "I";
        if(prefix.length()>1 && prefix.equals(prefix.toUpperCase(Locale.ROOT))) return word.toUpperCase(Locale.ROOT);
        if(!prefix.isEmpty() && Character.isUpperCase(prefix.charAt(0))) return Character.toUpperCase(word.charAt(0))+word.substring(1);
        return word;
    }
}
