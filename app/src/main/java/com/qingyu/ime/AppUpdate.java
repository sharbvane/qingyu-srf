package com.qingyu.ime;

import android.app.DownloadManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit update requests; DownloadManager owns persistence and background downloading. */
final class AppUpdate {
    static final String PROJECT_URL="https://github.com/sharbvane/qingyu-srf";
    static final String API_URL="https://api.github.com/repos/sharbvane/qingyu-srf/releases/latest";
    private static final long CHECK_INTERVAL=60_000L;
    static final String MIME="application/vnd.android.package-archive";
    private final Context context;
    private final SharedPreferences prefs;
    private final DownloadManager manager;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private volatile boolean closed;
    private boolean querying,verificationFailed;
    private volatile long generation;
    boolean checking,verifying,verified;
    int downloadStatus;
    long downloaded,total;
    String message="点击检查，获取 GitHub 上的最新正式版本。";
    Release release;

    static final class Release {
        final String version,notes,url,digest,json;
        final long size;
        Release(String version,String notes,String url,long size,String digest,String json){this.version=version;this.notes=notes;this.url=url;this.size=size;this.digest=digest;this.json=json;}
    }
    AppUpdate(Context context,Runnable changed){
        this.context=context.getApplicationContext();this.changed=changed;
        prefs=this.context.getSharedPreferences("app_update",Context.MODE_PRIVATE);
        manager=(DownloadManager)this.context.getSystemService(Context.DOWNLOAD_SERVICE);
        String saved=prefs.getString(prefs.getLong("download_id",0)>0?"download_release":"release","");
        try{if(!saved.isEmpty())release=parse(saved);}catch(Exception ignored){}
        if(release!=null&&hasDownload()&&!newer()){
            try{if(manager!=null)manager.remove(prefs.getLong("download_id",0));}catch(RuntimeException ignored){}
            prefs.edit().remove("download_id").remove("download_release").remove("auto_install").remove("install_pending").apply();
            try{File file=apkFile(this.context);if(file.exists())file.delete();}catch(Exception ignored){}
            removeInstallFiles(this.context);
            message="已更新至 "+currentVersion();
        }
    }
    static String normalizedVersion(String text){
        String value=text==null?"":text.trim();if(value.startsWith("v")||value.startsWith("V"))value=value.substring(1);
        return value.matches("(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})")?value:"";
    }
    static int compareVersions(String left,String right){
        String a=normalizedVersion(left),b=normalizedVersion(right);if(a.isEmpty()||b.isEmpty())throw new IllegalArgumentException("无法识别版本号");
        String[] first=a.split("\\."),second=b.split("\\.");for(int i=0;i<3;i++){int comparison=Integer.compare(Integer.parseInt(first[i]),Integer.parseInt(second[i]));if(comparison!=0)return comparison;}return 0;
    }
    static boolean trustedAsset(String address){
        try{URI uri=new URI(address);String prefix="/sharbvane/qingyu-srf/releases/download/";String path=uri.getRawPath();
            return "https".equals(uri.getScheme())&&"github.com".equals(uri.getHost())&&uri.getUserInfo()==null&&uri.getPort()==-1&&uri.getRawQuery()==null&&uri.getRawFragment()==null&&path!=null&&path.startsWith(prefix)&&path.substring(prefix.length()).matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.apk")&&!path.contains("..");
        }catch(Exception error){return false;}
    }
    static Release parse(String json)throws Exception{
        JSONObject root=new JSONObject(json);String version=normalizedVersion(root.optString("tag_name"));
        if(root.optBoolean("draft")||root.optBoolean("prerelease")||version.isEmpty())throw new IllegalArgumentException("没有可用的正式版本");
        JSONArray assets=root.optJSONArray("assets");if(assets!=null)for(int i=0;i<assets.length();i++){
            JSONObject asset=assets.getJSONObject(i);String url=asset.optString("browser_download_url"),name=asset.optString("name"),digest=asset.isNull("digest")?"":asset.optString("digest");long size=asset.optLong("size");
            if(!name.equalsIgnoreCase("Qingyu-"+version+".apk")||!asset.optString("state").equals("uploaded")||!trustedAsset(url)||!url.endsWith("/"+name)||size<1||size>512L*1024*1024)continue;
            if(!digest.isEmpty()&&!digest.matches("sha256:[0-9a-fA-F]{64}"))throw new IllegalArgumentException("安装包校验信息不受支持");
            return new Release(version,root.isNull("body")?"暂无更新说明。":root.optString("body","暂无更新说明。"),url,size,digest,json);
        }
        throw new IllegalArgumentException("此版本尚未提供轻语 Android 安装包");
    }
    String currentVersion(){try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception error){return "0.0.0";}}
    boolean newer(){return release!=null&&compareVersions(release.version,currentVersion())>0;}
    boolean active(){return downloadStatus==DownloadManager.STATUS_PENDING||downloadStatus==DownloadManager.STATUS_RUNNING||downloadStatus==DownloadManager.STATUS_PAUSED;}
    boolean hasDownload(){return prefs.getLong("download_id",0)>0;}
    private void notifyChanged(){if(!closed)changed.run();}
    void check(){
        if(checking||hasDownload())return;
        long now=System.currentTimeMillis(),checked=prefs.getLong("checked_at",0),retry=prefs.getLong("web_retry_at",0);
        if(release!=null&&now>=checked&&now-checked<CHECK_INTERVAL){message="使用刚刚检查的发布信息";notifyChanged();return;}
        if(retry>now&&retry-now<=CHECK_INTERVAL){long apiRetry=prefs.getLong("api_retry_at",0);message=(apiRetry>now?limitedMessage(apiRetry,now):"刚刚检查未成功，请在约 1 分钟后重试。")+(release==null?"":" 已显示上次成功检查的版本，尚未确认最新。");notifyChanged();return;}
        checking=true;message="正在检查最新版本…";notifyChanged();long request=++generation;
        worker.execute(()->{
            Release found=null;String failure=null,etag="",modified="";boolean web=false,apiSuccess=false;long retryAt=prefs.getLong("api_retry_at",0);int failures=prefs.getInt("api_failures",0);HttpURLConnection connection=null;
            try{
                long started=System.currentTimeMillis();if(retryAt<=started||retryAt-started>86400_000L)retryAt=0;
                if(retryAt>started&&retryAt-started<=86400_000L){web=true;found=webRelease();}
                else{
                    connection=open(API_URL,"GET");connection.setRequestProperty("Accept","application/vnd.github+json");connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
                    String apiJson=prefs.getString("api_release","");Release cached=null;try{if(!apiJson.isEmpty())cached=parse(apiJson);}catch(Exception ignored){}
                    if(cached!=null){etag=prefs.getString("etag","");modified=prefs.getString("last_modified","");if(!etag.isEmpty())connection.setRequestProperty("If-None-Match",etag);else if(!modified.isEmpty())connection.setRequestProperty("If-Modified-Since",modified);}
                    int code=connection.getResponseCode();
                    if(code==200){found=parse(read(connection));etag=header(connection,"ETag");modified=header(connection,"Last-Modified");apiSuccess=true;retryAt=0;failures=0;}
                    else if(code==304&&cached!=null){found=cached;apiSuccess=true;retryAt=0;failures=0;}
                    else if(code==403||code==429){retryAt=retryUntil(started,header(connection,"Retry-After"),header(connection,"X-RateLimit-Reset"),header(connection,"X-RateLimit-Remaining"),failures);failures=Math.min(6,failures+1);connection.disconnect();connection=null;web=true;found=webRelease();}
                    else throw new IllegalArgumentException(code==404?"项目尚未发布可下载的正式版本。":"检查失败（HTTP "+code+"），请稍后重试。");
                }
            }catch(Exception error){failure=error instanceof IllegalArgumentException?error.getMessage():"无法连接 GitHub，请检查网络后重试。";}finally{if(connection!=null)connection.disconnect();}
            Release result=found;String error=failure,newEtag=etag,newModified=modified;boolean viaWeb=web,viaApi=apiSuccess;long nextRetry=retryAt;int failureCount=failures;
            main.post(()->{if(closed||request!=generation)return;checking=false;long finished=System.currentTimeMillis();SharedPreferences.Editor saved=prefs.edit().putLong("api_retry_at",nextRetry).putInt("api_failures",failureCount);
                if(result!=null){release=result;saved.putString("release",result.json).putLong("checked_at",finished).remove("web_retry_at");if(viaApi)saved.putString("api_release",result.json).putString("etag",newEtag).putString("last_modified",newModified);message=(newer()?"发现新版本":"当前已是最新版本")+(viaWeb?"（已通过官方发布页检查）":"");}
                else{saved.putLong("web_retry_at",finished+CHECK_INTERVAL);message=(nextRetry>finished?limitedMessage(nextRetry,finished)+" 官方发布页暂时无法读取。":error)+(release==null?"":" 已显示上次成功检查的版本，尚未确认最新。");}
                saved.apply();notifyChanged();});
        });
    }
    static String header(HttpURLConnection connection,String name){String value=connection.getHeaderField(name);int limit="Location".equalsIgnoreCase(name)?8192:512;return value==null||value.length()>limit?"":value;}
    private static HttpURLConnection open(String address,String method)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(12000);connection.setReadTimeout(15000);connection.setRequestMethod(method);connection.setRequestProperty("User-Agent","Qingyu-Android");return connection;
    }
    private static String read(HttpURLConnection connection)throws Exception{
        try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();if(output.size()+count>512*1024)throw new IllegalArgumentException("发布信息过大，请前往项目主页查看。");output.write(buffer,0,count);}return new String(output.toByteArray(),StandardCharsets.UTF_8);}
    }
    static long retryUntil(long now,String after,String reset,String remaining,int failures){
        long wait=0;
        try{long seconds=Long.parseLong(after);if(seconds>0)wait=Math.min(seconds,86400L)*1000L;}catch(Exception ignored){try{java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z",Locale.US);format.setLenient(false);java.util.Date date=format.parse(after);if(date!=null)wait=Math.max(0,date.getTime()-now);}catch(Exception ignoredDate){}}
        if("0".equals(remaining))try{long seconds=Long.parseLong(reset);if(seconds>0&&seconds<Long.MAX_VALUE/1000)wait=Math.max(wait,seconds*1000-now);}catch(Exception ignored){}
        if(wait<=0)wait=CHECK_INTERVAL*(1L<<Math.max(0,Math.min(6,failures)));
        return now+Math.min(86400_000L,Math.max(CHECK_INTERVAL,wait));
    }
    private static String limitedMessage(long until,long now){long minutes=Math.max(1,(Math.max(0,until-now)+59999)/60000);return "GitHub API 暂时受限，约 "+minutes+" 分钟后可重试。";}
    static String releaseTag(String address){
        String prefix=PROJECT_URL+"/releases/tag/";if(address==null||!address.startsWith(prefix))return "";String tag=address.substring(prefix.length());return normalizedVersion(tag).isEmpty()?"":tag;
    }
    static boolean trustedAssetRedirect(String address){
        try{URI uri=new URI(address);return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&uri.getPort()==-1&&uri.getRawFragment()==null&&("release-assets.githubusercontent.com".equals(uri.getHost())||trustedAsset(address));}catch(Exception ignored){return false;}
    }
    static Release parseWeb(String tag,String page,String assets,long size)throws Exception{
        String version=normalizedVersion(tag);if(version.isEmpty()||!tag.matches("[A-Za-z0-9._-]+"))throw new IllegalArgumentException("无法识别官方发布版本。");
        // ponytail: GitHub's bounded server-rendered asset rows; fail closed if
        // their markup changes. Replace with an official manifest if it becomes unstable.
        String name="Qingyu-"+version+".apk",path="/sharbvane/qingyu-srf/releases/download/"+tag+"/"+name;Matcher rows=Pattern.compile("(?s)<li\\b[^>]*>.*?</li>").matcher(assets);String digest="";
        while(rows.find()){String row=rows.group();if(!row.contains("href=\""+path+"\""))continue;Matcher sha=Pattern.compile("value=\"(sha256:[0-9a-fA-F]{64})\"").matcher(row);if(sha.find())digest=sha.group(1);break;}
        if(digest.isEmpty())throw new IllegalArgumentException("官方发布页缺少安装包校验信息，请稍后重试。");
        Matcher notes=Pattern.compile("(?s)<div\\b[^>]*data-test-selector=\"body-content\"[^>]*>(.*?)</div>").matcher(page);String body=notes.find()?android.text.Html.fromHtml(notes.group(1),android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim():"更新说明暂时无法读取，请查看项目主页。";
        JSONObject asset=new JSONObject().put("name",name).put("state","uploaded").put("browser_download_url","https://github.com"+path).put("size",size).put("digest",digest);
        return parse(new JSONObject().put("tag_name",tag).put("draft",false).put("prerelease",false).put("body",body).put("assets",new JSONArray().put(asset)).toString());
    }
    static Release webRelease()throws Exception{
        HttpURLConnection connection=null;String tag,page,assets;
        try{
            connection=open(PROJECT_URL+"/releases/latest","GET");int code=connection.getResponseCode();if(code!=301&&code!=302&&code!=303&&code!=307&&code!=308)throw new IllegalArgumentException("官方发布页暂时不可用。");
            String address=new URL(connection.getURL(),header(connection,"Location")).toString();tag=releaseTag(address);if(tag.isEmpty())throw new IllegalArgumentException("官方发布页未提供正式版本。");connection.disconnect();connection=open(address,"GET");if(connection.getResponseCode()!=200)throw new IllegalArgumentException("官方更新说明暂时不可用。");page=read(connection);
            connection.disconnect();connection=open(PROJECT_URL+"/releases/expanded_assets/"+tag,"GET");if(connection.getResponseCode()!=200)throw new IllegalArgumentException("官方安装包信息暂时不可用。");assets=read(connection);
            // GitHub's human-readable rounded size is unsuitable for archive validation.
            String addressApk=PROJECT_URL+"/releases/download/"+tag+"/Qingyu-"+normalizedVersion(tag)+".apk";long size=-1;
            for(int redirects=0;redirects<5;redirects++){
                connection.disconnect();connection=open(addressApk,"HEAD");int status=connection.getResponseCode();
                if(status==200){size=connection.getContentLengthLong();break;}
                if(status!=301&&status!=302&&status!=303&&status!=307&&status!=308)throw new IllegalArgumentException("正式安装包暂时不可用。");
                addressApk=new URL(connection.getURL(),header(connection,"Location")).toString();if(!trustedAssetRedirect(addressApk))throw new IllegalArgumentException("安装包跳转地址不可信。");
            }
            return parseWeb(tag,page,assets,size);
        }finally{if(connection!=null)connection.disconnect();}
    }
    void download(){
        if(release==null||!newer()||hasDownload()||manager==null)return;
        try{
            File file=apkFile(context);File folder=file.getParentFile();if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException();if(file.exists()&&!file.delete())throw new IllegalStateException();
            // Completed APK notifications open files directly, bypassing our signature checks.
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(release.url)).setTitle("轻语 "+release.version).setDescription("下载新版输入法").setMimeType(MIME).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE).setVisibleInDownloadsUi(false).setAllowedOverRoaming(false).setDestinationUri(Uri.fromFile(file));
            long id=manager.enqueue(request);prefs.edit().putLong("download_id",id).putString("download_release",release.json).putBoolean("auto_install",true).apply();verified=false;verificationFailed=false;downloadStatus=DownloadManager.STATUS_PENDING;message="等待下载…";notifyChanged();refresh();
        }catch(Exception error){message="无法开始下载，请检查网络和可用存储后重试。";notifyChanged();}
    }
    void cancel(){
        generation++;checking=false;verifying=false;verified=false;verificationFailed=false;querying=false;
        long id=prefs.getLong("download_id",0);try{if(manager!=null&&id>0)manager.remove(id);}catch(RuntimeException error){message="暂时无法取消下载，请稍后重试。";notifyChanged();return;}
        prefs.edit().remove("download_id").remove("download_release").remove("auto_install").remove("install_pending").apply();
        File file;try{file=apkFile(context);if(file.exists())file.delete();}catch(Exception ignored){}
        removeInstallFiles(context);
        downloadStatus=0;downloaded=total=0;message="下载已取消";notifyChanged();
    }
    void refresh(){
        if(closed||querying||verifying||!hasDownload()||manager==null)return;
        querying=true;long id=prefs.getLong("download_id",0),request=generation;
        worker.execute(()->{
            int status=0,reason=0;long bytes=0,size=0;
            try(Cursor cursor=manager.query(new DownloadManager.Query().setFilterById(id))){if(cursor!=null&&cursor.moveToFirst()){status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));reason=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));bytes=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));size=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));}}
            catch(Exception ignored){}
            int state=status,error=reason;long received=bytes,length=size;
            main.post(()->{if(closed||request!=generation)return;querying=false;downloadStatus=state;downloaded=received;total=length;
                if(state==DownloadManager.STATUS_SUCCESSFUL){if(verified)message="下载完成，可安装更新";else if(!verificationFailed){message="下载完成，正在校验安装包…";verify(null);}}
                else if(state==DownloadManager.STATUS_FAILED)message=error==DownloadManager.ERROR_INSUFFICIENT_SPACE?"存储空间不足，请清理后重新下载。":"下载失败（"+error+"），请重新下载。";
                else if(state==DownloadManager.STATUS_PAUSED)message="下载已暂停，联网后会自动继续";
                else if(state==DownloadManager.STATUS_RUNNING)message="正在下载安装包…";
                else if(state==DownloadManager.STATUS_PENDING)message="等待下载…";
                else{message="下载记录已失效，请重新下载。";prefs.edit().remove("download_id").remove("auto_install").apply();}
                notifyChanged();
            });
        });
    }
    void verify(Runnable accepted){
        if(verifying||release==null)return;verifying=true;long request=generation;Release expected=release;notifyChanged();
        worker.execute(()->{
            String error=null;File temporary=null;
            try{
                File destination=installFile(context),folder=destination.getParentFile();if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("无法保存安装包，请检查可用存储。");
                temporary=File.createTempFile(".verify-",".apk",folder);
                // On older Android, other storage-authorized apps can modify the raw download.
                // Verify a private snapshot and expose only that snapshot to the installer.
                try(InputStream input=new FileInputStream(apkFile(context));FileOutputStream output=new FileOutputStream(temporary)){
                    byte[] buffer=new byte[65536];int count;long copied=0;
                    while((count=input.read(buffer))!=-1){if(closed||request!=generation||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("更新校验已取消");copied+=count;if(copied>expected.size)throw new IllegalArgumentException("安装包不完整，请重新下载。");output.write(buffer,0,count);}
                    output.getFD().sync();
                }
                validateArchive(context,temporary,expected);
                if(closed||request!=generation)throw new java.io.InterruptedIOException("更新校验已取消");
                android.system.Os.rename(temporary.getAbsolutePath(),destination.getAbsolutePath());temporary=null;
            }catch(Exception failure){error=failure.getMessage()==null?"安装包校验失败，请重新下载。":failure.getMessage();}
            finally{if(temporary!=null)temporary.delete();}
            String result=error;
            main.post(()->{if(closed||request!=generation)return;verifying=false;verified=result==null;verificationFailed=result!=null;message=result==null?"下载完成，可安装更新":result;if(result!=null)prefs.edit().remove("auto_install").remove("install_pending").apply();notifyChanged();if(result==null&&accepted!=null)accepted.run();});
        });
    }
    static void validateArchive(Context context,File file,Release release)throws Exception{
        if(!file.isFile()||file.length()!=release.size)throw new IllegalArgumentException("安装包不完整，请重新下载。");
        if(!release.digest.isEmpty()){
            MessageDigest sha=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(file)){byte[] bytes=new byte[65536];int count;while((count=input.read(bytes))!=-1)sha.update(bytes,0,count);}StringBuilder hex=new StringBuilder();for(byte b:sha.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));if(!hex.toString().equalsIgnoreCase(release.digest.substring(7)))throw new IllegalArgumentException("安装包校验不匹配，请重新下载。");
        }
        PackageManager pm=context.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo installed=pm.getPackageInfo(context.getPackageName(),flags),archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags);
        if(archive==null||!context.getPackageName().equals(archive.packageName)||!release.version.equals(normalizedVersion(archive.versionName)))throw new IllegalArgumentException("安装包不属于此轻语版本，已阻止安装。");
        long next=Build.VERSION.SDK_INT>=28?archive.getLongVersionCode():archive.versionCode,current=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;
        if(next<=current||compareVersions(archive.versionName,installed.versionName)<=0)throw new IllegalArgumentException("此安装包不是更新版本，无需安装。");
        Signature[] old=Build.VERSION.SDK_INT>=28&&installed.signingInfo!=null?installed.signingInfo.getApkContentsSigners():installed.signatures,newer=Build.VERSION.SDK_INT>=28&&archive.signingInfo!=null?archive.signingInfo.getApkContentsSigners():archive.signatures;
        boolean signed=Build.VERSION.SDK_INT>=28?authorizedSigner(old,newer,archive.signingInfo==null||archive.signingInfo.hasMultipleSigners()?null:archive.signingInfo.getSigningCertificateHistory()):signaturesMatch(old,newer);
        if(!signed)throw new IllegalArgumentException("安装包签名与当前应用不一致，已阻止安装。");
    }
    // PackageManager verifies APK v3 proof-of-rotation; only a forward chain from
    // the currently installed signer is accepted, never shared historic roots.
    static boolean authorizedSigner(Signature[] installed,Signature[] archive,Signature[] verifiedHistory){
        if(installed==null||archive==null||installed.length!=1||archive.length!=1||installed[0]==null||archive[0]==null)return false;
        if(installed[0].equals(archive[0]))return true;
        if(verifiedHistory==null||verifiedHistory.length<2||!archive[0].equals(verifiedHistory[verifiedHistory.length-1]))return false;
        boolean authorized=false;for(int i=0;i<verifiedHistory.length;i++){if(verifiedHistory[i]==null)return false;for(int j=0;j<i;j++)if(verifiedHistory[i].equals(verifiedHistory[j]))return false;if(i<verifiedHistory.length-1&&installed[0].equals(verifiedHistory[i]))authorized=true;}return authorized;
    }
    static boolean signaturesMatch(Signature[] installed,Signature[] archive){if(installed==null||archive==null||installed.length==0||installed.length!=archive.length)return false;for(Signature signer:installed)if(signer==null||!Arrays.asList(archive).contains(signer))return false;return true;}
    boolean consumeAutoInstall(){boolean value=prefs.getBoolean("auto_install",false);if(value&&verified)prefs.edit().remove("auto_install").apply();return value&&verified;}
    boolean installPending(){return prefs.getBoolean("install_pending",false);}
    void installPending(boolean value){prefs.edit().putBoolean("install_pending",value).apply();}
    static Intent installIntent(Context context){return new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://"+context.getPackageName()+".updates/update.apk"),MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);}
    void close(){closed=true;worker.shutdownNow();main.removeCallbacksAndMessages(null);}
    static File apkFile(Context context){File folder=context.getExternalFilesDir("updates");if(folder==null)throw new IllegalStateException("应用存储不可用");return new File(folder,"update.apk");}
    static File installFile(Context context){return new File(new File(context.getFilesDir(),"updates"),"verified.apk");}
    private static void removeInstallFiles(Context context){File[] files=installFile(context).getParentFile().listFiles(file->file.getName().equals("verified.apk")||file.getName().startsWith(".verify-")&&file.getName().endsWith(".apk"));if(files!=null)for(File file:files)if(file.isFile())file.delete();}

    /** Grants the system installer read access to exactly one file, never a directory. */
    public static final class ApkProvider extends ContentProvider {
        @Override public boolean onCreate(){return true;}
        private File file(Uri uri)throws FileNotFoundException{
            if(!"content".equals(uri.getScheme())||!(getContext().getPackageName()+".updates").equals(uri.getAuthority())||!"/update.apk".equals(uri.getEncodedPath())||uri.getQuery()!=null||uri.getFragment()!=null)throw new FileNotFoundException();
            try{File file=installFile(getContext()),canonical=file.getCanonicalFile();if(!file.isFile()||!canonical.getParentFile().equals(file.getParentFile().getCanonicalFile())||!canonical.getName().equals("verified.apk"))throw new FileNotFoundException();return canonical;}catch(Exception error){throw new FileNotFoundException();}
        }
        @Override public String getType(Uri uri){try{file(uri);return MIME;}catch(Exception error){return null;}}
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
            try{File file=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]="Qingyu-update.apk";else if(OpenableColumns.SIZE.equals(columns[i]))values[i]=file.length();}cursor.addRow(values);return cursor;}catch(Exception error){return null;}
        }
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
        @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("read only");}
        @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("read only");}
        @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("read only");}
    }
}
