package com.qingyu.ime;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import android.os.ParcelFileDescriptor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/** Debug-only update boundary checks. No network, downloads or installation is initiated. */
public final class AppUpdateInstrumentation extends Instrumentation {
    private static void check(boolean condition,String note){if(!condition)throw new AssertionError(note);}
    private static JSONObject release(String version)throws Exception{
        return new JSONObject().put("tag_name","v"+version).put("draft",false).put("prerelease",false).put("body","修复候选栏\n改进键盘体验").put("assets",new JSONArray().put(new JSONObject().put("name","Qingyu-"+version+".apk").put("state","uploaded").put("size",1234).put("browser_download_url",AppUpdate.PROJECT_URL+"/releases/download/v"+version+"/Qingyu-"+version+".apk")));
    }
    private static void rejected(JSONObject input)throws Exception{try{AppUpdate.parse(input.toString());throw new AssertionError("unsafe release accepted");}catch(IllegalArgumentException expected){}}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        StringBuilder report=new StringBuilder();Bundle result=new Bundle();Context target=getTargetContext();
        try{
            check(AppUpdate.compareVersions("v0.4.0","0.3.0")>0&&AppUpdate.compareVersions("0.10.0","0.9.0")>0&&AppUpdate.compareVersions("1.0.0","1.0.0")==0,"numeric version order");
            check(AppUpdate.normalizedVersion("0.4.0-beta").isEmpty()&&AppUpdate.normalizedVersion("0.04.0").isEmpty()&&AppUpdate.normalizedVersion("999999999999999999999.0.0").isEmpty(),"invalid version accepted");report.append("PASS update numeric versions and invalid tags\n");
            JSONObject root=release("0.4.0");AppUpdate.Release parsed=AppUpdate.parse(root.toString());check(parsed.size==1234&&parsed.version.equals("0.4.0")&&parsed.notes.contains("\n"),"release metadata");
            JSONObject nullable=release("0.4.0").put("body",JSONObject.NULL);nullable.getJSONArray("assets").getJSONObject(0).put("digest",JSONObject.NULL);AppUpdate.Release withoutDigest=AppUpdate.parse(nullable.toString());check(withoutDigest.digest.isEmpty()&&!withoutDigest.notes.equals("null"),"nullable GitHub metadata rejected");
            rejected(release("0.4.0").put("draft",true));rejected(release("0.4.0").put("prerelease",true));JSONObject empty=release("0.4.0");empty.put("assets",new JSONArray());rejected(empty);JSONObject oversize=release("0.4.0");oversize.getJSONArray("assets").getJSONObject(0).put("size",1024L*1024*1024);rejected(oversize);report.append("PASS update stable release, notes, asset name and size\n");
            for(String address:new String[]{"http://github.com/sharbvane/qingyu-srf/releases/download/v1/Qingyu.apk","https://github.com.evil.test/sharbvane/qingyu-srf/releases/download/v1/Qingyu.apk","https://user@github.com/sharbvane/qingyu-srf/releases/download/v1/Qingyu.apk","https://github.com/other/qingyu-srf/releases/download/v1/Qingyu.apk","https://github.com/sharbvane/qingyu-srf/releases/download/%2e%2e/Qingyu.apk","https://github.com/sharbvane/qingyu-srf/releases/download/v1/Qingyu.apk?next=https://evil.test"})check(!AppUpdate.trustedAsset(address),"untrusted asset accepted "+address);check(AppUpdate.trustedAsset(parsed.url),"official asset rejected");report.append("PASS update HTTPS repository and URL boundary\n");
            check(AppUpdate.signaturesMatch(new Signature[]{new Signature("aa")},new Signature[]{new Signature("aa")}),"matching signature");check(!AppUpdate.signaturesMatch(new Signature[]{new Signature("aa")},new Signature[]{new Signature("bb")})&&!AppUpdate.signaturesMatch(null,new Signature[0]),"mismatched signature accepted");
            File installed=new File(target.getApplicationInfo().sourceDir);String version=target.getPackageManager().getPackageInfo(target.getPackageName(),0).versionName;AppUpdate.Release old=new AppUpdate.Release(version,"",parsed.url,installed.length(),"",root.toString());
            try{AppUpdate.validateArchive(target,installed,old);throw new AssertionError("same-version installation accepted");}catch(IllegalArgumentException expected){check(expected.getMessage().contains("更新版本"),"archive could not be read "+expected);}
            AppUpdate.Release wrong=new AppUpdate.Release("99.0.0","",parsed.url,installed.length(),"",root.toString());try{AppUpdate.validateArchive(target,installed,wrong);throw new AssertionError("mismatched archive version accepted");}catch(IllegalArgumentException expected){check(expected.getMessage().contains("不属于"),"unexpected package mismatch "+expected);}
            AppUpdate.Release digest=new AppUpdate.Release(version,"",parsed.url,installed.length(),"sha256:"+"0".repeat(64),root.toString());try{AppUpdate.validateArchive(target,installed,digest);throw new AssertionError("wrong digest accepted");}catch(IllegalArgumentException expected){check(expected.getMessage().contains("校验不匹配"),"unexpected digest mismatch "+expected);}
            report.append("PASS update archive version, downgrade and signature/digest checks\n");
            Intent installer=AppUpdate.installIntent(target);check(Intent.ACTION_VIEW.equals(installer.getAction())&&AppUpdate.MIME.equals(installer.getType())&&(installer.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0&&installer.getData().getAuthority().equals(target.getPackageName()+".updates"),"installer intent");
            try{target.getContentResolver().openFileDescriptor(Uri.parse("content://"+target.getPackageName()+".updates/../update.apk"),"r");throw new AssertionError("provider traversal accepted");}catch(java.io.FileNotFoundException expected){}
            report.append("PASS update installer MIME, temporary read grant and provider path guard\n");
            File providerApk=AppUpdate.installFile(target),externalApk=AppUpdate.apkFile(target);boolean created=false,externalCreated=false;
            try{
                if(!providerApk.exists()){
                    check(providerApk.getParentFile().isDirectory()||providerApk.getParentFile().mkdirs(),"update folder creation");File stage=File.createTempFile(".verify-",".apk",providerApk.getParentFile());try{Files.copy(installed.toPath(),stage.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);android.system.Os.rename(stage.getAbsolutePath(),providerApk.getAbsolutePath());created=true;}finally{stage.delete();}
                    if(!externalApk.exists()){check(externalApk.getParentFile().isDirectory()||externalApk.getParentFile().mkdirs(),"raw update folder creation");Files.write(externalApk.toPath(),new byte[]{'B','A','D'});externalCreated=true;}
                    Uri uri=installer.getData();try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(target.getContentResolver().openFileDescriptor(uri,"r"))){check(input.read()=='P'&&input.read()=='K',"provider could not serve real APK");}
                    check(AppUpdate.MIME.equals(target.getContentResolver().getType(uri)),"provider real APK MIME");try{target.getContentResolver().openFileDescriptor(uri,"rw");throw new AssertionError("provider write access accepted");}catch(java.io.FileNotFoundException expected){}
                    int flags=android.os.Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;PackageInfo live=target.getPackageManager().getPackageInfo(target.getPackageName(),flags),archive=target.getPackageManager().getPackageArchiveInfo(providerApk.getAbsolutePath(),flags);
                    check(archive!=null&&AppUpdate.signaturesMatch(android.os.Build.VERSION.SDK_INT>=28?live.signingInfo.getApkContentsSigners():live.signatures,android.os.Build.VERSION.SDK_INT>=28?archive.signingInfo.getApkContentsSigners():archive.signatures),"real installed APK signing identity");
                    try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("cmd package resolve-activity --brief -a android.intent.action.VIEW -t "+AppUpdate.MIME+" -d "+uri))){String handler=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);check(handler.contains("/")&&!handler.contains("No activity"),"system installer unavailable "+handler);report.append("INSTALLER ").append(handler.trim()).append('\n');}
                    report.append("PASS update private APK provider rejects raw tampering, same signing identity and system installer resolution\n");
                }else report.append("SKIP real APK provider smoke: an existing download is preserved\n");
            }finally{if(created)providerApk.delete();if(externalCreated)externalApk.delete();}
            Context isolated=new ContextWrapper(target){@Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("test_update_check_"+name,mode);}@Override public Context getApplicationContext(){return this;}@Override public File getExternalFilesDir(String type){return new File(super.getExternalFilesDir(type),"test-update-check");}@Override public File getFilesDir(){return new File(super.getFilesDir(),"test-update-check");}};
            SharedPreferences saved=isolated.getSharedPreferences("app_update",Context.MODE_PRIVATE);saved.edit().clear().putLong("download_id",Long.MAX_VALUE).putString("download_release",release("99.0.0").toString()).putBoolean("auto_install",true).commit();AppUpdate[] updates={null};
            File badRaw=AppUpdate.apkFile(isolated);check(badRaw.getParentFile().isDirectory()||badRaw.getParentFile().mkdirs(),"isolated update folder creation");Files.write(badRaw.toPath(),new byte[]{'B','A','D'});java.util.concurrent.CountDownLatch validation=new java.util.concurrent.CountDownLatch(1);
            runOnMainSync(()->{updates[0]=new AppUpdate(isolated,()->{if(updates[0]!=null&&!updates[0].verifying)validation.countDown();});updates[0].verify(null);});check(validation.await(15,java.util.concurrent.TimeUnit.SECONDS)&&!updates[0].verified,"bad private snapshot accepted");File[] remnants=AppUpdate.installFile(isolated).getParentFile().listFiles();check(remnants!=null&&remnants.length==0,"failed snapshot was published or not removed");updates[0].close();
            Throwable[] mainError={null};runOnMainSync(()->{try{updates[0]=new AppUpdate(isolated,()->{});check(updates[0].hasDownload()&&updates[0].release.version.equals("99.0.0"),"download not restored");updates[0].cancel();check(!updates[0].hasDownload()&&!updates[0].consumeAutoInstall(),"cancel left pending download");}catch(Throwable error){mainError[0]=error;}finally{if(updates[0]!=null)updates[0].close();}});saved.edit().clear().commit();if(mainError[0]!=null)throw new AssertionError(mainError[0]);
            report.append("PASS update persisted download restoration and cancellation\nALL_APP_UPDATE_CHECKS_PASS\n");result.putString("stream",report.toString());finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("stream",report+"APP_UPDATE_CHECK_FAILED: "+error+"\n");finish(Activity.RESULT_CANCELED,result);}
    }
}
