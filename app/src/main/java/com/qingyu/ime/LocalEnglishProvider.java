package com.qingyu.ime;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.LruCache;
import com.qingyu.core.translation.TranslationProvider;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;

/** Own worker only: neither asset copy nor SQLite lookup ever runs on the IME thread. */
final class LocalEnglishProvider implements TranslationProvider {
    private final Context context;
    private SQLiteDatabase database;
    private SQLiteDatabase details;
    private final LruCache<String, String> cache = new LruCache<>(1024);
    LocalEnglishProvider(Context context) { this.context = context.getApplicationContext(); }
    void open() throws Exception {
        database = openAsset("translation/zh-en.db", "gloss-en-v1.db");
        try { details = openAsset("translation/zh-en-details.db", "gloss-en-details-v1.db"); }
        catch (Exception ignored) { /* Detailed senses never block the compact candidate glosses. */ }
    }
    private SQLiteDatabase openAsset(String asset, String name) throws Exception {
        File file = new File(context.getFilesDir(), name);
        if (!file.exists()) {
            File temp = new File(context.getFilesDir(), name+".tmp");
            try (InputStream in = context.getAssets().open(asset);
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] bytes = new byte[32768];
                int n;
                while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
                out.getFD().sync();
            }
            if (!temp.renameTo(file)) throw new java.io.IOException("Dictionary install failed");
        }
        return SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
    }
    @Override public String targetLanguage() { return "en"; }
    @Override public String lookup(String chinese) {
        if (database == null) return "";
        String cached = cache.get(chinese);
        if (cached != null) return cached;
        String result = "";
        try (Cursor c = database.rawQuery("SELECT en FROM gloss WHERE zh=?", new String[]{chinese})) {
            if (c.moveToFirst()) result = c.getString(0);
        }
        cache.put(chinese, result);
        return result;
    }
    String lookupDetails(String chinese) {
        if (details == null) return "";
        try (Cursor c = details.rawQuery("SELECT pinyin,meanings FROM details WHERE zh=?", new String[]{chinese})) {
            return c.moveToFirst() ? c.getString(0) + "\n\n" + c.getString(1) : "";
        }
    }
    @Override public void close() {
        cache.evictAll();
        if (database != null) { database.close(); database = null; }
        if (details != null) { details.close(); details = null; }
    }
}

