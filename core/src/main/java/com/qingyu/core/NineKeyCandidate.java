package com.qingyu.core;

/** A local Chinese reading matched by T9. Consumed digits are never guessed by the UI. */
public final class NineKeyCandidate {
    public final String text;
    public final String pinyin;
    public final int consumedDigits;
    public NineKeyCandidate(String text,String pinyin,int consumedDigits) {
        this.text=text;this.pinyin=pinyin;this.consumedDigits=consumedDigits;
    }
}
