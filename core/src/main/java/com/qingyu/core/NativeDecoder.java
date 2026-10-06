package com.qingyu.core;

/** Minimal JNI boundary; no Android framework dependency. Single serial worker only. */
final class NativeDecoder {
    static { System.loadLibrary("qingyu_pinyin"); }
    private NativeDecoder() { }
    static native boolean open(String systemDictionary, String userDictionary);
    static native void close();
    static native void reset();
    static native int search(String pinyin);
    static native int choose(int id);
    static native int cancelLastChoice();
    static native int delete(int position, boolean positionIsSyllable, boolean clearFixed);
    static native String pinyin();
    static native String choice(int id);
    static native int fixedLength();
    static native int[] syllableStarts();
    static native void flush();
    static native void setLearningEnabled(boolean enabled);
    static native String[] predict(String context);
}
