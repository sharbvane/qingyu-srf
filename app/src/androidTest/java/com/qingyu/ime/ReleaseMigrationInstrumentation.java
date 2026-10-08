package com.qingyu.ime;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Framework-only seed before a system APK upgrade; never changes IME settings. */
public final class ReleaseMigrationInstrumentation extends Instrumentation {
    private static final String LEGACY = "12794d0f0be3a864281e828ad389f8e71bb725e196cb4bf65c4937069452d6d9";
    private static final String RELEASE = "1d6872a840fe42468cdc5af2d11eeee87455c4b635e2fd2b60168b082c4e0cf5";
    private String phase;
    private int fromVersion;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        phase = args == null ? "" : args.getString("phase", "");
        fromVersion=args==null?4:Integer.parseInt(args.getString("from_version","4"));
        start();
    }
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private static String digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        return hex(hash.digest());
    }
    private static String certificate(Signature signer) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()));
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); StringBuilder report = new StringBuilder();
        try {
            check(phase.equals("seed") || phase.equals("verify"), "Use -e phase seed or verify");
            Context context = getTargetContext();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), flags);
            Signature[] current = Build.VERSION.SDK_INT >= 28 ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
            check(current != null && current.length == 1, "Expected one current signing certificate");
            check(installed.packageName.equals("com.qingyu.ime"), "Unexpected target package");
            check(fromVersion==4||fromVersion==5,"Supported original versions are 4 and 5");
            SharedPreferences saved = context.getSharedPreferences("test_release_migration"+(fromVersion==4?"":"_"+fromVersion), Context.MODE_PRIVATE);
            File sentinel = new File(context.getFilesDir(), ".qingyu-release-migration-sentinel"+(fromVersion==4?"":"-"+fromVersion));
            File dictionary = new File(context.getFilesDir(), "user-pinyin.dat");
            if (phase.equals("seed")) {
                check(installed.versionCode == fromVersion && installed.versionName.equals("0."+fromVersion+".0") && certificate(current[0]).equals(fromVersion==4?LEGACY:RELEASE), "Seed requires the original signed Release");
                check(!sentinel.exists() && !saved.contains("marker"), "Migration seed already exists; refusing to replace it");
                String marker = UUID.randomUUID().toString();
                try (FileOutputStream output = new FileOutputStream(sentinel)) {
                    output.write(("Qingyu release migration " + marker).getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
                SharedPreferences.Editor seed=saved.edit();
                for(String name:new String[]{"pinyin-learning-v1.tsv","chinese-learning-v2.tsv","english-learning-v2.tsv"}){File learning=new File(context.getFilesDir(),name);seed.putString(name,learning.isFile()?digest(learning):"");}
                check(seed.putString("marker", marker).putString("sentinel_sha256", digest(sentinel))
                        .putString("dictionary_sha256", dictionary.isFile() ? digest(dictionary) : "")
                        .putInt("uid", context.getApplicationInfo().uid).commit(), "Migration marker commit failed");
                report.append("PASS original v0.").append(fromVersion).append(" signing identity and version\n")
                        .append("PASS app-private preference/file sentinel seeded; existing user dictionary only read\n")
                        .append("RELEASE_MIGRATION_SEED_PASS\n");
            } else {
                check(installed.versionCode == fromVersion+1 && installed.versionName.equals("0."+(fromVersion+1)+".0"), "Verify requires the next Release");
                if (Build.VERSION.SDK_INT >= 28) {
                    check(!installed.signingInfo.hasMultipleSigners() && certificate(current[0]).equals(RELEASE), "Independent Release signing identity missing");
                    Signature[] history = installed.signingInfo.getSigningCertificateHistory();
                    check(history != null && history.length == 2 && certificate(history[0]).equals(LEGACY)
                            && certificate(history[1]).equals(RELEASE), "Authenticated old-to-new signing history missing");
                    report.append("PASS independent Release current signer and authenticated v0.4 history\n");
                } else {
                    check(certificate(current[0]).equals(LEGACY), "Android 8 compatibility signer missing");
                    report.append("PASS Android 8 compatibility signer\n");
                }
                String marker = saved.getString("marker", "");
                String expectedSentinel = hex(MessageDigest.getInstance("SHA-256").digest(("Qingyu release migration " + marker).getBytes(StandardCharsets.UTF_8)));
                check(!marker.isEmpty() && sentinel.isFile() && digest(sentinel).equals(expectedSentinel)
                        && expectedSentinel.equals(saved.getString("sentinel_sha256", "")), "App-private preference/file lost during upgrade");
                check(saved.getInt("uid", -1) == context.getApplicationInfo().uid, "Application UID changed during upgrade");
                String dictionaryHash = saved.getString("dictionary_sha256", "");
                if (!dictionaryHash.isEmpty()) check(dictionary.isFile() && digest(dictionary).equals(dictionaryHash), "Existing user dictionary changed during migration");
                for(String name:new String[]{"pinyin-learning-v1.tsv","chinese-learning-v2.tsv","english-learning-v2.tsv"}){String expected=saved.getString(name,"");if(!expected.isEmpty()){File learning=new File(context.getFilesDir(),name);check(learning.isFile()&&digest(learning).equals(expected),"Existing learning records changed during migration: "+name);}}
                report.append("PASS preserved app-private preferences, file sentinel and application UID\n")
                        .append("PASS existing personalization files preserved when present (content and digest not logged)\n")
                        .append(dictionaryHash.isEmpty() ? "SKIP existing dictionary byte check: absent at seed\n" : "PASS existing user dictionary bytes preserved (content and digest not logged)\n")
                        .append("RELEASE_MIGRATION_VERIFY_PASS\n");
            }
            result.putString("stream", report.toString()); finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", report + "RELEASE_MIGRATION_FAILED: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
