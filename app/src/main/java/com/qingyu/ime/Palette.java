package com.qingyu.ime;

import android.graphics.Color;

final class Palette {
    final int background, key, function, text, secondary, accent, accentText, border, pressed;
    Palette(boolean dark) {
        background = Color.parseColor(dark ? "#191E1C" : "#EFF1EC");
        key = Color.parseColor(dark ? "#303833" : "#FFFFFF");
        function = Color.parseColor(dark ? "#242D27" : "#DDE4DA");
        text = Color.parseColor(dark ? "#F0F3EE" : "#202B24");
        secondary = Color.parseColor(dark ? "#9CAAA0" : "#687D6D");
        accent = Color.parseColor(dark ? "#BAD4A8" : "#476B57");
        accentText = Color.parseColor(dark ? "#233323" : "#FFFFFF");
        border = Color.parseColor(dark ? "#39433C" : "#D9DFD5");
        pressed = Color.parseColor(dark ? "#52654E" : "#C6DCC3");
    }
}
