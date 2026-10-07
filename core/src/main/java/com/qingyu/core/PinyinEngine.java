package com.qingyu.core;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AOSP's offline full-pinyin decoder behind a platform-independent Java contract.
 * All methods must be called on the same serial worker. The native engine is
 * process-global, so only one instance may be open at a time.
 */
public final class PinyinEngine implements ChineseEngine {
    /** Indexed local words and durable learning supplied by the platform layer. */
    public interface Lexicon {
        List<Word> lookup(String code, String context);
        void learn(String code, String text, String context);
        default void learn(String code,String text,String context,String pinyin) { learn(code,text,context); }
    }
    public static final class Word {
        public final String text, pinyin;
        public final double score;
        public Word(String text, String pinyin, double score) {
            this.text=text;this.pinyin=pinyin;this.score=score;
        }
    }
    public static final int MAX_PINYIN_LENGTH = 64;
    public static final int MAX_CANDIDATES = 128;
    private static final Object DECODER_LOCK = new Object();
    private static PinyinEngine activeEngine;
    private boolean opened;
    private long ownerThread;
    private int candidateCount;
    private String activeInput = "";
    private String completedPrefix = "";
    private final ArrayDeque<Segment> completedSegments = new ArrayDeque<>();
    private final ArrayList<String> fixedChoices = new ArrayList<>();
    private boolean learningEnabled = true;
    private boolean previewing;
    private Lexicon lexicon;
    private String context="";
    private boolean lexicalDirty;
    private final Map<Integer,Word> lexicalChoices=new HashMap<>();
    private EngineSnapshot current = EngineSnapshot.empty();

    public void open(String dictionaryPath, String userPath) {
        if (opened) { checkWorker(); return; }
        if (dictionaryPath == null || userPath == null) {
            throw new IllegalArgumentException("Dictionary paths are required");
        }
        synchronized (DECODER_LOCK) {
            if (activeEngine != null) throw new IllegalStateException("The process already has an open decoder");
            ownerThread = Thread.currentThread().getId();
            if (!NativeDecoder.open(dictionaryPath, userPath)) {
                throw new IllegalStateException("The pinyin dictionary could not be opened");
            }
            activeEngine = this;
            opened = true;
            learningEnabled = true;
        }
        reset();
    }

    public EngineSnapshot reset() {
        checkWorker();
        NativeDecoder.reset();
        candidateCount = 0;
        activeInput = "";
        completedPrefix = "";
        completedSegments.clear();
        fixedChoices.clear();
        lexicalChoices.clear();
        current = EngineSnapshot.empty();
        return current;
    }

    /** Incremental search preserves any selected Chinese prefix when possible. */
    public EngineSnapshot search(String pinyin) {
        checkWorker();
        String input = pinyin == null ? "" : pinyin.toLowerCase(Locale.ROOT);
        if (input.length() > MAX_PINYIN_LENGTH) throw new IllegalArgumentException("Pinyin composition is too long");
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if ((ch < 'a' || ch > 'z') && ch != '\'') {
                throw new IllegalArgumentException("Only ASCII pinyin letters and apostrophes are supported");
            }
        }
        if (input.isEmpty()) return reset();
        // Reuse immutable results for repeated refreshes; native choose state is retained.
        if (input.equals(current.rawPinyin)&&!lexicalDirty) return current;
        lexicalDirty=false;
        activeInput = input;
        candidateCount = NativeDecoder.search(input);
        return publish("");
    }

    /** May select a prefix and return remaining candidates, or return committedText. */
    public EngineSnapshot select(int id) {
        checkWorker();
        Word lexical=lexicalChoices.get(id);
        if(lexical!=null) {
            String fixed=safe(NativeDecoder.choice(0));
            int size=Math.min(NativeDecoder.fixedLength(),fixed.length());
            int[] positions=NativeDecoder.syllableStarts();
            int offset=size>0&&size<positions.length?Math.min(activeInput.length(),positions[size]):0;
            String prefix=completedPrefix+fixed.substring(0,size);
            learn(activeInput.substring(offset),lexical.text,context+prefix,lexical.pinyin);
            String commit=prefix+lexical.text;
            if(!prefix.isEmpty()) {
                String nativePrefix=reading(positions,size,fixed.substring(0,size));
                String wholeReading=size>0&&nativePrefix.isEmpty()?"":joinedReading(completedReading(),nativePrefix,lexical.pinyin);
                learn(rawInput(),commit,context,wholeReading);
            }
            reset();current=new EngineSnapshot("","",Collections.emptyList(),commit);return current;
        }
        if (id < 0 || id >= candidateCount) return current;
        String chosen=safe(NativeDecoder.choice(id));
        String oldSentence=safe(NativeDecoder.choice(0));
        int oldFixed=Math.min(NativeDecoder.fixedLength(),oldSentence.length());
        if(id==0&&chosen.length()>=oldFixed)chosen=chosen.substring(oldFixed);
        int[] oldStarts=NativeDecoder.syllableStarts();
        int start=oldFixed>0&&oldFixed<oldStarts.length?Math.min(activeInput.length(),oldStarts[oldFixed]):0;
        int end=oldFixed+chosen.length()<oldStarts.length?Math.min(activeInput.length(),oldStarts[oldFixed+chosen.length()]):activeInput.length();
        if(end>start)learn(activeInput.substring(start,end),chosen,context+completedPrefix+oldSentence.substring(0,oldFixed),"");
        candidateCount = NativeDecoder.choose(id);
        String sentence = safe(NativeDecoder.choice(0));
        int fixed = NativeDecoder.fixedLength();
        int[] starts = NativeDecoder.syllableStarts();
        if (starts.length > 0 && fixed == starts.length - 1 && fixed > 0) {
            int consumed = Math.max(0, Math.min(starts[starts.length - 1], activeInput.length()));
            String remaining = activeInput.substring(consumed);
            while (remaining.startsWith("'")) remaining = remaining.substring(1);
            NativeDecoder.reset();
            fixedChoices.clear();
            if (!remaining.isEmpty()) {
                // The AOSP decoder bounds each sentence to nine syllables. Preserve
                // every unparsed keystroke and continue it as the next segment.
                completedSegments.addLast(new Segment(activeInput.substring(0, consumed), sentence,reading(starts,fixed,sentence)));
                completedPrefix += sentence;
                activeInput = remaining;
                candidateCount = NativeDecoder.search(activeInput);
                return publish("");
            }
            String commit = completedPrefix + sentence;
            if(oldFixed>0||!completedPrefix.isEmpty()) {
                String currentReading=reading(starts,fixed,sentence);
                learn(rawInput(),commit,context,currentReading.isEmpty()?"":joinedReading(completedReading(),currentReading));
            }
            activeInput = "";
            completedPrefix = "";
            completedSegments.clear();
            candidateCount = 0;
            current = new EngineSnapshot("", "", Collections.emptyList(), commit);
            return current;
        }
        return publish("");
    }

    /** Removes one pending pinyin character; when all are selected, undoes selection. */
    public EngineSnapshot backspace() {
        checkWorker();
        String raw = activeInput;
        if (raw.isEmpty()) return restorePreviousSegment();
        String nativeRaw = safe(NativeDecoder.pinyin());
        if (nativeRaw.length() < raw.length()) {
            activeInput = raw.substring(0, raw.length() - 1);
            candidateCount = NativeDecoder.search(activeInput);
            if (activeInput.isEmpty()) return restorePreviousSegment();
            return publish("");
        }
        int fixed = NativeDecoder.fixedLength();
        int[] starts = NativeDecoder.syllableStarts();
        int fixedEnd = fixed > 0 && fixed < starts.length ? starts[fixed] : 0;
        if (raw.length() <= fixedEnd && fixed > 0) {
            candidateCount = NativeDecoder.cancelLastChoice();
        } else {
            candidateCount = NativeDecoder.delete(raw.length() - 1, false, false);
            activeInput = raw.substring(0, raw.length() - 1);
        }
        if (activeInput.isEmpty()) return restorePreviousSegment();
        return publish("");
    }

    /** Original letters, including earlier selected but uncommitted segments. */
    public String rawInput() {
        checkWorker();
        StringBuilder raw = new StringBuilder();
        for (Segment segment : completedSegments) raw.append(segment.pinyin);
        return raw.append(activeInput).toString();
    }

    /** Flush learning only at a lifecycle boundary, never on every key. */
    public void flush() {
        checkWorker();
        NativeDecoder.flush();
    }

    /** No new words or frequency updates while disabled. Existing ranking is retained. */
    public void setLearningEnabled(boolean enabled) {
        checkWorker();
        learningEnabled = enabled;
        NativeDecoder.setLearningEnabled(enabled);
    }

    public void setLexicon(Lexicon lexicon) { checkWorker();this.lexicon=lexicon;lexicalDirty=true; }
    public void setContext(String context) { checkWorker();String next=context==null?"":context;if(!this.context.equals(next))lexicalDirty=true;this.context=next; }
    private void learn(String code,String text,String before,String pinyin) {
        if(lexicon!=null&&learningEnabled&&!previewing&&!code.isEmpty()&&!text.isEmpty()) {
            try{lexicon.learn(code,text,before,pinyin);}catch(RuntimeException ignored){ }
        }
    }

    /** Canonical reading is computed on selection, never by scanning on a key. */
    private String reading(int[] positions,int characters,String text) {
        if(characters==0)return "";
        if(positions.length<=characters)return "";
        String code=activeInput.substring(0,Math.min(activeInput.length(),positions[characters]));
        // Indexed known words also supply the complete reading of abbreviations.
        if(lexicon!=null)for(Word word:words(code,context+completedPrefix,new HashMap<>()))if(word.text.equals(text))return word.pinyin;
        StringBuilder result=new StringBuilder();
        for(int index=0;index<characters;index++) {
            int start=positions[index],end=positions[index+1];
            if(start<0||end<=start||end>activeInput.length())return "";
            String syllable=activeInput.substring(start,end).replace("'","");
            // Native starts already identify syllables; bare initials have no
            // canonical spelling and must not be invented for an unknown word.
            if(!syllable.matches("[a-z]*[aeiouv][a-z]*"))return "";
            if(result.length()>0)result.append('\'');result.append(syllable);
        }
        return result.toString();
    }
    private String completedReading() {
        StringBuilder result=new StringBuilder();
        for(Segment segment:completedSegments) {
            if(segment.reading.isEmpty())return "";
            if(result.length()>0)result.append('\'');result.append(segment.reading);
        }
        return result.toString();
    }
    private String joinedReading(String... parts) {
        // Empty components are valid only for a missing prefix. If a selected
        // prefix has an unknown reading, preserve its raw alias without guessing.
        if(!completedPrefix.isEmpty()&&parts[0].isEmpty())return "";
        StringBuilder result=new StringBuilder();
        for(String part:parts)if(!part.isEmpty()){if(result.length()>0)result.append('\'');result.append(part);}
        return result.toString();
    }

    /**
     * Read the entire sentence represented by this candidate without accepting
     * it. Includes earlier chosen segments and the native nine-syllable tail.
     * Preview never learns, discards invalid letters, or changes candidate ids.
     */
    public String previewCandidate(int id) {
        checkWorker();
        Word lexical=lexicalChoices.get(id);
        if(lexical!=null) {
            String fixed=safe(NativeDecoder.choice(0));
            return completedPrefix+fixed.substring(0,Math.min(NativeDecoder.fixedLength(),fixed.length()))+lexical.text;
        }
        if (id < 0 || id >= candidateCount) return current.composing;
        String savedInput = activeInput, savedPrefix = completedPrefix;
        EngineSnapshot savedSnapshot = current;
        ArrayDeque<Segment> savedSegments = new ArrayDeque<>(completedSegments);
        ArrayList<String> savedChoices = new ArrayList<>(fixedChoices);
        int savedCount = candidateCount;
        Map<Integer,Word> savedLexical=new HashMap<>(lexicalChoices);
        previewing=true;
        NativeDecoder.setLearningEnabled(false);
        try {
            EngineSnapshot preview = select(id);
            for (int step = 0; step < MAX_PINYIN_LENGTH && preview.committedText.isEmpty() && !preview.candidates.isEmpty(); step++) {
                EngineSnapshot next = select(preview.candidates.get(0).id);
                if (next == preview) break;
                preview = next;
            }
            return preview.committedText.isEmpty() ? preview.composing : preview.committedText;
        } finally {
            try {
                NativeDecoder.reset();
                int restoredCount = savedInput.isEmpty() ? 0 : NativeDecoder.search(savedInput);
                for (String chosen : savedChoices) {
                    int fixed = NativeDecoder.fixedLength(), match = -1;
                    for (int candidate = 0; candidate < restoredCount; candidate++) {
                        String word = safe(NativeDecoder.choice(candidate));
                        if (candidate == 0 && fixed > 0 && word.length() >= fixed) word = word.substring(fixed);
                        if (word.equals(chosen)) { match = candidate; break; }
                    }
                    if (match < 0) throw new IllegalStateException("Could not restore the selected pinyin prefix");
                    restoredCount = NativeDecoder.choose(match);
                }
                // A caller retains the same immutable snapshot and its ids.
                // Check the native ordering before letting a stale id escape.
                int fixed = NativeDecoder.fixedLength();
                for (Candidate candidate : savedSnapshot.candidates) {
                    if(candidate.id<0)continue;
                    String word = safe(NativeDecoder.choice(candidate.id));
                    if (candidate.id == 0 && fixed > 0 && word.length() >= fixed) word = word.substring(fixed);
                    if (!word.equals(candidate.text)) throw new IllegalStateException("Pinyin preview changed candidate ordering");
                }
            } finally {
                activeInput = savedInput; completedPrefix = savedPrefix; current = savedSnapshot;
                candidateCount = savedCount;
                completedSegments.clear(); completedSegments.addAll(savedSegments);
                fixedChoices.clear(); fixedChoices.addAll(savedChoices);
                lexicalChoices.clear();lexicalChoices.putAll(savedLexical);previewing=false;
                NativeDecoder.setLearningEnabled(learningEnabled);
            }
        }
    }

    /** Native dictionary continuation; never query while composition is active. */
    public List<String> predict(String context) {
        checkWorker();
        if (!activeInput.isEmpty() || !completedPrefix.isEmpty() || context == null || context.isEmpty()) {
            return Collections.emptyList();
        }
        String[] result = NativeDecoder.predict(context);
        if (result == null || result.length == 0) return Collections.emptyList();
        return Collections.unmodifiableList(java.util.Arrays.asList(result));
    }

    @Override public void close() {
        if (!opened) return;
        checkWorker();
        NativeDecoder.flush();
        synchronized (DECODER_LOCK) {
            NativeDecoder.close();
            opened = false;
            activeEngine = null;
        }
        activeInput = "";
        completedPrefix = "";
        completedSegments.clear();
        fixedChoices.clear();
        lexicalChoices.clear();lexicon=null;context="";
        current = EngineSnapshot.empty();
    }

    private EngineSnapshot publish(String committed) {
        String raw = activeInput;
        String sentence = safe(NativeDecoder.choice(0));
        int fixed = Math.min(NativeDecoder.fixedLength(), sentence.length());
        trackFixedChoices(sentence.substring(0, fixed));
        int[] starts = NativeDecoder.syllableStarts();
        int offset = fixed > 0 && fixed < starts.length ? starts[fixed] : 0;
        offset = Math.max(0, Math.min(offset, raw.length()));
        String composing = completedPrefix + sentence.substring(0, fixed) + raw.substring(offset);
        List<Candidate> nativeList = new ArrayList<>(Math.min(candidateCount, MAX_CANDIDATES));
        for (int i = 0; i < candidateCount && i < MAX_CANDIDATES; i++) {
            String text = safe(NativeDecoder.choice(i));
            // AOSP candidate zero contains the previously selected sentence prefix.
            if (i == 0 && fixed > 0 && text.length() >= fixed) text = text.substring(fixed);
            if (!text.isEmpty()) nativeList.add(new Candidate(i, text));
        }
        lexicalChoices.clear();
        List<Candidate> list=mergeLexicon(raw.substring(offset),completedPrefix+sentence.substring(0,fixed),nativeList);
        current = new EngineSnapshot(composing, raw, list, committed);
        return current;
    }

    private List<Candidate> mergeLexicon(String raw,String prefix,List<Candidate> nativeList) {
        // One letter is a native phonetic prefix, not evidence of a complete
        // word/sentence. Keep its immediate native candidates and no SQL work.
        if(lexicon==null||raw.length()<2)return nativeList;
        String before=context+prefix;
        Map<String,List<Word>> queried=new HashMap<>();
        List<Word> exact=words(raw,before,queried);
        ArrayList<Word> ranked=new ArrayList<>(exact);
        ranked.sort((a,b)->Double.compare(b.score-(fullReading(b,raw)?0:3),a.score-(fullReading(a,raw)?0:3)));
        boolean completeWord=exact.stream().anyMatch(word->fullReading(word,raw));
        // Exact dictionary words already resolve the whole composition. Do
        // not spend a sentence search budget recreating the same known word.
        List<Word> sentences=completeWord||raw.length()<8?Collections.emptyList():sentenceWords(raw,before,queried,160);
        LinkedHashMap<String,Candidate> merged=new LinkedHashMap<>();
        Map<String,Candidate> nativeWords=new HashMap<>();
        for(Candidate candidate:nativeList)nativeWords.putIfAbsent(candidate.text,candidate);
        int[] nativeStarts=NativeDecoder.syllableStarts();
        if(!nativeList.isEmpty()&&nativeList.get(0).text.length()==1&&NativeDecoder.fixedLength()==0&&nativeStarts.length==2&&nativeStarts[1]==activeInput.length()&&raw.indexOf('\'')<0) {
            // A complete single syllable keeps the native frequent character
            // first. Alternate longer splits (xi'an / ti'an) remain available
            // immediately afterwards without hijacking ordinary single keys.
            merged.put(nativeList.get(0).text,nativeList.get(0));
        }
        for(Word word:ranked)appendWord(merged,nativeWords,word);
        // AOSP keeps its preferred sentence when the same result is supported
        // by the modern word graph. An alternate syllable split is promoted
        // only when its complete lexical path beats the obsolete one.
        Candidate first=nativeList.isEmpty()?null:nativeList.get(0);
        boolean nativeSupported=first!=null&&sentences.stream().anyMatch(word->word.text.equals(first.text));
        if(nativeSupported&&first!=null)merged.putIfAbsent(first.text,first);
        for(Word word:sentences)appendWord(merged,nativeWords,word);
        if(exact.isEmpty()) {
            // A legal decomposition can still contain an extra accidental
            // letter (nihaoo). Append corrections without replacing that path.
            for(Word word:ChineseCorrection.suggest(raw,before,lexicon))appendWord(merged,nativeWords,word);
            if(sentences.isEmpty()&&raw.length()>=10) {
                for(ChineseCorrection.Variant variant:ChineseCorrection.variants(raw,3)) {
                    for(Word word:sentenceWords(variant.code,before,new HashMap<>(),48))appendWord(merged,nativeWords,new Word(word.text,word.pinyin,word.score-variant.penalty));
                }
            }
        }
        for(Candidate candidate:nativeList)merged.putIfAbsent(candidate.text,candidate);
        ArrayList<Candidate> result=new ArrayList<>(merged.values());
        if(result.size()>MAX_CANDIDATES)result.subList(MAX_CANDIDATES,result.size()).clear();
        return result;
    }

    private void appendWord(Map<String,Candidate> merged,Map<String,Candidate> nativeWords,Word word) {
        if(word==null||merged.containsKey(word.text)||lexicalChoices.size()>=48)return;
        Candidate original=nativeWords.get(word.text);
        // Reusing a native id is safe only when it already consumes this full
        // reading. A different syllable count must use the full-word commit.
        int fixed=NativeDecoder.fixedLength();int[] positions=NativeDecoder.syllableStarts();
        if(original!=null&&positions.length>fixed&&word.text.length()==positions.length-1-fixed&&positions[positions.length-1]==activeInput.length()) {
            merged.put(word.text,original);return;
        }
        int id=-1-lexicalChoices.size();lexicalChoices.put(id,word);merged.put(word.text,new Candidate(id,word.text));
    }

    private List<Word> words(String code,String before,Map<String,List<Word>> queried) {
        String key=code+"\t"+before;
        List<Word> cached=queried.get(key);if(cached!=null)return cached;
        ArrayList<Word> valid=new ArrayList<>();
        try {
            List<Word> found=lexicon.lookup(code,before);
            if(found!=null)for(Word word:found) {
                if(valid.size()>=64)break;
                if(word!=null&&word.text!=null&&!word.text.isEmpty()&&word.text.length()<=64&&word.pinyin!=null&&!word.pinyin.isEmpty()&&Double.isFinite(word.score))valid.add(word);
            }
            // Legacy AOSP serializes üe as lue/nue. Query its equivalent code
            // in addition to the original spelling, never rewrite the raw input.
            // Match canonical syllables before removing apostrophes: nv'er must
            // remain 女儿 and must not turn into a different nue+r reading.
            if(code.contains("lve")||code.contains("nve")) {
                String equivalent=code.replace("lve","lue").replace("nve","nue");
                List<Word> alternatives=lexicon.lookup(equivalent,before);
                if(alternatives!=null)for(Word word:alternatives) {
                    if(valid.size()>=64)break;
                    if(word!=null&&word.text!=null&&!word.text.isEmpty()&&word.text.length()<=64&&word.pinyin!=null&&Double.isFinite(word.score)&&fullReading(word,code))valid.add(word);
                }
            }
        }catch(RuntimeException ignored){ }
        queried.put(key,valid);return valid;
    }

    private static boolean fullReading(Word word,String raw) {
        String requested=raw.replace("'","");
        String canonical=word.pinyin.replace(" ","'");
        return canonical.replace("'","").equals(requested)||canonical.replace("lue","lve").replace("nue","nve").replace("'","").equals(requested);
    }

    private static final class Path {
        final String text,pinyin;final double score;final int words;
        Path(String text,String pinyin,double score,int words){this.text=text;this.pinyin=pinyin;this.score=score;this.words=words;}
    }
    private List<Word> sentenceWords(String raw,String before,Map<String,List<Word>> queried,int budget) {
        if(raw.length()<4||raw.length()>MAX_PINYIN_LENGTH)return Collections.emptyList();
        ArrayList<ArrayList<Path>> paths=new ArrayList<>();for(int i=0;i<=raw.length();i++)paths.add(new ArrayList<>());
        paths.get(0).add(new Path("","",0,0));
        int used=0;
        for(int start=0;start<raw.length()&&used<budget;start++) {
            List<Path> preceding=paths.get(start);if(preceding.isEmpty())continue;
            // ponytail: three paths per boundary and one best-prefix context
            // hint; use a corpus-backed language model if this measured beam
            // ceiling becomes the quality bottleneck, never on the UI thread.
            String precedingContext=before+preceding.get(0).text;
            for(int end=start+1;end<=Math.min(raw.length(),start+32)&&used<budget;end++) {
                String code=raw.substring(start,end);if(code.replace("'","").isEmpty())continue;
                List<Word> options=words(code,precedingContext,queried);used++;
                int next=end;while(next<raw.length()&&raw.charAt(next)=='\'')next++;
                for(Word word:options) {
                    if(!fullReading(word,code))continue;
                    // Isolated interjections n/m/ng are accepted normally by
                    // AOSP, but are weak evidence inside a typed sentence. In
                    // particular n+o+hao must not suppress nohao -> 你好.
                    if(java.util.Arrays.stream(word.pinyin.split("'" )).anyMatch(s->s.equals("n")||s.equals("m")||s.equals("ng")||s.equals("hm")||s.equals("hng")))continue;
                    for(Path previous:preceding) {
                        if(previous.text.length()+word.text.length()>24)continue;
                        Path candidate=new Path(previous.text+word.text,previous.pinyin+(previous.pinyin.isEmpty()?"":"'")+word.pinyin,previous.score+Math.min(30,word.score)-15.5,previous.words+1);
                        ArrayList<Path> destination=paths.get(next);
                        boolean better=true;
                        for(int index=0;index<destination.size();index++)if(destination.get(index).text.equals(candidate.text)) {
                            if(destination.get(index).score>=candidate.score)better=false;else destination.remove(index);break;
                        }
                        if(better){destination.add(candidate);destination.sort((a,b)->Double.compare(b.score,a.score));while(destination.size()>3)destination.remove(destination.size()-1);}
                    }
                }
            }
        }
        ArrayList<Word> result=new ArrayList<>();
        for(Path path:paths.get(raw.length()))if(path.words>1)result.add(new Word(path.text,path.pinyin,path.score));
        return result;
    }

    private void checkWorker() {
        if (!opened) throw new IllegalStateException("Pinyin engine is not open");
        if (Thread.currentThread().getId() != ownerThread) {
            throw new IllegalStateException("Use one serial worker for all decoder calls");
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private void trackFixedChoices(String prefix) {
        String kept = "";
        int count = 0;
        for (String chosen : fixedChoices) {
            if (!prefix.startsWith(kept + chosen)) break;
            kept += chosen; count++;
        }
        if (count < fixedChoices.size()) fixedChoices.subList(count, fixedChoices.size()).clear();
        if (kept.length() < prefix.length()) fixedChoices.add(prefix.substring(kept.length()));
    }

    private EngineSnapshot restorePreviousSegment() {
        if (completedSegments.isEmpty()) return reset();
        Segment previous = completedSegments.removeLast();
        completedPrefix = completedPrefix.substring(0, completedPrefix.length() - previous.text.length());
        activeInput = previous.pinyin;
        NativeDecoder.reset();
        fixedChoices.clear();
        candidateCount = NativeDecoder.search(activeInput);
        return publish("");
    }

    private static final class Segment {
        final String pinyin;
        final String text;
        final String reading;
        Segment(String pinyin, String text,String reading) { this.pinyin = pinyin; this.text = text;this.reading=reading; }
    }
}
