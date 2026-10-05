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
    private final LruCache<String, String> cache = new LruCache<>(1024);
    LocalEnglishProvider(Context context) { this.context = context.getApplicationContext(); }
    void open() throws Exception {
        File file = new File(context.getFilesDir(), "gloss-en-v1.db");
        if (!file.exists()) {
            File temp = new File(context.getFilesDir(), "gloss-en-v1.tmp");
            try (InputStream in = context.getAssets().open("translation/zh-en.db");
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] bytes = new byte[32768];
                int n;
                while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
                out.getFD().sync();
            }
            if (!temp.renameTo(file)) throw new java.io.IOException("Dictionary install failed");
        }
        database = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
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
    @Override public void close() {
        cache.evictAll();
        if (database != null) { database.close(); database = null; }
    }
}

