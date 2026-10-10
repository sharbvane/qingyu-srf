package com.qingyu.ime;

import java.util.Arrays;
import java.util.Collections;

public final class EditorTranslationChecks {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void run() {
        String original = "开发，2026🙂\r\n设计\tHello! https://example.org/中文 `中文代码` user@example.org";
        EditorTranslation.Plan mixed = EditorTranslation.plan(original, "zh");
        check(mixed.count == 2, "technical and foreign text must be protected");
        check(mixed.apply(Arrays.asList("develop.", "design!")).equals(
                "develop，2026🙂\r\ndesign\tHello! https://example.org/中文 `中文代码` user@example.org"),
                "punctuation, CRLF, tab, number, emoji, URL, code and email must remain exact");
        String family = "你好👩‍👩‍👧‍👦世界";
        check(EditorTranslation.plan(family,"zh").apply(Arrays.asList("hello", "world")).equals("hello👩‍👩‍👧‍👦world"), "ZWJ emoji intact");
        check(EditorTranslation.plan("\uD840\uDC00。你好","zh").count == 2, "supplementary Han is a word");
        check(EditorTranslation.plan("Hello, 12🙂\nworld!","en").apply(Arrays.asList("你好。","世界。"))
                .equals("你好, 12🙂\n世界!"), "foreign source punctuation is copied, not model-generated punctuation");
        check(EditorTranslation.plan("日本語を学ぶ。🙂42","ja").apply(Collections.singletonList("学习日语。"))
                .equals("学习日语。🙂42"), "Japanese Han and kana remain one translatable run");
        check(EditorTranslation.plan("cafe\u0301\u00a0beau","fr").parts.get(0).text.equals("cafe\u0301"), "decomposed accent belongs to word");
        check(EditorTranslation.plan("cafe\u0301\u00a0beau","fr").apply(Arrays.asList("咖啡","美丽"))
                .equals("咖啡\u00a0美丽"), "NBSP is a protected separator");
        EditorTranslation.Plan sentence = EditorTranslation.plan("I would like to watch short videos.", "en");
        check(sentence.count == 1 && sentence.parts.get(0).text.equals("I would like to watch short videos"), "whole foreign clause enters the model together");
        check(sentence.apply(Collections.singletonList("我想看短视频。")).equals("我想看短视频."), "ordinary word spacing follows the translated language while punctuation stays exact");
        check(EditorTranslation.plan("开发 设计", "zh").apply(Arrays.asList("develop", "design")).equals("develop design"), "spaced Chinese retains local word translation without requiring a model");
        check(EditorTranslation.plan("  Hello there  my friend\tgood night\r\n42🙂good\u00a0day ", "en")
                .apply(Arrays.asList("你好", "我的朋友", "晚安", "好", "天"))
                .equals("  你好  我的朋友\t晚安\r\n42🙂好\u00a0天 "), "indentation, repeated spaces, tabs, CRLF, NBSP and protected tokens remain exact");
        check(EditorTranslation.plan("This is https://x.test/path the `source code`\nfor me", "en")
                .apply(Arrays.asList("这是", "该", "给我")).equals("这是 https://x.test/path 该 `source code`\n给我"), "whole phrases cannot swallow technical text or its surrounding spaces");
        check(EditorTranslation.plan("你好world","zh").apply(Collections.singletonList("hello")).equals("helloworld"), "mixed foreign text is unchanged");
        check(EditorTranslation.plan("开发 こんにちは ABC","zh").apply(Collections.singletonList("develop")).equals("develop こんにちは ABC"), "mixed Japanese kana and Latin letters stay unchanged");
        check(EditorTranslation.separatedHanSample("开发 こんにちは ABC").equals("开发"), "isolated Chinese evidence in Han/kana mixed selection");
        check(EditorTranslation.separatedHanSample("今日は友達と映画を見ます").isEmpty(), "natural Japanese connected Han/kana is not treated as isolated Chinese evidence");
        check(EditorTranslation.plan("今日は友達と映画を見ます。🙂2026","zh").count == 0, "unselected native Japanese is never partially translated as Chinese");
        check(EditorTranslation.plan("开发 日本語を勉強する ABC","zh").apply(Collections.singletonList("develop")).equals("develop 日本語を勉強する ABC"), "Japanese Han connected to kana stays foreign in a mixed selection");
        check(EditorTranslation.plan("你好こんにちは","zh").count == 0, "inseparable Chinese/kana ambiguity retains original text");
        check(EditorTranslation.kanaHanRuns("你好こんにちは开发カタカナ 你好こんにちは https://x.test/中文かな `代码かな`")
                .equals(Arrays.asList("你好","开发")), "unique glued Han spans exclude protected technical text");
        check(EditorTranslation.kanaHanContexts("你好こんにちは 你好カタカナ 你好こ https://x.test/你好かな 我的你好こ", "你好")
                .equals(Arrays.asList("你好こ", "你好カ")), "all unique adjacent-kana contexts exclude protected and partial Han matches");
        check(EditorTranslation.kanaHanContexts("こんにちは你好 お名前", "你好").equals(Collections.singletonList("は你好")), "preceding kana supplies context when no following kana exists");
        check(EditorTranslation.kanaHanContexts("今日は友達と映画を見ます", "今日").equals(Collections.singletonList("今日は")), "Japanese particle remains in Han identification context");
        check(EditorTranslation.plan("你好こんにちは开发カタカナ！2026🙂\r\n", "zh", new java.util.HashSet<>(Arrays.asList("你好", "开发")))
                .apply(Arrays.asList("hello", "develop")).equals("helloこんにちはdevelopカタカナ！2026🙂\r\n"), "confirmed Chinese runs translate without touching adjacent kana or separators");
        check(EditorTranslation.plan("你好こんにちは 日本語を勉強する", "zh", Collections.singleton("你好"))
                .apply(Collections.singletonList("hello")).equals("helloこんにちは 日本語を勉強する"), "Japanese Han and unknown runs remain protected beside confirmed Chinese");
        check(EditorTranslation.plan("你好こんにちは https://x.test/你好かな", "zh", Collections.singleton("你好"))
                .apply(Collections.singletonList("hello")).equals("helloこんにちは https://x.test/你好かな"), "approved text inside a URL cannot bypass protection");
        check(EditorTranslation.plan("foo(中文) 变量=1 http://x.test/中文","zh").count == 0, "code and URL bodies are opaque");
        check(EditorTranslation.plan("42🙂\r\n","zh").count == 0, "nonwords never translate");
        check(EditorTranslation.languageSample("https://x.test/中文 `日本語` hello 42🙂").trim().equals("hello"), "language detection excludes protected technical tokens");
        check(EditorTranslation.confidentLanguage(new String[]{"en","fr"},new float[]{.8f,.1f}).equals("en"), "clear language wins");
        check(EditorTranslation.confidentLanguage(new String[]{"fr","en"},new float[]{.68f,.62f}).isEmpty(), "ambiguous language is rejected");
        check(EditorTranslation.confidentLanguage(new String[]{"zh-Hans"},new float[]{.95f}).equals("zh"), "Han language tags normalized");
        check(EditorTranslation.confidentLanguage(new String[]{"en","und"},new float[]{Float.NaN,1f}).isEmpty(), "undefined and invalid confidence never win");
        rejected(()->mixed.apply(Collections.singletonList("develop")), "partial translation rejected");
        rejected(()->mixed.apply(Arrays.asList("develop","...")), "empty model translation rejected");
        rejected(()->EditorTranslation.plan("字".repeat(4097),"zh"), "document length bound");
        rejected(()->EditorTranslation.plan("字。".repeat(65),"zh"), "span count bound");
    }
    private static void rejected(Runnable action,String message){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,message);}
    public static void main(String[] args) { run(); System.out.println("ALL_EDITOR_TRANSLATION_PROTECTION_CHECKS_PASS"); }
}
