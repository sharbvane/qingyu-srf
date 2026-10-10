package com.qingyu.ime;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formatting and protected tokens are copied verbatim; ordinary word spaces belong to the phrase. */
final class EditorTranslation {
    static final int MAX_TEXT = 4096, MAX_SEGMENTS = 64;
    private static final Pattern TECHNICAL = Pattern.compile(
            "```[\\s\\S]*?(?:```|$)|`[^`\\r\\n]*(?:`|$)|(?i:(?:https?://|ftp://|www\\.)\\S+)"
            + "|[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}.\\-]+\\.[\\p{L}]{2,}"
            + "|\\b[A-Za-z_][A-Za-z0-9_]*\\([^\\r\\n()]*\\)"
            + "|\\S*[=<>\\{\\}\\[\\]\\\\_]\\S*");

    static final class Part {
        final String text;
        final boolean translate;
        Part(String text, boolean translate) { this.text = text; this.translate = translate; }
    }
    static final class Plan {
        final List<Part> parts;
        final int count;
        Plan(List<Part> parts, int count) { this.parts = parts; this.count = count; }
        String apply(List<String> translations) {
            if (translations.size() != count) throw new IllegalArgumentException("Incomplete translation");
            StringBuilder result = new StringBuilder(); int index = 0;
            for (Part part : parts) {
                String value = part.translate ? clean(translations.get(index++)) : part.text;
                if (part.translate && value.isEmpty()) throw new IllegalArgumentException("Empty translation");
                result.append(value);
                if (result.length() > MAX_TEXT * 4) throw new IllegalArgumentException("Translation too long");
            }
            return result.toString();
        }
    }

    static Plan plan(String text, String source) {
        return plan(text, source, Collections.emptySet());
    }
    static Plan plan(String text, String source, Set<String> chineseKanaRuns) {
        if (text == null || text.length() > MAX_TEXT) throw new IllegalArgumentException("Text too long");
        boolean[] protectedText = protectedText(text);
        if (source.equals("zh")) protectKanaWords(text, protectedText, chineseKanaRuns);
        ArrayList<Part> parts = new ArrayList<>(); int count = 0, at = 0;
        while (at < text.length()) {
            int start = at, code = text.codePointAt(at);
            boolean translate = !protectedText[at] && letter(code, source);
            at += Character.charCount(code);
            while (at < text.length()) {
                code = text.codePointAt(at);
                boolean next = !protectedText[at] && (letter(code, source)
                        || translate && !source.equals("zh") && mark(code)
                        || translate && wordSpace(text, at, source, protectedText));
                if (next != translate) break;
                at += Character.charCount(code);
            }
            if (translate && ++count > MAX_SEGMENTS) throw new IllegalArgumentException("Too many segments");
            parts.add(new Part(text.substring(start, at), translate));
        }
        return new Plan(parts, count);
    }

    static List<String> kanaHanRuns(String text) {
        if (text == null || text.length() > MAX_TEXT) throw new IllegalArgumentException("Text too long");
        boolean[] technical = protectedText(text), connected = technical.clone();
        protectKanaWords(text, connected, Collections.emptySet());
        ArrayList<String> runs = new ArrayList<>();
        for (int at = 0; at < text.length();) {
            int code = text.codePointAt(at);
            if (!letter(code, "zh") || technical[at] || !connected[at]) { at += Character.charCount(code); continue; }
            int start = at;
            while (at < text.length() && letter(text.codePointAt(at), "zh") && !technical[at] && connected[at]) at += Character.charCount(text.codePointAt(at));
            String run = text.substring(start, at);
            if (!runs.contains(run)) runs.add(run);
            if (runs.size() > MAX_SEGMENTS) throw new IllegalArgumentException("Too many segments");
        }
        return runs;
    }

    static List<String> kanaHanContexts(String text, String run) {
        boolean[] technical = protectedText(text); ArrayList<String> contexts = new ArrayList<>();
        if (run.isEmpty()) return contexts;
        for (int start = text.indexOf(run); start >= 0; start = text.indexOf(run, start + run.length())) {
            int end = start + run.length();
            if (technical[start] || start > 0 && letter(text.codePointBefore(start), "zh")
                    || end < text.length() && letter(text.codePointAt(end), "zh")) continue;
            String context = "";
            if (end < text.length() && !technical[end] && kana(text.codePointAt(end)))
                context = text.substring(start, end + Character.charCount(text.codePointAt(end)));
            else if (start > 0 && !technical[start - 1] && kana(text.codePointBefore(start)))
                context = text.substring(start - Character.charCount(text.codePointBefore(start)), end);
            if (!context.isEmpty() && !contexts.contains(context)) contexts.add(context);
            if (contexts.size() > MAX_SEGMENTS) throw new IllegalArgumentException("Too many segments");
        }
        return contexts;
    }

    static String languageSample(String text) {
        boolean[] protectedText = protectedText(text); StringBuilder sample = new StringBuilder();
        for (int at = 0; at < text.length();) {
            int code = text.codePointAt(at);
            sample.appendCodePoint(!protectedText[at] && (Character.isLetter(code) || mark(code)) ? code : ' ');
            at += Character.charCount(code);
        }
        return sample.toString().trim();
    }
    static boolean hasHan(String text) { return hasScript(text, Character.UnicodeScript.HAN); }
    static boolean hasKana(String text) {
        return hasScript(text, Character.UnicodeScript.HIRAGANA) || hasScript(text, Character.UnicodeScript.KATAKANA);
    }
    static boolean hasNonHanLetter(String text) {
        for (int at = 0; at < text.length();) {
            int code = text.codePointAt(at); at += Character.charCount(code);
            if (Character.isLetter(code) && Character.UnicodeScript.of(code) != Character.UnicodeScript.HAN) return true;
        }
        return false;
    }
    static String separatedHanSample(String sample) {
        StringBuilder result = new StringBuilder(); int at = 0;
        while (at < sample.length()) {
            int code = sample.codePointAt(at);
            if (!Character.isLetter(code)) { at += Character.charCount(code); continue; }
            int start = at;
            while (at < sample.length() && Character.isLetter(sample.codePointAt(at))) at += Character.charCount(sample.codePointAt(at));
            String word = sample.substring(start, at);
            if (hasHan(word) && !hasNonHanLetter(word)) { if (result.length() > 0) result.append(' '); result.append(word); }
        }
        return result.toString();
    }
    static String confidentLanguage(String[] tags, float[] confidence) {
        if (tags == null || confidence == null || tags.length != confidence.length) return "";
        String best = ""; float first = 0, second = 0;
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == null || tags[i].equals("und") || !Float.isFinite(confidence[i])
                    || confidence[i] < 0 || confidence[i] > 1) continue;
            String language = tags[i].split("-", 2)[0];
            if (confidence[i] > first) { second = first; first = confidence[i]; best = language; }
            else second = Math.max(second, confidence[i]);
        }
        return first >= .65f && first - second >= .15f ? best : "";
    }
    private static boolean hasScript(String text, Character.UnicodeScript script) {
        for (int at = 0; at < text.length();) {
            int code = text.codePointAt(at); at += Character.charCount(code);
            if (Character.isLetter(code) && Character.UnicodeScript.of(code) == script) return true;
        }
        return false;
    }
    private static boolean[] protectedText(String text) {
        boolean[] protectedText = new boolean[text.length()]; Matcher matcher = TECHNICAL.matcher(text);
        while (matcher.find()) java.util.Arrays.fill(protectedText, matcher.start(), matcher.end(), true);
        return protectedText;
    }
    private static void protectKanaWords(String text, boolean[] protectedText, Set<String> chineseRuns) {
        boolean[] technical = protectedText.clone();
        for (int at = 0; at < text.length();) {
            int code = text.codePointAt(at);
            if (!Character.isLetter(code)) { at += Character.charCount(code); continue; }
            int start = at;
            while (at < text.length() && (Character.isLetter(text.codePointAt(at)) || mark(text.codePointAt(at)))) at += Character.charCount(text.codePointAt(at));
            if (!hasKana(text.substring(start, at))) continue;
            java.util.Arrays.fill(protectedText, start, at, true);
            for (int run = start; run < at;) {
                code = text.codePointAt(run);
                if (!letter(code, "zh") || technical[run]) { run += Character.charCount(code); continue; }
                int first = run;
                while (run < at && letter(text.codePointAt(run), "zh") && !technical[run]) run += Character.charCount(text.codePointAt(run));
                if (chineseRuns.contains(text.substring(first, run))) java.util.Arrays.fill(protectedText, first, run, false);
            }
        }
    }
    private static boolean letter(int code, String source) {
        return Character.isLetter(code) && (!source.equals("zh") || Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN);
    }
    private static boolean kana(int code) {
        Character.UnicodeScript script = Character.UnicodeScript.of(code);
        return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA;
    }
    private static boolean wordSpace(String text, int at, String source, boolean[] protectedText) {
        return !source.equals("zh") && text.charAt(at) == ' ' && at > 0 && at + 1 < text.length() && !protectedText[at + 1]
                && letter(text.codePointAt(at + 1), source)
                && (letter(text.codePointBefore(at), source) || !source.equals("zh") && mark(text.codePointBefore(at)));
    }
    private static boolean mark(int code) {
        int type = Character.getType(code);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }
    private static String clean(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder();
        for (int at = 0; at < text.length();) {
            int code = text.codePointAt(at); at += Character.charCount(code);
            if (Character.isLetter(code) || mark(code) || code == ' ') result.appendCodePoint(code);
            else if ((code == '\'' || code == '-') && result.length() > 0 && at < text.length()
                    && Character.isLetter(result.codePointBefore(result.length())) && Character.isLetter(text.codePointAt(at))) result.appendCodePoint(code);
        }
        return result.toString().trim();
    }
}
