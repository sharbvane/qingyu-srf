import com.qingyu.core.ChineseContextModel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Provenance and failure boundaries, not assertions mirroring numerical scoring. */
public final class ChineseContextModelChecks {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
        ChineseContextModel model=ChineseContextModel.load(Files.newInputStream(Paths.get(args[0])));
        ChineseContextModel packed=ChineseContextModel.loadUncompressed(new java.util.zip.GZIPInputStream(Files.newInputStream(Paths.get(args[0]))));
        check(packed.source().equals(model.source())&&packed.predict("中国").equals(model.predict("中国")),"Android expanded asset changed model behavior");
        Set<String> realTrainingSentences=new HashSet<>();for(String line:Files.readAllLines(Paths.get(args[1])))if(line.startsWith("# text = "))realTrainingSentences.add(line.substring(9));
        java.lang.reflect.Field field=ChineseContextModel.class.getDeclaredField("examples");field.setAccessible(true);Map<String,String> examples=(Map<String,String>)field.get(model);
        check(examples.size()>100,"usable real examples");
        for(Map.Entry<String,String> entry:examples.entrySet()){check(realTrainingSentences.contains(entry.getValue()),"fabricated or non-training example: "+entry.getKey());check(entry.getValue().length()<=80&&entry.getValue().contains(entry.getKey()),"unrelated source excerpt");check(model.example(entry.getKey()).equals(entry.getValue())&&packed.example(entry.getKey()).equals(entry.getValue()),"example lookup changed source text");}
        for(String context:new String[]{"","hello","中国。","中国，","中国！","中国?","甲乙丙丁戊己"})check(model.predict(context).isEmpty(),"unreliable/boundary prediction "+context);
        boolean failed=false;try{ChineseContextModel.load(new java.io.ByteArrayInputStream(new byte[]{1,2,3,4}));}catch(java.io.IOException expected){failed=true;}check(failed,"corrupt optional resource accepted");
        for(String text:new String[]{"网约车","扫码支付","短视频","中国","陌生词甲乙"})for(String before:new String[]{"","我想","。","你好，"})check(Double.isFinite(model.score(before,text)),"unknown context produced non-finite score");
        System.out.println("ALL_CHINESE_CONTEXT_SOURCE_CHECKS_PASS examples="+examples.size()+" (exact TRAIN texts, complete excerpts); gzip/Android raw asset equivalent; unknown/boundary predictions empty; invalid model rejected");
    }
}
