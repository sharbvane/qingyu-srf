package com.qingyu.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One-edit, keyboard-aware suggestions; never rewrites the user's composition. */
public final class ChineseCorrection {
    public static final int MAX_QUERIES = 49;
    public static final int MAX_RESULTS = 8;
    private static final int MAX_INPUT = PinyinEngine.MAX_PINYIN_LENGTH;
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final String[] ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    private static final double[] OFFSETS = {0, .25, .75};
    private static final String[] NEIGHBORS = new String[26];
    private static final double[] X = new double[26], Y = new double[26];
    private static final Trie SPELLINGS = new Trie();

    static {
        for (int row=0;row<ROWS.length;row++) for (int col=0;col<ROWS[row].length();col++) {
            int key=ROWS[row].charAt(col)-'a'; X[key]=col+OFFSETS[row]; Y[key]=row;
        }
        for (int key=0;key<26;key++) {
            StringBuilder neighbors=new StringBuilder();
            for (int other=0;other<26;other++) if (key!=other && distance(key,other)<=1.3) neighbors.append((char)('a'+other));
            NEIGHBORS[key]=neighbors.toString();
        }
        // The existing Apache-2.0 AOSP dictionary's legal syllable alphabet.
        // Isolated m/n/ng/hm/hng are not full-word typo evidence; accept them
        // normally in the decoder, but do not use them to justify corrections.
        String syllables="a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu "
                +"ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo "
                +"da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo e ei en eng er "
                +"fa fan fang fei fen feng fiao fo fou fu ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo "
                +"ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun "
                +"ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo "
                +"la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lue lve lun luo lv "
                +"ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu "
                +"na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nue nve nuo nv o ou "
                +"pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun "
                +"ran rang rao re ren reng ri rong rou ru ruan rui run ruo sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo "
                +"ta tai tan tang tao te tei teng ti tian tiao tie ting tong tou tu tuan tui tun tuo "
                +"wa wai wan wang wei wen weng wo wu xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun "
                +"za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo";
        for (String syllable:syllables.split(" ")) SPELLINGS.add(syllable);
    }

    private ChineseCorrection() { }

    /** Indexed lookups inherit source frequency, context and durable learning. */
    public static List<PinyinEngine.Word> suggest(String raw, String context, PinyinEngine.Lexicon lexicon) {
        if (lexicon==null || !eligible(raw)) return Collections.emptyList();
        String before=context==null?"":context;
        try {
            // Exact full spelling or initials always beat speculative corrections.
            List<PinyinEngine.Word> exact=lexicon.lookup(raw,before);
            if (exact==null || !exact.isEmpty()) return Collections.emptyList();
            Map<String,PinyinEngine.Word> words=new HashMap<>();
            for (Variant variant:variants(raw,MAX_QUERIES-1)) {
                List<PinyinEngine.Word> matches=lexicon.lookup(variant.code,before);
                if (matches==null) continue;
                for (int i=0;i<Math.min(matches.size(),64);i++) {
                    PinyinEngine.Word word=matches.get(i);
                    // The provider also indexes initials: those are not evidence of
                    // a corrected full spelling, and must not occupy these slots.
                    if (word==null || word.text==null || word.pinyin==null || word.text.isEmpty()
                            || !word.pinyin.replace("'","").equals(variant.code) || !Double.isFinite(word.score)) continue;
                    PinyinEngine.Word candidate=new PinyinEngine.Word(word.text,word.pinyin,word.score-variant.penalty);
                    PinyinEngine.Word previous=words.get(word.text);
                    if (previous==null || candidate.score>previous.score) words.put(word.text,candidate);
                }
            }
            List<PinyinEngine.Word> result=new ArrayList<>(words.values());
            result.sort(Comparator.comparingDouble((PinyinEngine.Word word)->word.score).reversed().thenComparing(word->word.text));
            if (result.size()>MAX_RESULTS) result.subList(MAX_RESULTS,result.size()).clear();
            return result;
        } catch (RuntimeException unavailable) {
            // Auxiliary dictionary failure must not remove native candidates.
            return Collections.emptyList();
        }
    }

    /** Used by the existing bounded sentence beam for at most a few variants. */
    static List<Variant> variants(String raw, int max) {
        if (max<=0 || !eligible(raw)) return Collections.emptyList();
        int trouble=validPrefix(raw);
        Map<String,Variant> candidates=new HashMap<>();
        for (int i=0;i<raw.length();i++) {
            char original=raw.charAt(i);
            for (int n=0;n<NEIGHBORS[original-'a'].length();n++) {
                char next=NEIGHBORS[original-'a'].charAt(n);
                add(candidates,raw.substring(0,i)+next+raw.substring(i+1),1.8+.25*distance(original-'a',next-'a'),i,trouble);
            }
            if (i+1<raw.length() && original!=raw.charAt(i+1)) {
                add(candidates,raw.substring(0,i)+raw.charAt(i+1)+original+raw.substring(i+2),2.2,i,trouble);
            }
            add(candidates,raw.substring(0,i)+raw.substring(i+1),2.4,i,trouble);
        }
        if (raw.length()<MAX_INPUT) for (int i=0;i<=raw.length();i++) for (int key=0;key<LETTERS.length();key++) {
            add(candidates,raw.substring(0,i)+LETTERS.charAt(key)+raw.substring(i),2.6,i,trouble);
        }
        // ponytail: one edit and 64 raw letters bound generation to <2500
        // strings; an indexed typo trie is useful only if measurements demand it.
        List<Variant> result=new ArrayList<>(candidates.values());
        result.sort(Comparator.comparingDouble((Variant v)->v.order).thenComparing(v->v.code));
        int limit=Math.min(max,MAX_QUERIES-1);
        if (result.size()>limit) result.subList(limit,result.size()).clear();
        return result;
    }

    static final class Variant {
        final String code;
        final double penalty, order;
        Variant(String code,double penalty,double order) { this.code=code;this.penalty=penalty;this.order=order; }
    }

    private static void add(Map<String,Variant> out,String code,double penalty,int edited,int trouble) {
        int syllables=syllables(code);
        if (syllables<2) return;
        // Prefer the first unparseable region without making the spelling less
        // important than the source word frequency returned by the provider.
        double order=penalty+.02*Math.abs(edited-trouble)+.015*syllables;
        Variant old=out.get(code);
        if (old==null || penalty<old.penalty || penalty==old.penalty && order<old.order) out.put(code,new Variant(code,penalty,order));
    }

    private static boolean eligible(String raw) {
        if (raw==null || raw.length()<4 || raw.length()>MAX_INPUT) return false;
        boolean vowel=false;
        for (int i=0;i<raw.length();i++) {
            char ch=raw.charAt(i); if (ch<'a' || ch>'z') return false;
            if ("aeiouv".indexOf(ch)>=0) vowel=true;
        }
        return vowel;
    }

    private static int syllables(String value) { return parse(value)[value.length()]; }
    /** Sentence lookup can skip incomplete/illegal suffixes without any SQL. */
    static boolean[] completePrefixes(String value) {
        boolean[] result=new boolean[value.length()+1];
        for(int i=0;i<value.length();i++)if(value.charAt(i)<'a'||value.charAt(i)>'z'){Arrays.fill(result,true);return result;}
        int[] counts=parse(value);for(int i=0;i<counts.length;i++)result[i]=counts[i]>=0;
        return result;
    }
    private static int validPrefix(String value) {
        int[] parsed=parse(value);
        for (int i=value.length()-1;i>=0;i--) if (parsed[i]>=0) return i;
        return 0;
    }
    private static int[] parse(String value) {
        int[] counts=new int[value.length()+1]; Arrays.fill(counts,-1);counts[0]=0;
        for (int start=0;start<value.length();start++) if (counts[start]>=0) {
            Trie trie=SPELLINGS;
            for (int end=start;end<value.length() && end<start+6;end++) {
                trie=trie.next[value.charAt(end)-'a']; if (trie==null) break;
                if (trie.terminal && (counts[end+1]<0 || counts[end+1]>counts[start]+1)) counts[end+1]=counts[start]+1;
            }
        }
        return counts;
    }
    private static double distance(int a,int b) { return Math.hypot(X[a]-X[b],Y[a]-Y[b]); }
    private static final class Trie {
        final Trie[] next=new Trie[26]; boolean terminal;
        void add(String value) {
            Trie at=this;
            for (int i=0;i<value.length();i++) {
                int key=value.charAt(i)-'a'; if (at.next[key]==null) at.next[key]=new Trie();at=at.next[key];
            }
            at.terminal=true;
        }
    }
}
