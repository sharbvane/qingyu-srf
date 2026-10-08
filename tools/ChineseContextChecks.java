import com.qingyu.core.ChineseContextModel;
import com.qingyu.core.PinyinEngine;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Host check of actual core sentence beam. Final acceptance also uses Android JNI. */
public final class ChineseContextChecks {
    static void check(boolean value,String description){if(!value)throw new AssertionError(description);}
    private static final class Lexicon implements PinyinEngine.Lexicon {
        final Map<String,List<PinyinEngine.Word>> rows=new HashMap<>();final ChineseContextModel model;final boolean enabled;
        Lexicon(String fixture,ChineseContextModel model,boolean enabled)throws Exception {this.model=model;this.enabled=enabled;for(String line:Files.readAllLines(Paths.get(fixture))){String[] f=line.split("\t");rows.computeIfAbsent(f[0],ignored->new ArrayList<>()).add(new PinyinEngine.Word(f[1],f[2],Math.log1p(Integer.parseInt(f[3]))));}}
        public List<PinyinEngine.Word> lookup(String code,String before){return rows.getOrDefault(code,Collections.emptyList());}
        public void learn(String code,String text,String before){}
        public boolean contextReady(){return enabled;}
        public double transitionScore(String before,String word){return enabled?model.score(before,word):0;}
    }
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
        long loadStart=System.nanoTime();ChineseContextModel model=ChineseContextModel.load(Files.newInputStream(Paths.get(args[0])));
        System.out.printf("CONTEXT_LOAD %.3fms source=%s%n",(System.nanoTime()-loadStart)/1e6,model.source());
        check(model.predict("你好。").isEmpty()&&model.predict("中国！").isEmpty()&&model.predict("未知词甲乙").isEmpty(),"punctuation/unknown context must not manufacture predictions");
        check(model.example("不可能有例句甲乙").isEmpty(),"unknown examples stay empty");
        boolean enabled=args.length<4||!args[3].equals("baseline");Lexicon lexicon=new Lexicon(args[1],model,enabled);
        PinyinEngine engine=new PinyinEngine();java.lang.reflect.Field field=PinyinEngine.class.getDeclaredField("lexicon");field.setAccessible(true);field.set(engine,lexicon);
        java.lang.reflect.Method search=PinyinEngine.class.getDeclaredMethod("sentenceWords",String.class,String.class,Map.class,int.class);search.setAccessible(true);
        int daily=0,dailyTop1=0,dailyTop5=0,heldout=0,heldoutTop1=0,heldoutTop5=0;List<Long> times=new ArrayList<>();
        for(String line:Files.readAllLines(Paths.get(args[2]))){if(line.isEmpty()||line.startsWith("#"))continue;String[] f=line.split("\t");boolean assessment=f.length>4;
            List<PinyinEngine.Word> exact=lexicon.lookup(f[0],"");ArrayList<PinyinEngine.Word> found=new ArrayList<>();
            long start=System.nanoTime();
            if(!exact.isEmpty())found.addAll(exact);else found.addAll((List<PinyinEngine.Word>)search.invoke(engine,f[0],"",new HashMap<>(),160));times.add(System.nanoTime()-start);
            int rank=0;for(int i=0;i<found.size();i++)if(found.get(i).text.equals(f[1])){rank=i+1;break;}
            if(assessment){heldout++;if(rank==1)heldoutTop1++;if(rank>0&&rank<=5)heldoutTop5++;}else{daily++;if(rank==1)dailyTop1++;if(rank>0&&rank<=5)dailyTop5++;}
            StringBuilder first=new StringBuilder();for(int i=0;i<Math.min(found.size(),5);i++){if(i>0)first.append('|');first.append(found.get(i).text);}
            System.out.printf("CONTEXT_CASE\t%s\t%s\t%s\t%d\t%s%n",f[0],f[1],f[2],rank,first);
        }
        Collections.sort(times);System.out.printf("HOST_CORE_BEAM daily=%d top1=%d top5=%d; heldout=%d top1=%d top5=%d; p50=%.3fms p95=%.3fms max=%.3fms (no JNI/SQLite in timing)%n",daily,dailyTop1,dailyTop5,heldout,heldoutTop1,heldoutTop5,times.get(times.size()/2)/1e6,times.get(times.size()*95/100)/1e6,times.get(times.size()-1)/1e6);
    }
}
