package com.qingyu.core;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure Java assertions against actual pinned modern source words and weights. */
public final class ChineseCorrectionChecks {
    private static final String[][] CASES={
            {"nohao","你好","adjacent-key"},
            {"niho","你好","missing-letter"},
            {"nihhao","你好","extra-letter"},
            {"nhiao","你好","transposed-letter"},
            {"jintiab","今天","adjacent-key"},
            {"gonyuan","公园","missing-letter"},
            {"shijjie","世界","extra-letter"},
            {"shijei","世界","transposed-letter"}
    };
    private static void check(boolean valid,String message) { if(!valid)throw new AssertionError(message); }
    private static final class Source implements PinyinEngine.Lexicon {
        final Map<String,List<PinyinEngine.Word>> index=new HashMap<>();
        int queries;String lastContext;
        @Override public List<PinyinEngine.Word> lookup(String code,String context) {
            queries++;lastContext=context;return index.getOrDefault(code,List.of());
        }
        @Override public void learn(String code,String text,String context) { throw new AssertionError("correction never learns before selection"); }
    }
    private static int rank(List<PinyinEngine.Word> words,String text) {
        for(int i=0;i<words.size();i++)if(words.get(i).text.equals(text))return i+1;return 0;
    }
    public static void main(String[] args) throws Exception {
        check(args.length==1,"supply .cache/pinyin-v2/rime-ice-base.dict.yaml");
        Set<String> wanted=new HashSet<>(List.of("nihao","anzhuo","dangang","kd","jintian","gongyuan","shijie"));
        for(String[] test:CASES) {
            wanted.add(test[0]);for(ChineseCorrection.Variant variant:ChineseCorrection.variants(test[0],48))wanted.add(variant.code);
        }
        Source source=new Source();int sourceRows=0;
        try(BufferedReader reader=Files.newBufferedReader(Path.of(args[0]),StandardCharsets.UTF_8)) {
            for(String row;(row=reader.readLine())!=null;) {
                String[] fields=row.split("\t");if(fields.length!=3 || !fields[2].matches("[0-9]+"))continue;
                sourceRows++;String[] spelling=fields[1].split(" ");
                if(!fields[0].matches("[\\u3400-\\u9fff]{2,8}") || fields[0].length()!=spelling.length)continue;
                String code=String.join("",spelling);
                StringBuilder initials=new StringBuilder();for(String part:spelling)if(!part.isEmpty())initials.append(part.charAt(0));
                PinyinEngine.Word word=new PinyinEngine.Word(fields[0],String.join("'",spelling),Math.log1p(Long.parseLong(fields[2])));
                if(wanted.contains(code))source.index.computeIfAbsent(code,key->new ArrayList<>()).add(word);
                if(wanted.contains(initials.toString()) && !initials.toString().equals(code))source.index.computeIfAbsent(initials.toString(),key->new ArrayList<>()).add(word);
            }
        }
        check(sourceRows>100000,"use modern whole-corpus source, not handwritten correction mappings");
        for(List<PinyinEngine.Word> words:source.index.values())words.sort(Comparator.comparingDouble((PinyinEngine.Word w)->w.score).reversed());
        for(String[] test:CASES) {
            source.queries=0;String raw=test[0];
            List<PinyinEngine.Word> result=ChineseCorrection.suggest(raw,"上下文",source);
            int found=rank(result,test[1]);
            System.out.printf("CASE %s %s -> %s rank=%d queries=%d%n",test[2],raw,test[1],found,source.queries);
            check(found>0 && found<=5,"expected real source word in first five: "+raw+" -> "+test[1]);
            check(source.queries<=ChineseCorrection.MAX_QUERIES,"query budget");
            check(result.size()<=ChineseCorrection.MAX_RESULTS,"result budget");
            check(raw.equals(test[0]),"original composition remains unchanged");
            check(source.lastContext.equals("上下文"),"context reaches ranked provider");
            for(PinyinEngine.Word word:result) {
                check(word.pinyin.contains("'"),"regular reading retained");
                double original=source.index.get(word.pinyin.replace("'","")).stream().filter(w->w.text.equals(word.text)).findFirst().orElseThrow().score;
                check(word.score<original,"correction penalty keeps exact words preferable");
            }
        }
        for(String raw:List.of("nihao","anzhuo","dangang","jintian","gongyuan","shijie","kd","nh","zgrm","dan'gang","1234","NOHAO")) {
            source.queries=0;check(ChineseCorrection.suggest(raw,"",source).isEmpty(),"exact words, initials and invalid tokens are not corrected: "+raw);
            check(source.queries<=1,"valid input avoids variant lookup: "+raw);
        }
        // The algorithm consumes the provider's actual score; the platform
        // layer supplies context and durable learning, rather than this class.
        PinyinEngine.Word learned=source.index.get("nihao").stream().filter(w->!w.text.equals("你好")).findFirst().orElseThrow();
        PinyinEngine.Lexicon adapted=new PinyinEngine.Lexicon() {
            @Override public List<PinyinEngine.Word> lookup(String code,String context) {
                List<PinyinEngine.Word> result=new ArrayList<>();
                for(PinyinEngine.Word word:source.lookup(code,context)) result.add(new PinyinEngine.Word(word.text,word.pinyin,
                        word.score+(word.text.equals(learned.text) && context.equals("已学习上下文")?10:0)));
                return result;
            }
            @Override public void learn(String code,String text,String context) { throw new AssertionError("no speculative learning"); }
        };
        check(rank(ChineseCorrection.suggest("nohao","已学习上下文",adapted),learned.text)==1,"provider context/learning score changes correction ranking");
        for(boolean broken:List.of(false,true)) {
            PinyinEngine.Lexicon unavailable=new PinyinEngine.Lexicon() {
                @Override public List<PinyinEngine.Word> lookup(String code,String context) {
                    if(broken)throw new IllegalStateException("unavailable optional lexicon");return null;
                }
                @Override public void learn(String code,String text,String context) { throw new AssertionError("no learning while unavailable"); }
            };
            check(ChineseCorrection.suggest("nohao","",unavailable).isEmpty(),"invalid/failing provider safely disables auxiliary correction");
        }
        List<ChineseCorrection.Variant> sentence=ChineseCorrection.variants("wojintianxiangqugonhyuan",3);
        check(sentence.stream().anyMatch(v->v.code.equals("wojintianxiangqugongyuan")),"sentence correction reaches bounded beam variants");
        check(sentence.size()<=3,"sentence caller's stricter variant budget");
        for(String raw:List.of("nihao".repeat(12),"nhiao".repeat(12),"abcdefghijklmnopqrstuvwx".repeat(2))) {
            check(ChineseCorrection.variants(raw,1000).size()<=48,"long/hostile input cannot increase lookup budget");
        }
        // This benchmark measures Java generation, not physical keyboard latency.
        long[] timings=new long[1000];
        for(int i=0;i<200;i++)ChineseCorrection.suggest(CASES[i%CASES.length][0],"",source);
        long started=System.nanoTime();int maxQueries=0;
        for(int i=0;i<timings.length;i++) {
            source.queries=0;long before=System.nanoTime();
            ChineseCorrection.suggest(CASES[i%CASES.length][0],"",source);
            timings[i]=System.nanoTime()-before;maxQueries=Math.max(maxQueries,source.queries);
            check(source.queries<=49,"1000-run query budget");
        }
        java.util.Arrays.sort(timings);double elapsed=(System.nanoTime()-started)/1e6;
        check(elapsed<15000,"1000 suggestions finish within bounded smoke timeout");
        System.out.printf("PERFORMANCE 1000 suggestions %.2fms p95=%.3fms max=%.3fms maxQueries=%d sourceRows=%d%n",elapsed,timings[950]/1e6,timings[999]/1e6,maxQueries,sourceRows);
        System.out.println("ALL_CHINESE_CORRECTION_CHECKS_PASS");
    }
}
