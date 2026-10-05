package com.qingyu.core;

/** Platform-neutral protocol; later adapters may use Rime or another native engine. */
public interface ChineseEngine extends AutoCloseable {
    void open(String dictionaryPath, String userPath);
    EngineSnapshot reset();
    EngineSnapshot search(String pinyin);
    EngineSnapshot select(int id);
    EngineSnapshot backspace();
    void setLearningEnabled(boolean enabled);
    void flush();
    @Override void close();
}
