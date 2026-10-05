package com.qingyu.ime;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

final class ImePreferences {
    final SharedPreferences store;
    ImePreferences(Context c) { store = c.getSharedPreferences("qingyu", Context.MODE_PRIVATE); }
    boolean translation() { return store.getBoolean("translation", true); }
    boolean haptic() { return store.getBoolean("haptic", true); }
    boolean preview() { return store.getBoolean("preview", true); }
    boolean learning() { return store.getBoolean("learning", true); }
    float height() { return store.getFloat("height", 1f); }
    boolean dark(Context c) {
        String theme = store.getString("theme", "auto");
        return theme.equals("dark") || (theme.equals("auto") &&
            (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
    }
}

