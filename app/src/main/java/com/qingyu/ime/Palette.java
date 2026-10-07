package com.qingyu.ime;

import android.graphics.Color;

final class Palette {
    final int background, key, function, text, secondary, accent, accentText, border, pressed;
    private final int noun, verb, adjective, adverb, particle;
    Palette(boolean dark) {
        background = Color.parseColor(dark ? "#191E1C" : "#EFF1EC");
        key = Color.parseColor(dark ? "#303833" : "#FFFFFF");
        function = Color.parseColor(dark ? "#242D27" : "#DDE4DA");
        text = Color.parseColor(dark ? "#F0F3EE" : "#202B24");
        secondary = Color.parseColor(dark ? "#9CAAA0" : "#5E705F");
        accent = Color.parseColor(dark ? "#BAD4A8" : "#476B57");
        accentText = Color.parseColor(dark ? "#233323" : "#FFFFFF");
        border = Color.parseColor(dark ? "#39433C" : "#D9DFD5");
        pressed = Color.parseColor(dark ? "#52654E" : "#C6DCC3");
        noun = Color.parseColor(dark ? "#D4E0D9" : "#354C43");
        verb = Color.parseColor(dark ? "#DDE1D1" : "#4B5140");
        adjective = Color.parseColor(dark ? "#E6DBD0" : "#584A43");
        adverb = Color.parseColor(dark ? "#D8E1E7" : "#46515B");
        particle = Color.parseColor(dark ? "#E1D9E5" : "#544D5B");
    }
    int partOfSpeech(String tag) {
        if(tag==null||tag.isEmpty()||tag.equals("eng"))return text;
        // jieba's lexical tag is a default reading, not contextual disambiguation.
        switch(tag.charAt(0)) {
            case 'n':return noun;
            case 'v':return verb;
            case 'a':return adjective;
            case 'd':return adverb;
            case 'u':case 'p':case 'c':case 'e':case 'y':return particle;
            default:return text;
        }
    }
}
