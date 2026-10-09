package com.qingyu.core;

/** A T9 reading. Consumed length includes explicit separators in the raw input. */
public final class NineKeyCandidate {
    public final String text;
    public final String pinyin;
    /** The letters actually represented by the pressed keys, including initials. */
    public final String typedSpelling;
    public final int consumedDigits;
    public NineKeyCandidate(String text,String pinyin,int consumedDigits) {
        this(text,pinyin,pinyin,consumedDigits);
    }
    public NineKeyCandidate(String text,String pinyin,String typedSpelling,int consumedDigits) {
        this.text=text;this.pinyin=pinyin;this.typedSpelling=typedSpelling;this.consumedDigits=consumedDigits;
    }
}
