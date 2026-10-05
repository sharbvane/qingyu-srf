package com.qingyu.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Exercises the actual Java -> JNI -> AOSP boundary with Android's app_process. */
public final class EngineAdapterSmoke {
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    private static int id(EngineSnapshot snapshot, String word) {
        for (Candidate candidate : snapshot.candidates) if (word.equals(candidate.text)) return candidate.id;
        throw new AssertionError("Missing candidate " + word);
    }
    public static void main(String[] args) throws Exception {
        PinyinEngine engine = new PinyinEngine();
        engine.open(args[0], args[1]);
        engine.setLearningEnabled(false);
        boolean duplicateRejected = false;
        try { new PinyinEngine().open(args[0], args[1]); }
        catch (IllegalStateException expected) { duplicateRejected = true; }
        check(duplicateRejected, "only one process-global native instance can be open");
        EngineSnapshot state = engine.search("kaifa");
        check(state.rawPinyin.equals("kaifa") && state.composing.equals("kaifa"), "raw pinyin composition");
        check(engine.select(id(state, "开发")).committedText.equals("开发"), "Chinese selection commits only Chinese");
        System.out.println("PASS Java/JNI candidate and commit contract");

        state = engine.search("zhongguorenmin");
        state = engine.select(id(state, "中国"));
        check(state.composing.equals("中国renmin"), "partial selection composing");
        check(state.committedText.isEmpty(), "partial selection stays in preedit");
        state = engine.backspace();
        check(state.composing.equals("中国renmi"), "partial composing backspace");
        state = engine.search("zhongguorenmin");
        check(engine.select(0).committedText.equals("中国人民"), "candidate zero finishes partial sentence");
        System.out.println("PASS Java/JNI partial selection and deletion");

        String longInput = "nihaonihaonihaonihaonihao";
        state = engine.search(longInput);
        check(state.rawPinyin.equals(longInput), "all keystrokes retained beyond native sentence bound");
        int selections = 0;
        while (state.committedText.isEmpty() && !state.candidates.isEmpty() && selections++ < 8) {
            state = engine.select(0);
        }
        check(selections > 1 && state.committedText.length() == 10, "ten-syllable segmented selection commits ten Chinese characters");
        check(state.rawPinyin.isEmpty() && state.composing.isEmpty(), "completed long composition resets");
        System.out.println("PASS long composition segmentation without keystroke loss");

        state = engine.search(longInput);
        state = engine.select(0);
        int tailLength = state.rawPinyin.length();
        check(tailLength > 0, "long composition keeps suffix");
        for (int i = 0; i < tailLength; i++) state = engine.backspace();
        check(!state.rawPinyin.isEmpty() && !state.composing.isEmpty(), "deleting tail restores preceding selected segment");
        engine.reset();

        state = engine.search("nihaovvv");
        check(state.rawPinyin.equals("nihaovvv"), "invalid tail remains visible");
        if (!state.candidates.isEmpty()) state = engine.select(0);
        check(state.committedText.isEmpty() && state.composing.endsWith("vvv"), "invalid tail is never silently dropped on selection");
        state = engine.backspace();
        check(state.rawPinyin.endsWith("vv"), "invalid tail backspace");
        engine.reset();
        System.out.println("PASS invalid-pinyin tail preservation and deletion");

        String boundary = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        state = engine.search(boundary);
        check(state.rawPinyin.equals(boundary), "64-character boundary retains all pinyin");
        int rounds = 0;
        while (state.committedText.isEmpty() && !state.candidates.isEmpty() && rounds++ < 20) state = engine.select(0);
        check(state.committedText.length() == 64, "maximum-length composition commits every syllable");
        System.out.println("PASS bounded 64-character composition");

        String[] stressWords = {"nihao", "kaifa", "xiangmu", "sheji", "zhongwen", "shurufa", "zaijian"};
        List<Long> timings = new ArrayList<>();
        long stressStart = System.nanoTime();
        for (int iteration = 0; iteration < 1000; iteration++) {
            engine.reset();
            String input = stressWords[iteration % stressWords.length];
            for (int step = 1; step <= input.length(); step++) {
                long start = System.nanoTime();
                state = engine.search(input.substring(0, step));
                timings.add(System.nanoTime() - start);
                check(state.rawPinyin.equals(input.substring(0, step)), "rapid typing keeps requested input");
            }
            for (int step = input.length(); step > 0; step--) state = engine.backspace();
            check(state.rawPinyin.isEmpty() && state.composing.isEmpty(), "rapid deletion empties Java snapshot");
        }
        long stressElapsed = System.nanoTime() - stressStart;
        Collections.sort(timings);
        System.out.printf("PASS 1000 Java/JNI type/delete cycles; %d searches with up to 128 candidates; total %.2f ms; p50 %.3f ms; p95 %.3f ms; max %.3f ms%n",
            timings.size(), stressElapsed / 1e6, timings.get(timings.size() / 2) / 1e6,
            timings.get(timings.size() * 95 / 100) / 1e6, timings.get(timings.size() - 1) / 1e6);

        Random random = new Random(20261004);
        for (int iteration = 0; iteration < 100; iteration++) {
            engine.reset();
            StringBuilder randomInput = new StringBuilder();
            for (int step = 0; step < 64; step++) {
                randomInput.append((char)('a' + random.nextInt(26)));
                state = engine.search(randomInput.toString());
                check(state.rawPinyin.equals(randomInput.toString()), "random input is retained");
            }
            for (int step = 64; step > 0; step--) state = engine.backspace();
            check(state.composing.isEmpty() && state.rawPinyin.isEmpty(), "random input continuous deletion");
        }
        System.out.println("PASS 100 deterministic random 64-character type/delete sequences");

        Throwable[] wrongThread = new Throwable[1];
        Thread test = new Thread(() -> {
            try { engine.search("nihao"); } catch (Throwable expected) { wrongThread[0] = expected; }
        });
        test.start();test.join();
        check(wrongThread[0] instanceof IllegalStateException, "native state rejects unsynchronized worker access");
        engine.close();
        System.out.println("ALL_ADAPTER_CHECKS_PASS");
    }
}
