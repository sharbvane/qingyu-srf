package com.qingyu.core;

/** A T9 reading. Consumed length includes explicit separators in the raw input. */
public final class NineKeyCandidate {
    public final String text;
    public final String pinyin;
    public final int consumedDigits;
    public NineKeyCandidate(String text,String pinyin,int consumedDigits) {
        this.text=text;this.pinyin=pinyin;this.consumedDigits=consumedDigits;
    }
}
