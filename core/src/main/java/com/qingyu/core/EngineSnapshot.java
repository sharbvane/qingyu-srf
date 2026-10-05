package com.qingyu.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable result safe to publish from the decoder worker to the UI thread. */
public final class EngineSnapshot {
    /** Selected Chinese prefix followed by still-unselected pinyin. */
    public final String composing;
    /** Active pinyin segment, including its syllables already selected in the engine. */
    public final String rawPinyin;
    public final List<Candidate> candidates;
    /** Nonempty only when select() has completed the entire composition. */
    public final String committedText;

    public EngineSnapshot(String composing, String rawPinyin,
                          List<Candidate> candidates, String committedText) {
        this.composing = composing == null ? "" : composing;
        this.rawPinyin = rawPinyin == null ? "" : rawPinyin;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.committedText = committedText == null ? "" : committedText;
    }

    public static EngineSnapshot empty() {
        return new EngineSnapshot("", "", Collections.emptyList(), "");
    }
}
