package com.qingyu.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Small offline corpus statistics. No network, SQL, training or text generation. */
public final class ChineseContextModel {
    private static final int TAGS=17, UNKNOWN=0, VERB=3;
    private final int[] unigrams=new int[65536];
    private final Table bigrams,trigrams;
    private final double totalCharacters;
    private final Map<String,Integer> tags;
    private final int[][] transitions;
    private final int[] classTotals=new int[TAGS],classNext=new int[TAGS];
    private int transitionTotal;
    private final Map<String,Prediction> continuations;
    private final Map<String,String> examples;
    private final String source;
    // Worker-local, bounded memoization of word-internal character scores.
    private final Map<String,Double> internal=new LinkedHashMap<>(128,.75f,true);
    private final Map<String,Double> conditional=new LinkedHashMap<>(128,.75f,true);

    private ChineseContextModel(Cursor in)throws IOException {
        if(in.readInt()!=0x5159434d||in.readInt()!=2)throw new IOException("Unsupported Chinese context data");
        source=readString(in,512);
        Table uni=readTable(in,65536);double total=0;
        for(int i=0;i<uni.keys.length;i++){if(uni.keys[i]>65535)throw new IOException("Invalid character");unigrams[(int)uni.keys[i]]=uni.counts[i];total+=uni.counts[i];}
        if(total<=0)throw new IOException("Empty Chinese context data");totalCharacters=total;
        bigrams=readTable(in,600000);trigrams=readTable(in,900000);
        tags=new HashMap<>();int n=readSize(in,50000);
        for(int i=0;i<n;i++){String word=readString(in,128);int tag=in.readInt();if(tag<0||tag>=TAGS)throw new IOException("Invalid class");tags.put(word,tag);}
        transitions=new int[TAGS][TAGS];
        for(int a=0;a<TAGS;a++)for(int b=0;b<TAGS;b++){int count=in.readInt();if(count<0)throw new IOException("Invalid class count");transitions[a][b]=count;classTotals[a]+=count;classNext[b]+=count;transitionTotal+=count;}
        continuations=new HashMap<>();n=readSize(in,10000);
        for(int i=0;i<n;i++){String history=readString(in,64);int totalCount=in.readInt();int size=readSize(in,8);ArrayList<String> words=new ArrayList<>();
            for(int j=0;j<size;j++){String word=readString(in,64);int count=in.readInt();if(count>=3&&totalCount>=count&&count/(double)totalCount>=.08)words.add(word);}
            if(!words.isEmpty())continuations.put(history,new Prediction(totalCount,Collections.unmodifiableList(words)));
        }
        n=readSize(in,1024);ArrayList<String> sentences=new ArrayList<>();for(int i=0;i<n;i++)sentences.add(readString(in,512));
        examples=new HashMap<>();n=readSize(in,10000);
        for(int i=0;i<n;i++){String word=readString(in,64);int id=in.readInt();if(id<0||id>=sentences.size())throw new IOException("Invalid example");String text=sentences.get(id);if(text.length()<=80&&text.contains(word))examples.put(word,text);}
        if(in.read()!=-1)throw new IOException("Trailing Chinese context data");
    }

    public static ChineseContextModel load(InputStream bytes)throws IOException {
        try(InputStream in=new GZIPInputStream(bytes)){return loadUncompressed(in);}
    }
    /** Android's asset packager expands .gz assets and strips that suffix. */
    public static ChineseContextModel loadUncompressed(InputStream bytes)throws IOException {
        // Bulk decompression avoids millions of synchronized single-byte
        // BufferedInputStream reads during a cold Android/JIT startup.
        try(InputStream in=bytes;ByteArrayOutputStream output=new ByteArrayOutputStream(1024*1024)){
            byte[] buffer=new byte[32768];int count;
            while((count=in.read(buffer))!=-1){if(output.size()+count>32*1024*1024)throw new IOException("Oversized Chinese context data");output.write(buffer,0,count);}
            try{return new ChineseContextModel(new Cursor(output.toByteArray()));}catch(java.nio.BufferUnderflowException failure){throw new IOException("Truncated Chinese context data",failure);}
        }
    }
    public String source(){return source;}
    public String example(String word){return word==null?"":examples.getOrDefault(word,"");}

    /** Actual corpus next tokens only. Punctuation and mixed-script boundaries stop prediction. */
    public List<String> predict(String context) {
        String tail=chineseTail(context,6);if(tail.isEmpty())return Collections.emptyList();
        for(int length=tail.length();length>0;length--){Prediction result=continuations.get(tail.substring(tail.length()-length));
            if(result!=null&&(length>=2||result.total>=12))return result.words;
        }
        return Collections.emptyList();
    }

    /** Log likelihood for the whole edge under its own prefix, not the best other path. */
    public double score(String before,String text) {
        if(text==null||text.isEmpty())return 0;
        String history=chineseTail(before,6);
        int previous=classOf(history),next=classOf(text);
        String key=(history.length()>2?history.substring(history.length()-2):history)+"\t"+previous+"\t"+text;
        Double known=conditional.get(key);if(known!=null)return known;
        Double cached=internal.get(text);
        if(cached==null){double value=0;String prefix="";for(int i=0;i<text.length();i++){value+=character(prefix,text.charAt(i));prefix=(prefix+text.charAt(i));if(prefix.length()>2)prefix=prefix.substring(prefix.length()-2);}cached=value;internal.put(text,value);if(internal.size()>2048)internal.remove(internal.keySet().iterator().next());}
        double result=cached;
        if(!history.isEmpty()){
            result+=character(history,text.charAt(0))-character("",text.charAt(0));
            if(text.length()>1)result+=character(history+text.charAt(0),text.charAt(1))-character(text.substring(0,1),text.charAt(1));
        }
        // A light, globally trained class prior. It cannot swamp lexical or
        // character evidence; unseen classes have no fabricated preference.
        if(!history.isEmpty()&&previous!=UNKNOWN&&next!=UNKNOWN&&transitions[previous][next]>=10){
            double p=(classNext[next]+5.0)/(transitionTotal+TAGS*5.0);
            double q=(transitions[previous][next]+100*p)/(classTotals[previous]+100.0);
            result+=.6*Math.max(-2,Math.min(2,Math.log(q/p)));
        }
        conditional.put(key,result);if(conditional.size()>4096)conditional.remove(conditional.keySet().iterator().next());
        return result;
    }
    private double character(String history,char character) {
        if(character<'\u3400'||character>'\u9fff')return -8;
        double p=(unigrams[character]+5.0)/(totalCharacters+5*unigrams.length);
        if(!history.isEmpty()){
            char previous=history.charAt(history.length()-1);
            int count=bigrams.count(((long)previous<<16)|character);
            p=.95*(count+.25)/(unigrams[previous]+200.0)+.05*p;
            if(history.length()>1){char first=history.charAt(history.length()-2);int pair=bigrams.count(((long)first<<16)|previous);
                if(pair>2)p=.65*(trigrams.count(((long)first<<32)|((long)previous<<16)|character)+.1)/(pair+40.0)+.35*p;
            }
        }
        return Math.log(Math.max(1e-10,p));
    }
    private int classOf(String word) {
        if(word.isEmpty())return UNKNOWN;Integer found=tags.get(word);if(found!=null)return found;
        for(int length=Math.min(5,word.length()-1);length>0;length--){Integer head=tags.get(word.substring(0,length));if(head!=null&&head==VERB)return VERB;Integer tail=tags.get(word.substring(word.length()-length));if(tail!=null)return tail;}
        return UNKNOWN;
    }
    public static String chineseTail(String context,int maximum) {
        if(context==null)return "";int end=context.length();while(end>0&&Character.isWhitespace(context.charAt(end-1)))end--;
        int start=end;while(start>0&&end-start<maximum&&context.charAt(start-1)>='\u3400'&&context.charAt(start-1)<='\u9fff')start--;
        return context.substring(start,end);
    }
    private static final class Prediction {final int total;final List<String> words;Prediction(int total,List<String> words){this.total=total;this.words=words;}}
    private static final class Table {final long[] keys;final int[] counts;Table(long[] keys,int[] counts){this.keys=keys;this.counts=counts;}int count(long key){int index=Arrays.binarySearch(keys,key);return index<0?0:counts[index];}}
    private static Table readTable(Cursor in,int maximum)throws IOException {
        int size=readSize(in,maximum);long[] keys=new long[size];int[] counts=new int[size];long previous=0;
        for(int i=0;i<size;i++){long delta=readVarint(in);if(delta==0&&i>0)throw new IOException("Unsorted model");long key=previous+delta;long count=readVarint(in);if(key<previous||key>0xffffffffffffL||count<=0||count>Integer.MAX_VALUE)throw new IOException("Invalid ngram");keys[i]=key;counts[i]=(int)count;previous=key;}
        return new Table(keys,counts);
    }
    private static long readVarint(Cursor in)throws IOException {long value=0;for(int shift=0;shift<56;shift+=7){int b=in.readUnsignedByte();value|=(long)(b&127)<<shift;if((b&128)==0)return value;}throw new IOException("Oversized model integer");}
    private static int readSize(Cursor in,int maximum)throws IOException {int value=in.readInt();if(value<0||value>maximum)throw new IOException("Oversized model section");return value;}
    private static String readString(Cursor in,int maximum)throws IOException {int length=readSize(in,maximum);byte[] bytes=new byte[length];in.readFully(bytes);return new String(bytes,StandardCharsets.UTF_8);}
    private static final class Cursor {
        final ByteBuffer bytes;Cursor(byte[] data){bytes=ByteBuffer.wrap(data);}
        int readInt(){return bytes.getInt();}int readUnsignedByte(){return bytes.get()&255;}
        void readFully(byte[] target){bytes.get(target);}int read(){return bytes.hasRemaining()?readUnsignedByte():-1;}
    }
}
