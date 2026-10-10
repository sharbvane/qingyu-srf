package com.qingyu.ime;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses existing installed models only; never requests a download or edits a user's editor. */
public final class EditorTranslationInstrumentation extends Instrumentation {
    private boolean modelsAvailable,redownload,frenchNeedsRestore;
    @Override public void onCreate(Bundle args){super.onCreate(args);modelsAvailable=args!=null&&Boolean.parseBoolean(args.getString("models_available","false"));redownload=args!=null&&Boolean.parseBoolean(args.getString("redownload","false"));start();}
    @Override public void onStart(){
        Bundle output=new Bundle();StringBuilder report=new StringBuilder();
        HandlerThread thread=new HandlerThread("editor-translation-check");thread.start();Handler worker=new Handler(thread.getLooper());
        TranslationRepository repository=new TranslationRepository(getTargetContext(),worker);
        boolean passed=false;
        try {
            check(!redownload||modelsAvailable,"redownload requires explicitly available models on a test device");
            protections();report.append("PASS exact punctuation, numbers, emoji, CRLF, whitespace, URL/email/code protection and atomic assembly\n");
            for(String abi:new String[]{"arm64-v8a","x86_64"})check(TranslationRepository.packagedLibrarySupportsPageSize(getTargetContext(),abi,"liblanguage_id_l2c_jni.so",16384),"language identifier 16KB gate "+abi);
            check(TranslationRepository.packagedLibrarySupportsPageSize(getTargetContext(),"armeabi-v7a","liblanguage_id_l2c_jni.so",4096),"language identifier 4KB armv7 gate");
            check(!TranslationRepository.packagedLibrarySupportsPageSize(getTargetContext(),"armeabi-v7a","liblanguage_id_l2c_jni.so",16384),"armv7 cannot falsely promise 16KB support");
            check(!TranslationRepository.packagedLibrarySupportsPageSize(getTargetContext(),"x86_64","../unknown.so",16384),"unknown library name rejected");
            report.append("PASS actual packaged language-identification native libraries and 16KB safeguards\n");
            CountDownLatch opened=new CountDownLatch(1);worker.post(()->{try{repository.open();}catch(Exception ignored){}finally{opened.countDown();}});check(opened.await(10,TimeUnit.SECONDS),"dictionary startup");
            String[] codes={"en","ja","fr","de","ru","es","zh"};
            String[] samples={"This keyboard helps me write messages clearly every day.","今日は友達と映画を見に行きます。","Cette application permet de rédiger des messages facilement.","Diese Tastatur hilft mir jeden Tag beim Schreiben von Nachrichten.","Эта клавиатура помогает мне каждый день писать сообщения друзьям.","Este teclado me ayuda a escribir mensajes a mis amigos todos los días.","今天下午我准备和朋友一起去公园散步。"};
            for(int i=0;i<codes.length;i++){
                String code=codes[i];CountDownLatch identified=new CountDownLatch(1);String[] value={""};boolean[] onMain={false};
                repository.identifyLanguage(samples[i],(language,note)->{onMain[0]=Looper.myLooper()==Looper.getMainLooper();value[0]=language;identified.countDown();});
                check(identified.await(15,TimeUnit.SECONDS)&&onMain[0]&&value[0].equals(code),"real offline main-thread language detection "+code+" got "+value[0]);
            }
            report.append("PASS real bundled offline detection of Chinese and six supported foreign languages\n");
            for(String sample:new String[]{"今日","今日は","友達","友達と","映画","映画を","見","見ま","你好","你好こ","开发","开发こ"}) {
                CountDownLatch returned=new CountDownLatch(1);String[] language={""};
                repository.identifyLanguage(sample,(value,note)->{language[0]=value;returned.countDown();});
                check(returned.await(15,TimeUnit.SECONDS),"Han/kana context identification timed out");
                report.append("CONTEXT_ID ").append(sample).append(" -> ").append(language[0].isEmpty()?"und":language[0]).append('\n');
            }
            String local="开发，2026🙂\r\n设计\tHello! https://example.org/中文 `中文代码` user@example.org";
            String[] mixed=editor(repository,local,false,"en");
            check(mixed[0].equals("develop，2026🙂\r\ndesign\tHello! https://example.org/中文 `中文代码` user@example.org"),"local editor translation with protected original text");
            check(editor(repository,"开发 French 文本",true,"en")[0].contains(" French "),"mixed selected foreign words retained");
            check(editor(repository,"开发 こんにちは ABC",true,"en")[0].equals("develop こんにちは ABC"),"selected Chinese mixed with kana and Latin must preserve foreign content");
            check(editor(repository,"开发 日本語を勉強する ABC",true,"en")[0].equals("develop 日本語を勉強する ABC"),"selected Chinese keeps Japanese Han/kana tokens intact");
            check(editor(repository,"开发こんにちは ABC",true,"en")[0].equals("developこんにちは ABC"),"glued Chinese and Japanese selection translates only identified Chinese");
            check(editor(repository,"你好こんにちは 123🙂\r\n",false,"en")[0].equals("helloこんにちは 123🙂\r\n"),"unselected glued Chinese translates without touching kana or separators");
            String[] nativeJapanese=editor(repository,"今日は友達と映画を見ます。🙂2026",false,"en");
            check(nativeJapanese[0].isEmpty(),"unselected native Japanese must not have its Han partially translated: "+nativeJapanese[0]+" ("+nativeJapanese[1]+")");
            check(editor(repository,"123🙂 https://x.test/中文 `代码`",false,"en")[0].isEmpty(),"technical-only content must not change");
            check(editor(repository,"Hello world",false,"en")[0].isEmpty(),"unselected non-Chinese content must not change");
            check(editor(repository,"字".repeat(4097),false,"en")[0].isEmpty(),"large editor selection rejected safely");
            report.append("PASS actual local Chinese, mixed selection, protected technical content, no-selection rule and size limits\n");
            if(modelsAvailable){
                String sentence="I would like to watch short videos with my friends.";
                EditorTranslation.Plan sentencePlan=EditorTranslation.plan(sentence,"en");
                check(sentencePlan.count==1&&sentencePlan.parts.get(0).text.equals(sentence.substring(0,sentence.length()-1)),"English sentence must enter the translator as one clause");
                String[] fluent=editor(repository,sentence,true,"en");
                check(!fluent[0].isEmpty()&&fluent[0].contains("朋友")&&(fluent[0].contains("视频")||fluent[0].contains("短片"))&&fluent[0].endsWith("."),"whole English sentence keeps its meaning and original punctuation "+fluent[1]);
                report.append("PASS whole English clause translation with natural Chinese order and protected original punctuation\n");
                for(String code:TranslationRepository.glossLanguages()) {
                    String[] value=editor(repository,"今天下午和朋友喝咖啡，2026🙂\r\n明天继续",false,code);
                    check(!value[0].isEmpty()&&value[0].contains("，2026🙂\r\n")&&!value[0].equals("今天下午和朋友喝咖啡，2026🙂\r\n明天继续"),"real installed model editor translation "+code+" "+value[1]);
                }
                report.append("PASS real installed six-language Chinese editor translations with immutable separators\n");
                for(int i=0;i<6;i++) {
                    String[] value=editor(repository,samples[i]+" 123🙂\r\n",true,"en");
                    check(!value[0].isEmpty()&&value[0].endsWith(" 123🙂\r\n")&&EditorTranslation.hasHan(value[0]),"detected foreign selection replaced by Chinese "+codes[i]+" "+value[1]);
                }
                String[] kanji=editor(repository,"日本語",true,"en");
                check(!kanji[0].isEmpty()&&EditorTranslation.hasHan(kanji[0])&&!kanji[0].equals("Japanese language"),"credible Han-only Japanese is translated into Chinese");
                report.append("PASS six-language selection auto-detection, foreign-to-Chinese conversion and Han-only Japanese\n");
                if(redownload){
                    frenchNeedsRestore=true;
                    CountDownLatch deleted=new CountDownLatch(1);TranslationRepository.State[] removed={null};
                    repository.deleteModels("fr",state->{removed[0]=state;if(state!=TranslationRepository.State.DELETING&&state!=TranslationRepository.State.CHECKING)deleted.countDown();});
                    check(deleted.await(30,TimeUnit.SECONDS)&&removed[0]==TranslationRepository.State.MISSING,"explicit test-only French model removal");
                    check(modelBytes(repository)==0,"deleted French model files still occupy storage");
                    String[] unavailable=editor(repository,samples[2]+" 123🙂\r\n",true,"en");
                    check(unavailable[0].isEmpty()&&unavailable[1].contains("原文已保留")&&unavailable[1].contains("模型"),"missing real French model returned replacement text");
                    check(repository.status("fr")==TranslationRepository.State.MISSING&&modelBytes(repository)==0,"editor action automatically downloaded missing French model");
                    report.append("PASS real missing French model rejects selection replacement, retains original and does not auto-download\n");
                    restoreFrench(repository);frenchNeedsRestore=false;
                    check(modelBytes(repository)>0,"restored French model bytes unavailable");
                    check(!editor(repository,"Cette application est pratique.",true,"en")[0].isEmpty(),"restored French editor translation unavailable");
                    report.append("PASS explicit French model re-download restored READY and actual editor translation\n");
                }
            } else {
                for(int i=0;i<6;i++) {
                    String code=codes[i];CountDownLatch refreshed=new CountDownLatch(1);TranslationRepository.State[] state={null};
                    repository.refreshModels(code,value->{state[0]=value;refreshed.countDown();});check(refreshed.await(10,TimeUnit.SECONDS),"model readiness query");
                    if(state[0]!=TranslationRepository.State.READY){String[] value=editor(repository,samples[i],true,"en");check(value[0].isEmpty()&&value[1].contains("原文已保留"),"missing reverse model leaves original unchanged");report.append("PASS missing ").append(code).append(" model rejected without an automatic download\n");break;}
                }
            }
            cancellation(repository,worker);report.append("PASS explicit cancellation and replacement requests stop old editor batches exactly once\n");
            generationChange(repository,worker);report.append("PASS model-generation change discards complete editor request once\n");
            passed=true;
        }catch(Throwable failure){report.append("EDITOR_TRANSLATION_CHECKS_FAIL: ").append(failure).append('\n');}
        finally{
            if(frenchNeedsRestore)try{restoreFrench(repository);frenchNeedsRestore=false;report.append("French test model restored during failure cleanup\n");}
            catch(Throwable failure){passed=false;report.append("FRENCH_MODEL_RESTORE_FAIL: ").append(failure).append('\n');}
            repository.close();thread.quitSafely();
        }
        output.putString("stream",report+(passed?"ALL_EDITOR_TRANSLATION_CHECKS_PASS\n":"EDITOR_TRANSLATION_CHECKS_FAIL\n"));finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,output);
    }
    private static long modelBytes(TranslationRepository repository)throws Exception {
        CountDownLatch returned=new CountDownLatch(1);long[] bytes={-1};repository.modelSize("fr",value->{bytes[0]=value;returned.countDown();});
        check(returned.await(10,TimeUnit.SECONDS),"French model size query timed out");return bytes[0];
    }
    private static void restoreFrench(TranslationRepository repository)throws Exception {
        CountDownLatch started=new CountDownLatch(1);repository.ensureModels("fr",true,state->started.countDown());
        check(started.await(10,TimeUnit.SECONDS),"French test model restore did not start");
        long deadline=android.os.SystemClock.elapsedRealtime()+180000;
        do {
            TranslationRepository.State state=repository.status("fr");
            if(state==TranslationRepository.State.READY)return;
            if(state==TranslationRepository.State.FAILED||state==TranslationRepository.State.UNSUPPORTED)throw new AssertionError("French test model restore failed: "+repository.modelStatusText("fr"));
            Thread.sleep(250);
        }while(android.os.SystemClock.elapsedRealtime()<deadline);
        throw new AssertionError("French test model restore timed out");
    }
    private static String[] editor(TranslationRepository repository,String original,boolean selected,String target)throws Exception {
        CountDownLatch returned=new CountDownLatch(1);String[] output={"",""};AtomicInteger calls=new AtomicInteger();
        repository.translateEditor(original,selected,target,(value,note)->{output[0]=value;output[1]=note;calls.incrementAndGet();returned.countDown();});
        check(returned.await(50,TimeUnit.SECONDS),"editor translation did not finish");
        check(calls.get()==1,"editor request callback exactly once");return output;
    }
    private static void protections(){
        EditorTranslation.Plan plan=EditorTranslation.plan("开发，2026👩‍👩‍👧‍👦\r\n设计\tHello https://x.test/中文 `代码`","zh");
        check(plan.count==2&&plan.apply(Arrays.asList("develop.","design!")).equals("develop，2026👩‍👩‍👧‍👦\r\ndesign\tHello https://x.test/中文 `代码`"),"protection parser");
        check(EditorTranslation.plan("cafe\u0301\u00a0beau","fr").apply(Arrays.asList("咖啡","美丽")).equals("咖啡\u00a0美丽"),"combining marks and NBSP");
        EditorTranslation.Plan sentence=EditorTranslation.plan("I would like to watch short videos.","en");
        check(sentence.count==1&&sentence.parts.get(0).text.equals("I would like to watch short videos")
                &&sentence.apply(Collections.singletonList("我想看短视频。")).equals("我想看短视频."),"ordinary foreign word spaces belong to a complete clause, not separate word translations");
        check(EditorTranslation.plan("  Hello there  my friend\tgood night\r\n42🙂good\u00a0day ","en")
                .apply(Arrays.asList("你好","我的朋友","晚安","好","天")).equals("  你好  我的朋友\t晚安\r\n42🙂好\u00a0天 "),"formatted whitespace remains verbatim around complete clauses");
        boolean rejected=false;try{plan.apply(Collections.singletonList("develop"));}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"partial results must be rejected");
        check(EditorTranslation.confidentLanguage(new String[]{"en","fr"},new float[]{.68f,.62f}).isEmpty(),"ambiguous identification cannot alter the editor");
        check(EditorTranslation.plan("开发 こんにちは ABC","zh").apply(Collections.singletonList("develop")).equals("develop こんにちは ABC"),"mixed kana and Latin protected");
        check(EditorTranslation.separatedHanSample("今日は友達と映画を見ます").isEmpty(),"native connected Japanese preserves identification path");
        check(EditorTranslation.plan("你好こんにちは","zh").count==0,"inseparable Han/kana content is preserved conservatively");
        check(EditorTranslation.kanaHanRuns("你好こんにちは https://x.test/中文かな").equals(Collections.singletonList("你好")),"glued Han identification excludes protected technical text");
        check(EditorTranslation.plan("你好こんにちは 日本語を勉強する","zh",Collections.singleton("你好"))
                .apply(Collections.singletonList("hello")).equals("helloこんにちは 日本語を勉強する"),"confirmed Chinese identification never unprotects Japanese Han");
    }
    private static void cancellation(TranslationRepository repository,Handler worker)throws Exception {
        CountDownLatch mainBlocked=new CountDownLatch(1),releaseMain=new CountDownLatch(1),registered=new CountDownLatch(1),cancelled=new CountDownLatch(1),returned=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();String[] output={"",""};
        new Handler(Looper.getMainLooper()).post(()->{mainBlocked.countDown();try{releaseMain.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
        try {
            check(mainBlocked.await(5,TimeUnit.SECONDS),"cancel callback barrier");
            repository.translateEditor("开发，设计",false,"en",(value,note)->{output[0]=value;output[1]=note;calls.incrementAndGet();returned.countDown();});
            worker.post(registered::countDown);check(registered.await(5,TimeUnit.SECONDS),"old editor request registration");
            repository.cancelEditorTranslation();worker.post(cancelled::countDown);check(cancelled.await(5,TimeUnit.SECONDS),"old editor batch cancellation");
        }finally{releaseMain.countDown();}
        check(returned.await(10,TimeUnit.SECONDS)&&output[0].isEmpty()&&output[1].contains("取消")&&calls.get()==1,"cancelled batch returned replacement content or repeated callback");
        CountDownLatch firstReturned=new CountDownLatch(1),secondReturned=new CountDownLatch(1);String[] first={""},second={""};AtomicInteger firstCalls=new AtomicInteger(),secondCalls=new AtomicInteger();
        repository.translateEditor("开发，设计",false,"en",(value,note)->{first[0]=value;firstCalls.incrementAndGet();firstReturned.countDown();});
        repository.translateEditor("设计",false,"en",(value,note)->{second[0]=value;secondCalls.incrementAndGet();secondReturned.countDown();});
        check(firstReturned.await(10,TimeUnit.SECONDS)&&secondReturned.await(10,TimeUnit.SECONDS)&&first[0].isEmpty()&&second[0].equals("design")&&firstCalls.get()==1&&secondCalls.get()==1,"new editor request did not supersede old batch safely");
    }
    private static void generationChange(TranslationRepository repository,Handler worker)throws Exception {
        CountDownLatch mainBlocked=new CountDownLatch(1),releaseMain=new CountDownLatch(1),returned=new CountDownLatch(1),changed=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();String[] output={"",""};
        new Handler(Looper.getMainLooper()).post(()->{mainBlocked.countDown();try{releaseMain.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
        try {
            check(mainBlocked.await(5,TimeUnit.SECONDS),"callback barrier");
            repository.translateEditor("开发，设计",false,"en",(value,note)->{output[0]=value;output[1]=note;calls.incrementAndGet();returned.countDown();});
            worker.post(()->{try{java.lang.reflect.Field generation=TranslationRepository.class.getDeclaredField("modelGeneration");generation.setAccessible(true);generation.setLong(repository,generation.getLong(repository)+1);}catch(Exception ignored){}finally{changed.countDown();}});
            check(changed.await(5,TimeUnit.SECONDS),"generation mutation barrier");
        }finally{releaseMain.countDown();}
        check(returned.await(10,TimeUnit.SECONDS)&&output[0].isEmpty()&&output[1].contains("更改")&&calls.get()==1,"stale whole request must not return partial or replacement content");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
