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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit update requests; DownloadManager owns persistence and background downloading. */
final class AppUpdate {
    static final String PROJECT_URL="https://github.com/sharbvane/qingyu-srf";
    static final String API_URL="https://api.github.com/repos/sharbvane/qingyu-srf/releases/latest";
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
        if(checking||hasDownload())return;checking=true;message="正在检查最新版本…";notifyChanged();long request=++generation;
        worker.execute(()->{
            Release found=null;String failure=null;HttpURLConnection connection=null;
            try{
                connection=(HttpURLConnection)new URL(API_URL).openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(12000);connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept","application/vnd.github+json");connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");connection.setRequestProperty("User-Agent","Qingyu-Android");
                int code=connection.getResponseCode();if(code!=200)throw new IllegalArgumentException(code==403||code==429?"GitHub 请求暂时受限，请稍后重试。":code==404?"项目尚未发布可下载的正式版本。":"检查失败（HTTP "+code+"），请稍后重试。");
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(output.size()+count>512*1024)throw new IllegalArgumentException("发布信息过大，请前往项目主页查看。");output.write(buffer,0,count);}found=parse(new String(output.toByteArray(),StandardCharsets.UTF_8));}
            }catch(Exception error){failure=error instanceof IllegalArgumentException?error.getMessage():"无法连接 GitHub，请检查网络后重试。";}finally{if(connection!=null)connection.disconnect();}
            Release result=found;String error=failure;main.post(()->{if(closed||request!=generation)return;checking=false;if(result!=null){release=result;prefs.edit().putString("release",result.json).apply();message=newer()?"发现新版本":"当前已是最新版本";}else message=error;notifyChanged();});
        });
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
        if(!signaturesMatch(old,newer))throw new IllegalArgumentException("安装包签名与当前应用不一致，已阻止安装。");
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
