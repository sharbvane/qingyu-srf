package com.qingyu.core.translation;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** A failed or missing gloss leaves Chinese candidates intact. */
public final class TranslationBatch {
    private TranslationBatch() {}
    public static Map<String, String> lookup(TranslationProvider provider, Iterable<String> candidates) {
        Map<String, String> result = new HashMap<>();
        for (String text : candidates) {
            try {
                String gloss = provider.lookup(text);
                if (gloss != null && !gloss.isEmpty()) result.put(text, gloss);
            } catch (Exception ignored) { /* independent fail-open lookup */ }
        }
        return Collections.unmodifiableMap(result);
    }
}
