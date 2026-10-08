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
    private boolean redownload,offlineOnly;
    @Override public void onCreate(Bundle args){super.onCreate(args);redownload=args!=null&&Boolean.parseBoolean(args.getString("redownload","false"));offlineOnly=args!=null&&Boolean.parseBoolean(args.getString("offline_only","false"));start();}
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
            check(TranslationRepository.conciseTranslation("row; line").equals("row"),"one English sense for commit");
            check(TranslationRepository.conciseTranslation("n. 工作；作品").equals("工作"),"one Chinese sense without POS for commit");
            check(TranslationRepository.conciseTranslation("a natural sentence.").equals("a natural sentence."),"translation punctuation retained");
            check(TranslationRepository.conciseTranslation(null).isEmpty(),"null dictionary gloss");
            check(new TranslationRepository.ModelProgress(TranslationRepository.State.DOWNLOADING,50,100,"").percent()==50,"real byte progress");
            check(new TranslationRepository.ModelProgress(TranslationRepository.State.DOWNLOADING,50,-1,"").percent()==-1,"unknown total stays indeterminate");
            check(TranslationRepository.downloadFailureText(android.app.DownloadManager.ERROR_INSUFFICIENT_SPACE).contains("空间不足"),"actionable storage error");
            check(TranslationRepository.downloadPauseText(android.app.DownloadManager.PAUSED_WAITING_FOR_NETWORK).contains("网络"),"paused network reason");
            check(TranslationRepository.failureText(new com.google.mlkit.common.MlKitException("must not expose URL or input",com.google.mlkit.common.MlKitException.MODEL_HASH_MISMATCH)).contains("校验失败"),"model hash failure reason");
            check(!TranslationRepository.failureText(new com.google.mlkit.common.MlKitException("private test content",com.google.mlkit.common.MlKitException.INTERNAL)).contains("private"),"failure message does not expose SDK text");
            report.append("PASS concise translations, honest progress and safe failure reasons\n");
            CountDownLatch open=new CountDownLatch(1);worker.post(()->{try{repository.open();}catch(Exception ignored){}finally{open.countDown();}});check(open.await(10,TimeUnit.SECONDS),"local data startup timeout");
            CountDownLatch local=new CountDownLatch(1);String[] localValues=new String[6];worker.post(()->{int i=0;for(String language:TranslationRepository.glossLanguages())localValues[i++]=repository.localGloss("开发","zh",language);local.countDown();});check(local.await(5,TimeUnit.SECONDS),"local language isolation timeout");check(localValues[0].equals("develop"),"English local gloss retained");for(int i=1;i<localValues.length;i++)check(localValues[i].isEmpty(),"selected non-English language must not leak English dictionary");
            report.append("PASS selected-language local lookup isolation\n");
            if(offlineOnly){
                CountDownLatch removed=new CountDownLatch(1);TranslationRepository.State[] deletion={TranslationRepository.State.CHECKING};repository.deleteModels("ja",state->{deletion[0]=state;if(state==TranslationRepository.State.MISSING||state==TranslationRepository.State.FAILED||state==TranslationRepository.State.UNSUPPORTED)removed.countDown();});check(removed.await(30,TimeUnit.SECONDS)&&deletion[0]==TranslationRepository.State.MISSING,"offline test-only Japanese removal");
                CountDownLatch failed=new CountDownLatch(1);TranslationRepository.State[] downloadState={TranslationRepository.State.CHECKING};repository.ensureModels("ja",state->{downloadState[0]=state;if(state!=TranslationRepository.State.DOWNLOADING&&state!=TranslationRepository.State.CHECKING)failed.countDown();});check(failed.await(5,TimeUnit.SECONDS)&&downloadState[0]==TranslationRepository.State.FAILED,"disconnected model must fail promptly rather than stay downloading");check(repository.modelStatusText("ja").contains("没有网络"),"offline failure gives actionable reason");
                CountDownLatch measured=new CountDownLatch(1);TranslationRepository.ModelProgress[] progress={null};repository.modelProgress("ja",value->{progress[0]=value;measured.countDown();});check(measured.await(5,TimeUnit.SECONDS)&&progress[0].state==TranslationRepository.State.FAILED&&progress[0].downloadedBytes==0&&progress[0].percent()==-1,"no invented offline download or readiness");
                report.append("PASS real disconnected download, prompt failure and no fabricated progress\n");output.putString("stream",report+"ALL_MODEL_OFFLINE_CHECKS_PASS\n");finish(Activity.RESULT_OK,output);return;
            }
            boolean available=true;
            for(String language:TranslationRepository.glossLanguages()){
                if(redownload&&!language.equals("en")){
                    CountDownLatch removed=new CountDownLatch(1);TranslationRepository.State[] deletion={TranslationRepository.State.CHECKING};repository.deleteModels(language,state->{deletion[0]=state;if(state==TranslationRepository.State.MISSING||state==TranslationRepository.State.FAILED||state==TranslationRepository.State.UNSUPPORTED)removed.countDown();});check(removed.await(30,TimeUnit.SECONDS)&&deletion[0]==TranslationRepository.State.MISSING,"test-only fresh model removal "+language);
                    report.append("FRESH_MODEL ").append(language).append('\n');
                }
                CountDownLatch download=new CountDownLatch(1);TranslationRepository.State[] state={TranslationRepository.State.CHECKING};repository.ensureModels(language,value->{state[0]=value;Bundle update=new Bundle();update.putString("stream","MODEL "+language+" "+value+"\n");sendStatus(0,update);if(value==TranslationRepository.State.READY||value==TranslationRepository.State.FAILED||value==TranslationRepository.State.UNSUPPORTED)download.countDown();});
                long deadline=android.os.SystemClock.elapsedRealtime()+180000;long[] observed={0,-1};int[] samples={0};boolean[] validProgress={true};
                while(download.getCount()!=0&&android.os.SystemClock.elapsedRealtime()<deadline){CountDownLatch progressReceived=new CountDownLatch(1);repository.modelProgress(language,progress->{if(progress.percent()>=0&&progress.totalBytes<=0)validProgress[0]=false;observed[0]=Math.max(observed[0],progress.downloadedBytes);if(progress.totalBytes>0)observed[1]=progress.totalBytes;if(samples[0]++<24){Bundle update=new Bundle();update.putString("stream","PROGRESS "+language+" "+progress.downloadedBytes+"/"+progress.totalBytes+" "+progress.message+"\n");sendStatus(0,update);}progressReceived.countDown();});check(progressReceived.await(5,TimeUnit.SECONDS)&&validProgress[0],"honest model progress callback");download.await(400,TimeUnit.MILLISECONDS);}
                boolean ended=download.getCount()==0;report.append("MODEL ").append(language).append(' ').append(ended?state[0]:"TIMEOUT").append(" | download observation ").append(observed[0]).append('/').append(observed[1]).append(" bytes\n");
                if(!ended||state[0]!=TranslationRepository.State.READY){available=false;continue;}
                CountDownLatch measured=new CountDownLatch(1);long[] bytes={-1};repository.modelSize(language,value->{bytes[0]=value;measured.countDown();});check(measured.await(10,TimeUnit.SECONDS)&&bytes[0]>0,"installed model size unavailable "+language);report.append("BYTES ").append(language).append(' ').append(bytes[0]).append(" | ").append(new TranslateRemoteModel.Builder(language.equals("en")?"zh":language).build().getModelNameForBackend()).append('\n');
                CountDownLatch translated=new CountDownLatch(1);String[] value={""};repository.translate("今天下午我想和朋友一起喝咖啡。","zh",language,(text,note)->{value[0]=text;report.append("TRANSLATED ").append(language).append(' ').append(text).append(" | ").append(note).append('\n');translated.countDown();});
                check(translated.await(30,TimeUnit.SECONDS)&&!value[0].isEmpty()&&!value[0].equals("今天下午我想和朋友一起喝咖啡。"),"real model translation failed "+language);
                if(!language.equals("en")){CountDownLatch details=new CountDownLatch(1);String[] selectedDetails={"",""};repository.describe("开发","zh",language,(text,note)->{selectedDetails[0]=text;selectedDetails[1]=note;details.countDown();});check(details.await(30,TimeUnit.SECONDS)&&!selectedDetails[0].isEmpty()&&!selectedDetails[1].contains("英文词典"),"non-English details must use selected model "+language);report.append("DETAIL_LANGUAGE ").append(language).append(' ').append(selectedDetails[0]).append(" | ").append(selectedDetails[1]).append('\n');}
            }
            if(repository.status("en")==TranslationRepository.State.READY){CountDownLatch reverse=new CountDownLatch(1);String[] value={""};repository.translate("The green keyboard makes everyday typing comfortable.","en","zh",(text,note)->{value[0]=text;report.append("TRANSLATED en-zh ").append(text).append('\n');reverse.countDown();});check(reverse.await(30,TimeUnit.SECONDS)&&value[0].matches(".*[\u3400-\u9fff].*"),"English to Chinese sentence failed");}
            if(available&&(getTargetContext().getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)checkBusyRetry(repository,worker,report);
            output.putString("stream",report.toString()+(available?"ALL_MODEL_CHECKS_PASS":"MODEL_DOWNLOAD_UNAVAILABLE")+"\n");finish(Activity.RESULT_OK,output);
        }catch(Throwable error){output.putString("stream",report+"MODEL_CHECK_FAILED: "+error+"\n");finish(Activity.RESULT_CANCELED,output);}
        finally{repository.close();worker.post(thread::quitSafely);}
    }
    @SuppressWarnings("unchecked")
    private static void checkBusyRetry(TranslationRepository repository,Handler worker,StringBuilder report)throws Exception{
        // Debug-only saturation fixture: no production hook, network request or user data mutation.
        java.lang.reflect.Field field=TranslationRepository.class.getDeclaredField("pending");field.setAccessible(true);
        java.util.Map<String,java.util.ArrayList<TranslationRepository.Callback>> pending=(java.util.Map<String,java.util.ArrayList<TranslationRepository.Callback>>)field.get(repository);
        CountDownLatch filled=new CountDownLatch(1);worker.post(()->{for(int i=0;i<8;i++)pending.put("v6-retry-fixture-"+i,new java.util.ArrayList<>());filled.countDown();});check(filled.await(5,TimeUnit.SECONDS),"retry fixture startup");
        try{
            String original="请在候选词出现后继续输入。";CountDownLatch busy=new CountDownLatch(1);String[] busyResult={"",""};java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();long started=android.os.SystemClock.elapsedRealtime();
            repository.translate(original,"zh","en",(text,note)->{busyResult[0]=text;busyResult[1]=note;calls.incrementAndGet();busy.countDown();});check(busy.await(5,TimeUnit.SECONDS)&&busyResult[0].isEmpty()&&busyResult[1].contains("翻译处理中")&&calls.get()==1,"saturated inference ends once with busy result");check(android.os.SystemClock.elapsedRealtime()-started>=1000,"three delayed retries before busy result");
            CountDownLatch cleared=new CountDownLatch(1);worker.post(()->{for(int i=0;i<8;i++)pending.remove("v6-retry-fixture-"+i);cleared.countDown();});check(cleared.await(5,TimeUnit.SECONDS),"retry fixture cleanup");
            CountDownLatch translated=new CountDownLatch(1);String[] resumed={""};repository.translate(original,"zh","en",(text,note)->{resumed[0]=text;translated.countDown();});check(translated.await(30,TimeUnit.SECONDS)&&!resumed[0].isEmpty(),"translation recovers after inference capacity frees");
            report.append("PASS saturated inference: three bounded retries, one busy callback and later recovery\n");
        }finally{CountDownLatch cleaned=new CountDownLatch(1);worker.post(()->{for(int i=0;i<8;i++)pending.remove("v6-retry-fixture-"+i);cleaned.countDown();});cleaned.await(5,TimeUnit.SECONDS);}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
