package com.qingyu.ime;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

final class ImePreferences {
    static final float MIN_HEIGHT = 0.78f, MAX_HEIGHT = 1.24f;
    final SharedPreferences store;
    ImePreferences(Context c) { store = c.getSharedPreferences("qingyu", Context.MODE_PRIVATE); }
    boolean translation() { return store.getBoolean("translation", true); }
    boolean haptic() { return store.getBoolean("haptic", true); }
    boolean preview() { return store.getBoolean("preview", true); }
    boolean learning() { return store.getBoolean("learning", true); }
    boolean clipboard() { return store.getBoolean("clipboard", true); }
    float height() {
        float height = store.getFloat("height", 1f);
        return Float.isFinite(height) ? Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, height)) : 1f;
    }
    String glossLanguage() {
        String language = store.getString("gloss_language", "en");
        return TranslationRepository.isGlossLanguage(language) ? language : "en";
    }
    String keyboardMode() { return "t9".equals(store.getString("keyboard_mode", "full")) ? "t9" : "full"; }
    boolean nineKey() { return keyboardMode().equals("t9"); }
    String style() { return "flat".equals(store.getString("style", "classic")) ? "flat" : "classic"; }
    boolean dark(Context c) {
        String theme = store.getString("theme", "auto");
        return theme.equals("dark") || (theme.equals("auto") &&
            (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
    }
}

