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

/** Debug-only update boundary checks. -e webcheck true additionally reads official release metadata; never downloads or installs an APK. */
public final class AppUpdateInstrumentation extends Instrumentation {
    private boolean webcheck;
    private static void check(boolean condition,String note){if(!condition)throw new AssertionError(note);}
    private static JSONObject release(String version)throws Exception{
        return new JSONObject().put("tag_name","v"+version).put("draft",false).put("prerelease",false).put("body","修复候选栏\n改进键盘体验").put("assets",new JSONArray().put(new JSONObject().put("name","Qingyu-"+version+".apk").put("state","uploaded").put("size",1234).put("browser_download_url",AppUpdate.PROJECT_URL+"/releases/download/v"+version+"/Qingyu-"+version+".apk")));
    }
    private static void rejected(JSONObject input)throws Exception{try{AppUpdate.parse(input.toString());throw new AssertionError("unsafe release accepted");}catch(IllegalArgumentException expected){}}
    private static String boundedHeader(String name,String value)throws Exception{
        java.net.HttpURLConnection connection=new java.net.HttpURLConnection(new java.net.URL(AppUpdate.PROJECT_URL)){
            @Override public String getHeaderField(String ignored){return value;}
            @Override public void connect(){} @Override public void disconnect(){} @Override public boolean usingProxy(){return false;}
        };return AppUpdate.header(connection,name);
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);webcheck=args!=null&&"true".equals(args.getString("webcheck"));start();}
    @Override public void onStart(){
        StringBuilder report=new StringBuilder();Bundle result=new Bundle();Context target=getTargetContext();
        try{
            check(AppUpdate.compareVersions("v0.4.0","0.3.0")>0&&AppUpdate.compareVersions("0.10.0","0.9.0")>0&&AppUpdate.compareVersions("1.0.0","1.0.0")==0,"numeric version order");
            check(AppUpdate.normalizedVersion("0.4.0-beta").isEmpty()&&AppUpdate.normalizedVersion("0.04.0").isEmpty()&&AppUpdate.normalizedVersion("999999999999999999999.0.0").isEmpty(),"invalid version accepted");report.append("PASS update numeric versions and invalid tags\n");
            JSONObject root=release("0.4.0");AppUpdate.Release parsed=AppUpdate.parse(root.toString());check(parsed.size==1234&&parsed.version.equals("0.4.0")&&parsed.notes.contains("\n"),"release metadata");
            JSONObject nullable=release("0.4.0").put("body",JSONObject.NULL);nullable.getJSONArray("assets").getJSONObject(0).put("digest",JSONObject.NULL);AppUpdate.Release withoutDigest=AppUpdate.parse(nullable.toString());check(withoutDigest.digest.isEmpty()&&!withoutDigest.notes.equals("null"),"nullable GitHub metadata rejected");
            rejected(release("0.4.0").put("draft",true));rejected(release("0.4.0").put("prerelease",true));JSONObject empty=release("0.4.0");empty.put("assets",new JSONArray());rejected(empty);JSONObject oversize=release("0.4.0");oversize.getJSONArray("assets").getJSONObject(0).put("size",1024L*1024*1024);rejected(oversize);report.append("PASS update stable release, notes, asset name and size\n");
            for(String address:new String[]{"http://github.com/sharbvane/qingyu-srf/releases/download/v1/Qingyu.apk","https://github.com.evil.test/sharbvane/qingyu-srf/releases/download/v1/Qingyu.apk","https://user@github.com/sharbvane/qingyu-srf/releases/download/v1/Qingyu.apk","https://github.com/other/qingyu-srf/releases/download/v1/Qingyu.apk","https://github.com/sharbvane/qingyu-srf/releases/download/%2e%2e/Qingyu.apk","https://github.com/sharbvane/qingyu-srf/releases/download/v1/Qingyu.apk?next=https://evil.test"})check(!AppUpdate.trustedAsset(address),"untrusted asset accepted "+address);check(AppUpdate.trustedAsset(parsed.url),"official asset rejected");report.append("PASS update HTTPS repository and URL boundary\n");
            String tag="v0.5.0",path="/sharbvane/qingyu-srf/releases/download/"+tag+"/Qingyu-0.5.0.apk",sha="sha256:"+"a".repeat(64);
            String webAssets="<li><a href=\""+path+".sha256\">checksum</a><clipboard-copy value=\"sha256:"+"b".repeat(64)+"\"></clipboard-copy></li><li><a href=\""+path+"\">APK</a><clipboard-copy value=\""+sha+"\"></clipboard-copy></li>";
            AppUpdate.Release web=AppUpdate.parseWeb(tag,"<div data-test-selector=\"body-content\"><p>修复 &amp; 优化</p></div>",webAssets,59836541);
            check(web.digest.equals(sha)&&web.size==59836541&&web.notes.contains("修复 & 优化")&&AppUpdate.trustedAsset(web.url),"official web metadata");
            for(String bad:new String[]{"v0.5.0-beta","v0.5.0?x=1","../v0.5.0"})check(AppUpdate.releaseTag(AppUpdate.PROJECT_URL+"/releases/tag/"+bad).isEmpty(),"invalid web tag accepted");check(AppUpdate.releaseTag("https://evil.test/releases/tag/v0.5.0").isEmpty(),"untrusted release redirect accepted");
            try{AppUpdate.parseWeb(tag,"",webAssets.replace(path+"\"",path+".bad\""),59836541);throw new AssertionError("missing APK or neighboring digest accepted");}catch(IllegalArgumentException expected){}
            try{AppUpdate.parseWeb(tag,"",webAssets,-1);throw new AssertionError("unknown exact APK size accepted");}catch(IllegalArgumentException expected){}
            check(AppUpdate.trustedAssetRedirect("https://release-assets.githubusercontent.com/github-production-release-asset/123.apk?sig=a"),"GitHub asset CDN rejected");for(String bad:new String[]{"http://release-assets.githubusercontent.com/x","https://release-assets.githubusercontent.com.evil.test/x","https://user@release-assets.githubusercontent.com/x","https://release-assets.githubusercontent.com:443/x","https://github.com/other/repo/releases/download/v1/a.apk"})check(!AppUpdate.trustedAssetRedirect(bad),"unsafe CDN redirect accepted");
            check(boundedHeader("Location","a".repeat(945)).length()==945&&boundedHeader("location","a".repeat(8192)).length()==8192&&boundedHeader("Location","a".repeat(8193)).isEmpty(),"GitHub signed redirect length bounds");check(boundedHeader("ETag","a".repeat(512)).length()==512&&boundedHeader("ETag","a".repeat(513)).isEmpty()&&boundedHeader("Location",null).isEmpty(),"non-redirect header length or missing header");report.append("PASS update official web fallback notes, exact size, digest row and redirect/header boundaries\n");
            long now=1700000000000L;check(AppUpdate.retryUntil(now,"120","0","1",0)==now+120000,"Retry-After seconds");check(AppUpdate.retryUntil(now,"Wed, 15 Nov 2023 00:13:20 GMT","0","1",0)==now+7200000,"Retry-After date");check(AppUpdate.retryUntil(now,"1",Long.toString(now/1000+3600),"0",0)==now+3600000,"primary rate reset");check(AppUpdate.retryUntil(now,"invalid","invalid","0",2)==now+240000,"secondary exponential pause");check(AppUpdate.retryUntil(now,"9223372036854775807","0","1",0)==now+86400000,"unbounded Retry-After");report.append("PASS update rate-limit seconds, date, reset, bounded exponential cooldown\n");
            check(AppUpdate.signaturesMatch(new Signature[]{new Signature("aa")},new Signature[]{new Signature("aa")}),"matching signature");check(!AppUpdate.signaturesMatch(new Signature[]{new Signature("aa")},new Signature[]{new Signature("bb")})&&!AppUpdate.signaturesMatch(null,new Signature[0]),"mismatched signature accepted");
            Signature ancestor=new Signature("aa"),current=new Signature("bb"),nextSigner=new Signature("cc");check(AppUpdate.authorizedSigner(new Signature[]{current},new Signature[]{nextSigner},new Signature[]{ancestor,current,nextSigner}),"forward rotation rejected");
            check(!AppUpdate.authorizedSigner(new Signature[]{current},new Signature[]{ancestor},new Signature[]{ancestor})&&!AppUpdate.authorizedSigner(new Signature[]{current},new Signature[]{nextSigner},new Signature[]{ancestor,nextSigner})&&!AppUpdate.authorizedSigner(new Signature[]{current},new Signature[]{nextSigner},new Signature[]{ancestor,current})&&!AppUpdate.authorizedSigner(new Signature[]{current},new Signature[]{nextSigner},new Signature[]{current,current,nextSigner})&&!AppUpdate.authorizedSigner(new Signature[]{current},new Signature[]{current,nextSigner},new Signature[]{current,nextSigner}),"reverse, fork, malformed or multiple signer accepted");report.append("PASS update one-way signer rotation rejects shared root, reversed/malformed histories and multiple signers\n");
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
            report.append("PASS update persisted download restoration and cancellation\n");
            saved.edit().putString("release",release("99.0.0").toString()).putLong("checked_at",System.currentTimeMillis()).commit();runOnMainSync(()->{try{updates[0]=new AppUpdate(isolated,()->{});updates[0].check();check(!updates[0].checking&&updates[0].message.contains("刚刚检查"),"rapid cache check started network");}catch(Throwable error){mainError[0]=error;}finally{updates[0].close();}});saved.edit().clear().putLong("web_retry_at",System.currentTimeMillis()+50000).putLong("api_retry_at",System.currentTimeMillis()+300000).commit();runOnMainSync(()->{try{updates[0]=new AppUpdate(isolated,()->{});updates[0].check();check(!updates[0].checking&&updates[0].message.contains("受限"),"cooldown check started network");}catch(Throwable error){mainError[0]=error;}finally{updates[0].close();}});saved.edit().clear().commit();if(mainError[0]!=null)throw new AssertionError(mainError[0]);report.append("PASS update persisted rapid-check cache and limited retry avoid network\n");
            if(webcheck){AppUpdate.Release live=AppUpdate.webRelease();check(AppUpdate.trustedAsset(live.url)&&live.size>0&&live.digest.matches("sha256:[0-9a-fA-F]{64}"),"live official fallback metadata");report.append("PASS update live official web fallback: ").append(live.version).append(" bytes=").append(live.size).append(" digest=").append(live.digest).append('\n');}
            report.append("ALL_APP_UPDATE_CHECKS_PASS\n");result.putString("stream",report.toString());finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("stream",report+"APP_UPDATE_CHECK_FAILED: "+error+"\n");finish(Activity.RESULT_CANCELED,result);}
    }
}
