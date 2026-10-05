package com.qingyu.core;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * AOSP's offline full-pinyin decoder behind a platform-independent Java contract.
 * All methods must be called on the same serial worker. The native engine is
 * process-global, so only one instance may be open at a time.
 */
public final class PinyinEngine implements ChineseEngine {
    public static final int MAX_PINYIN_LENGTH = 64;
    public static final int MAX_CANDIDATES = 128;
    private static final Object DECODER_LOCK = new Object();
    private static PinyinEngine activeEngine;
    private boolean opened;
    private long ownerThread;
    private int candidateCount;
    private String activeInput = "";
    private String completedPrefix = "";
    private final ArrayDeque<Segment> completedSegments = new ArrayDeque<>();
    private EngineSnapshot current = EngineSnapshot.empty();

    public void open(String dictionaryPath, String userPath) {
        if (opened) { checkWorker(); return; }
        if (dictionaryPath == null || userPath == null) {
            throw new IllegalArgumentException("Dictionary paths are required");
        }
        synchronized (DECODER_LOCK) {
            if (activeEngine != null) throw new IllegalStateException("The process already has an open decoder");
            ownerThread = Thread.currentThread().getId();
            if (!NativeDecoder.open(dictionaryPath, userPath)) {
                throw new IllegalStateException("The pinyin dictionary could not be opened");
            }
            activeEngine = this;
            opened = true;
        }
        reset();
    }

    public EngineSnapshot reset() {
        checkWorker();
        NativeDecoder.reset();
        candidateCount = 0;
        activeInput = "";
        completedPrefix = "";
        completedSegments.clear();
        current = EngineSnapshot.empty();
        return current;
    }

    /** Incremental search preserves any selected Chinese prefix when possible. */
    public EngineSnapshot search(String pinyin) {
        checkWorker();
        String input = pinyin == null ? "" : pinyin.toLowerCase(Locale.ROOT);
        if (input.length() > MAX_PINYIN_LENGTH) throw new IllegalArgumentException("Pinyin composition is too long");
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if ((ch < 'a' || ch > 'z') && ch != '\'') {
                throw new IllegalArgumentException("Only ASCII pinyin letters and apostrophes are supported");
            }
        }
        if (input.isEmpty()) return reset();
        // Reuse immutable results for repeated refreshes; native choose state is retained.
        if (input.equals(current.rawPinyin)) return current;
        activeInput = input;
        candidateCount = NativeDecoder.search(input);
        return publish("");
    }

    /** May select a prefix and return remaining candidates, or return committedText. */
    public EngineSnapshot select(int id) {
        checkWorker();
        if (id < 0 || id >= candidateCount) return current;
        candidateCount = NativeDecoder.choose(id);
        String sentence = safe(NativeDecoder.choice(0));
        int fixed = NativeDecoder.fixedLength();
        int[] starts = NativeDecoder.syllableStarts();
        if (starts.length > 0 && fixed == starts.length - 1 && fixed > 0) {
            int consumed = Math.max(0, Math.min(starts[starts.length - 1], activeInput.length()));
            String remaining = activeInput.substring(consumed);
            while (remaining.startsWith("'")) remaining = remaining.substring(1);
            NativeDecoder.reset();
            if (!remaining.isEmpty()) {
                // The AOSP decoder bounds each sentence to nine syllables. Preserve
                // every unparsed keystroke and continue it as the next segment.
                completedSegments.addLast(new Segment(activeInput.substring(0, consumed), sentence));
                completedPrefix += sentence;
                activeInput = remaining;
                candidateCount = NativeDecoder.search(activeInput);
                return publish("");
            }
            String commit = completedPrefix + sentence;
            activeInput = "";
            completedPrefix = "";
            completedSegments.clear();
            candidateCount = 0;
            current = new EngineSnapshot("", "", Collections.emptyList(), commit);
            return current;
        }
        return publish("");
    }

    /** Removes one pending pinyin character; when all are selected, undoes selection. */
    public EngineSnapshot backspace() {
        checkWorker();
        String raw = activeInput;
        if (raw.isEmpty()) return restorePreviousSegment();
        String nativeRaw = safe(NativeDecoder.pinyin());
        if (nativeRaw.length() < raw.length()) {
            activeInput = raw.substring(0, raw.length() - 1);
            candidateCount = NativeDecoder.search(activeInput);
            if (activeInput.isEmpty()) return restorePreviousSegment();
            return publish("");
        }
        int fixed = NativeDecoder.fixedLength();
        int[] starts = NativeDecoder.syllableStarts();
        int fixedEnd = fixed > 0 && fixed < starts.length ? starts[fixed] : 0;
        if (raw.length() <= fixedEnd && fixed > 0) {
            candidateCount = NativeDecoder.cancelLastChoice();
        } else {
            candidateCount = NativeDecoder.delete(raw.length() - 1, false, false);
            activeInput = raw.substring(0, raw.length() - 1);
        }
        if (activeInput.isEmpty()) return restorePreviousSegment();
        return publish("");
    }

    /** Flush learning only at a lifecycle boundary, never on every key. */
    public void flush() {
        checkWorker();
        NativeDecoder.flush();
    }

    /** No new words or frequency updates while disabled. Existing ranking is retained. */
    public void setLearningEnabled(boolean enabled) {
        checkWorker();
        NativeDecoder.setLearningEnabled(enabled);
    }

    @Override public void close() {
        if (!opened) return;
        checkWorker();
        NativeDecoder.flush();
        synchronized (DECODER_LOCK) {
            NativeDecoder.close();
            opened = false;
            activeEngine = null;
        }
        activeInput = "";
        completedPrefix = "";
        completedSegments.clear();
        current = EngineSnapshot.empty();
    }

    private EngineSnapshot publish(String committed) {
        String raw = activeInput;
        String sentence = safe(NativeDecoder.choice(0));
        int fixed = Math.min(NativeDecoder.fixedLength(), sentence.length());
        int[] starts = NativeDecoder.syllableStarts();
        int offset = fixed > 0 && fixed < starts.length ? starts[fixed] : 0;
        offset = Math.max(0, Math.min(offset, raw.length()));
        String composing = completedPrefix + sentence.substring(0, fixed) + raw.substring(offset);
        List<Candidate> list = new ArrayList<>(Math.min(candidateCount, MAX_CANDIDATES));
        for (int i = 0; i < candidateCount && i < MAX_CANDIDATES; i++) {
            String text = safe(NativeDecoder.choice(i));
            // AOSP candidate zero contains the previously selected sentence prefix.
            if (i == 0 && fixed > 0 && text.length() >= fixed) text = text.substring(fixed);
            if (!text.isEmpty()) list.add(new Candidate(i, text));
        }
        current = new EngineSnapshot(composing, raw, list, committed);
        return current;
    }

    private void checkWorker() {
        if (!opened) throw new IllegalStateException("Pinyin engine is not open");
        if (Thread.currentThread().getId() != ownerThread) {
            throw new IllegalStateException("Use one serial worker for all decoder calls");
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private EngineSnapshot restorePreviousSegment() {
        if (completedSegments.isEmpty()) return reset();
        Segment previous = completedSegments.removeLast();
        completedPrefix = completedPrefix.substring(0, completedPrefix.length() - previous.text.length());
        activeInput = previous.pinyin;
        NativeDecoder.reset();
        candidateCount = NativeDecoder.search(activeInput);
        return publish("");
    }

    private static final class Segment {
        final String pinyin;
        final String text;
        Segment(String pinyin, String text) { this.pinyin = pinyin; this.text = text; }
    }
}
