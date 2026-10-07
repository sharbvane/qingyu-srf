package com.qingyu.ime;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Explicit test-only downloads and unseen sentence inference; does not assess translation quality. */
public final class TranslationModelInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle output=new Bundle();HandlerThread thread=new HandlerThread("model-check");thread.start();Handler worker=new Handler(thread.getLooper());TranslationRepository repository=new TranslationRepository(getTargetContext(),worker);StringBuilder report=new StringBuilder();
        try{
            String[] languages=TranslationRepository.glossLanguages();
            check(Arrays.equals(languages,new String[]{"en","ja","fr","de","ru","es"}),"six gloss languages");
            languages[0]="invalid";check(TranslationRepository.glossLanguages()[0].equals("en"),"language catalog cannot be changed by caller");
            ImePreferences prefs=new ImePreferences(getTargetContext());String savedLanguage=prefs.store.getString("gloss_language","en");
            try{for(String language:TranslationRepository.glossLanguages()){
                check(TranslationRepository.isGlossLanguage(language)&&language.equals(TranslateLanguage.fromLanguageTag(language)),"SDK language supported "+language);
                check(TranslationRepository.languageLabel(language).startsWith(TranslationRepository.languageName(language)+" · "),"language label "+language);
                prefs.store.edit().putString("gloss_language",language).commit();check(prefs.glossLanguage().equals(language),"saved gloss language "+language);
            }prefs.store.edit().putString("gloss_language","invalid").commit();check(prefs.glossLanguage().equals("en"),"unknown language falls back to English");}
            finally{prefs.store.edit().putString("gloss_language",savedLanguage).commit();}
            check(!TranslationRepository.isGlossLanguage(null)&&!TranslationRepository.isGlossLanguage("zh")&&repository.status("invalid")==TranslationRepository.State.UNSUPPORTED,"invalid model selection does not use English");
            CountDownLatch rejected=new CountDownLatch(1);TranslationRepository.State[] rejectedState={TranslationRepository.State.CHECKING};repository.ensureModels("invalid",state->{rejectedState[0]=state;rejected.countDown();});check(rejected.await(5,TimeUnit.SECONDS)&&rejectedState[0]==TranslationRepository.State.UNSUPPORTED,"invalid model download rejected");
            report.append("PASS six-language catalog, preferences and invalid download guard\n");
            check(TranslationRepository.modelPlatformSupported(true,"arm64-v8a",4096),"4K ARM64 model guard");
            check(!TranslationRepository.modelPlatformSupported(true,"arm64-v8a",16384),"16K ARM64 model guard");
            check(TranslationRepository.modelPlatformSupported(true,"x86_64",16384),"16K x86 model guard");
            check(TranslationRepository.modelPlatformSupported(false,"arm64-v8a",16384),"32-bit process model guard");
            report.append("PASS model ABI/page-size guard\n");
            CountDownLatch open=new CountDownLatch(1);worker.post(()->{try{repository.open();}catch(Exception ignored){}finally{open.countDown();}});check(open.await(10,TimeUnit.SECONDS),"local data startup timeout");
            boolean available=true;
            for(String language:TranslationRepository.glossLanguages()){
                CountDownLatch download=new CountDownLatch(1);TranslationRepository.State[] state={TranslationRepository.State.CHECKING};repository.ensureModels(language,value->{state[0]=value;Bundle update=new Bundle();update.putString("stream","MODEL "+language+" "+value+"\n");sendStatus(0,update);if(value==TranslationRepository.State.READY||value==TranslationRepository.State.FAILED||value==TranslationRepository.State.UNSUPPORTED)download.countDown();});
                boolean ended=download.await(180,TimeUnit.SECONDS);report.append("MODEL ").append(language).append(' ').append(ended?state[0]:"TIMEOUT").append('\n');
                if(!ended||state[0]!=TranslationRepository.State.READY){available=false;continue;}
                CountDownLatch measured=new CountDownLatch(1);long[] bytes={-1};repository.modelSize(language,value->{bytes[0]=value;measured.countDown();});check(measured.await(10,TimeUnit.SECONDS)&&bytes[0]>0,"installed model size unavailable "+language);report.append("BYTES ").append(language).append(' ').append(bytes[0]).append(" | ").append(new TranslateRemoteModel.Builder(language.equals("en")?"zh":language).build().getModelNameForBackend()).append('\n');
                CountDownLatch translated=new CountDownLatch(1);String[] value={""};repository.translate("今天下午我想和朋友一起喝咖啡。","zh",language,(text,note)->{value[0]=text;report.append("TRANSLATED ").append(language).append(' ').append(text).append(" | ").append(note).append('\n');translated.countDown();});
                check(translated.await(30,TimeUnit.SECONDS)&&!value[0].isEmpty()&&!value[0].equals("今天下午我想和朋友一起喝咖啡。"),"real model translation failed "+language);
            }
            if(repository.status("en")==TranslationRepository.State.READY){CountDownLatch reverse=new CountDownLatch(1);String[] value={""};repository.translate("The green keyboard makes everyday typing comfortable.","en","zh",(text,note)->{value[0]=text;report.append("TRANSLATED en-zh ").append(text).append('\n');reverse.countDown();});check(reverse.await(30,TimeUnit.SECONDS)&&value[0].matches(".*[\u3400-\u9fff].*"),"English to Chinese sentence failed");}
            output.putString("stream",report.toString()+(available?"ALL_MODEL_CHECKS_PASS":"MODEL_DOWNLOAD_UNAVAILABLE")+"\n");finish(Activity.RESULT_OK,output);
        }catch(Throwable error){output.putString("stream",report+"MODEL_CHECK_FAILED: "+error+"\n");finish(Activity.RESULT_CANCELED,output);}
        finally{repository.close();worker.post(thread::quitSafely);}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
