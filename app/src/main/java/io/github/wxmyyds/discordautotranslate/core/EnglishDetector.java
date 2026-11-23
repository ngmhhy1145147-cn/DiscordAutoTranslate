package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.Locale;

/** 判断消息是否包含足量的非目标语言自然语言文本。 */
public final class EnglishDetector {

    /** 固定判定依据，不包含正文、计数或目标语言配置。 */
    public enum Reason {
        EMPTY, NO_VISIBLE_CONTENT, TOO_FEW_LETTERS, LOW_LETTER_RATIO,
        NO_TEXT_NODE, TARGET_LANGUAGE_SCRIPT, TRANSLATABLE
    }

    /** 判定结果与依据，便于诊断。 */
    public static final class Verdict {
        public final boolean shouldTranslate;
        public final Reason reason;

        Verdict(boolean shouldTranslate, Reason reason) {
            this.shouldTranslate = shouldTranslate;
            this.reason = reason;
        }
    }

    private static final int MIN_LETTERS = 4;
    private static final float MIN_LETTER_RATIO = 0.5f;
    private static final float MIN_TARGET_SCRIPT_RATIO = 0.2f;

    private EnglishDetector() {
    }

    /** Evaluate extractable prose; retained native nodes never veto surrounding text. */
    public static Verdict evaluate(@Nullable String rawText, int textNodeCount,
                                   @Nullable String targetLanguage) {
        return evaluate(rawText, textNodeCount, targetLanguage, MIN_LETTERS);
    }

    public static Verdict evaluateLabel(@Nullable String label, @Nullable String targetLanguage) {
        return evaluate(label, 1, targetLanguage, 1);
    }

    private static Verdict evaluate(@Nullable String rawText, int textNodeCount,
                                    @Nullable String targetLanguage, int minimumLetters) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return new Verdict(false, Reason.EMPTY);
        }
        String text = RetainedNodeMarkers.maskedForDetection(rawText).replaceAll("https?://\\S+", " ")
                .replaceAll("`[^`]*`", " ")
                .replaceAll(":([A-Za-z0-9_]+):", " ");

        int letters = 0;
        int nonSpace = 0;
        int han = 0;
        int kana = 0;
        int hangul = 0;
        int targetLetters = 0;
        TargetScript targetScript = targetScript(targetLanguage);

        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                continue;
            }
            nonSpace++;
            if (!Character.isLetter(cp)) {
                continue;
            }
            letters++;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN) {
                han++;
            } else if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA) {
                kana++;
            } else if (script == Character.UnicodeScript.HANGUL) {
                hangul++;
            }
            if (matchesTargetScript(script, targetScript)) {
                targetLetters++;
            }
        }

        if (nonSpace == 0) {
            return new Verdict(false, Reason.NO_VISIBLE_CONTENT);
        }
        if (letters < minimumLetters) {
            return new Verdict(false, Reason.TOO_FEW_LETTERS);
        }
        float letterRatio = (float) letters / nonSpace;
        if (letterRatio < MIN_LETTER_RATIO) {
            return new Verdict(false, Reason.LOW_LETTER_RATIO);
        }
        if (textNodeCount == 0) {
            return new Verdict(false, Reason.NO_TEXT_NODE);
        }
        // Han-only text is treated as Chinese for the default target. Japanese kana
        // and Korean Hangul disambiguate their scripts from Chinese.
        if (targetScript == TargetScript.HAN
                && kana == 0 && hangul == 0
                && (float) han / letters >= MIN_TARGET_SCRIPT_RATIO) {
            return new Verdict(false, Reason.TARGET_LANGUAGE_SCRIPT);
        }
        if (targetScript == TargetScript.JAPANESE && kana > 0) {
            return new Verdict(false, Reason.TARGET_LANGUAGE_SCRIPT);
        }
        if (targetScript != TargetScript.HAN && targetScript != TargetScript.JAPANESE
                && targetScript != TargetScript.NONE
                && (float) targetLetters / letters >= MIN_TARGET_SCRIPT_RATIO) {
            return new Verdict(false, Reason.TARGET_LANGUAGE_SCRIPT);
        }
        return new Verdict(true, Reason.TRANSLATABLE);
    }

    private enum TargetScript {
        NONE, HAN, JAPANESE, HANGUL, CYRILLIC, ARABIC, GREEK, HEBREW,
        DEVANAGARI, BENGALI, THAI
    }

    private static TargetScript targetScript(@Nullable String language) {
        if (language == null) {
            return TargetScript.HAN;
        }
        String value = language.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return TargetScript.HAN;
        }
        if (containsAny(value, "中文", "汉语", "漢語", "chinese", "zh")) {
            return TargetScript.HAN;
        }
        if (containsAny(value, "日本", "japanese", "ja")) {
            return TargetScript.JAPANESE;
        }
        if (containsAny(value, "한국", "조선", "korean", "ko")) {
            return TargetScript.HANGUL;
        }
        if (containsAny(value, "рус", "russian", "ru", "укра", "ukrainian", "uk")) {
            return TargetScript.CYRILLIC;
        }
        if (containsAny(value, "العربية", "arabic", "ar", "فارسی", "persian", "farsi", "fa")) {
            return TargetScript.ARABIC;
        }
        if (containsAny(value, "ελλην", "greek", "el")) {
            return TargetScript.GREEK;
        }
        if (containsAny(value, "עברית", "hebrew", "he")) {
            return TargetScript.HEBREW;
        }
        if (containsAny(value, "हिन्दी", "hindi", "hi")) {
            return TargetScript.DEVANAGARI;
        }
        if (containsAny(value, "বাংলা", "bengali", "bn")) {
            return TargetScript.BENGALI;
        }
        if (containsAny(value, "ไทย", "thai", "th")) {
            return TargetScript.THAI;
        }
        return TargetScript.NONE;
    }

    private static boolean matchesTargetScript(Character.UnicodeScript script,
                                                TargetScript target) {
        switch (target) {
            case HAN:
                return script == Character.UnicodeScript.HAN;
            case JAPANESE:
                return script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA;
            case HANGUL:
                return script == Character.UnicodeScript.HANGUL;
            case CYRILLIC:
                return script == Character.UnicodeScript.CYRILLIC;
            case ARABIC:
                return script == Character.UnicodeScript.ARABIC;
            case GREEK:
                return script == Character.UnicodeScript.GREEK;
            case HEBREW:
                return script == Character.UnicodeScript.HEBREW;
            case DEVANAGARI:
                return script == Character.UnicodeScript.DEVANAGARI;
            case BENGALI:
                return script == Character.UnicodeScript.BENGALI;
            case THAI:
                return script == Character.UnicodeScript.THAI;
            default:
                return false;
        }
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
