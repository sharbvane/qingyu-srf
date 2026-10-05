package com.qingyu.core.translation;

/** Independent of any keyboard platform. Call only on a translation worker. */
public interface TranslationProvider extends AutoCloseable {
    String targetLanguage();
    String lookup(String chinese) throws Exception;
    @Override void close();
}

