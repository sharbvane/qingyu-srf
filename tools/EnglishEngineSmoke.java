import com.qingyu.core.EnglishEngine;
import java.io.FileInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

/** Real generated lexicon checks; run with javac/java, no framework. */
public final class EnglishEngineSmoke {
    private static void check(boolean condition,String label) {if(!condition) throw new AssertionError(label);}
    public static void main(String[] args) throws Exception {
        EnglishEngine engine=new EnglishEngine(new FileInputStream(args[0]+"/english-words.tsv"),new FileInputStream(args[0]+"/english-bigrams.tsv"));
        check(engine.wordCount()>50000,"full licensed English vocabulary");
        check(engine.suggest("hel","").contains("hello"),"prefix completion");
        check(engine.suggest("helo","").contains("hello"),"single-edit spelling suggestion");
        check(engine.suggest("progamming","").contains("programming"),"missing middle character correction");
        check(engine.suggest("", "thank ").get(0).equals("you"),"contextual next word");
        check(engine.suggest("Hel", "").contains("Hello"),"capitalized completion");
        check(engine.suggest("HEL", "").contains("HELLO"),"uppercase completion");
        for(int i=0;i<10;i++) engine.learn("qingyu", "test ");
        check(engine.suggest("qing", "").contains("qingyu"),"new personalized word");
        check(engine.suggest("", "test ").contains("qingyu"),"learned contextual continuation");
        StringWriter before=new StringWriter();engine.saveLearning(before);
        check(before.toString().contains("qingyu\t10"),"learning frequency accumulates");
        engine.setLearningEnabled(false);engine.learn("secret", "password ");
        StringWriter after=new StringWriter();engine.saveLearning(after);
        check(before.toString().equals(after.toString()),"private field cannot mutate learning");
        EnglishEngine restored=new EnglishEngine(new FileInputStream(args[0]+"/english-words.tsv"),new FileInputStream(args[0]+"/english-bigrams.tsv"));
        restored.loadLearning(new StringReader(before.toString()));
        check(restored.suggest("qing", "").contains("qingyu"),"learning roundtrip");
        check(engine.suggest("<script>", "").equals(java.util.Collections.singletonList("<script>")),"invalid literal remains literal");
        long start=System.nanoTime();
        for(int i=0;i<1000;i++) engine.suggest(new String[]{"h","he","hel","helo","program","progamming","","de"}[i%8],"thank ");
        System.out.printf("ALL_ENGLISH_ENGINE_CHECKS_PASS: %d words, 1000 completion/correction/prediction calls %.2f ms%n",engine.wordCount(),(System.nanoTime()-start)/1e6);
    }
}
