package com.docextract.parse.quality;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Does a page's text read as language? Real prose in any Latin-script language is ~25-50% function words ("the",
 * "der", "et", "de"); tables and forms still have a few percent. A text layer with a broken font encoding decodes
 * to letters that look fine but spell nothing ("WKH SHUIRUPDQFH" for "the performance"): almost no function words.
 * Language-independent within the 8 languages listed; text in other scripts is not judged (null).
 */
public final class TextQuality {

    /** Fewer alphabetic words than this: too little text to judge. */
    static final int MIN_WORDS = 30;

    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final Set<String> FUNCTION_WORDS = Set.copyOf(java.util.List.of( // languages share words
            // en
            "the", "of", "and", "to", "in", "a", "is", "for", "on", "that", "with", "as", "by", "be", "are", "or",
            "it", "this", "an", "at", "from", "not", "we", "you", "if", "our", "your", "can", "will", "has", "have",
            // de
            "der", "die", "das", "und", "ist", "ein", "eine", "zu", "den", "mit", "von", "für", "auf", "nicht", "sich",
            "des", "dem", "im", "es", "auch", "wir", "sie", "ich",
            // fr
            "le", "la", "les", "de", "des", "et", "un", "une", "du", "en", "est", "pour", "que", "qui", "dans", "par",
            "sur", "au", "il", "pas",
            // es / pt / it
            "el", "los", "las", "y", "que", "por", "con", "para", "del", "se", "o", "os", "um", "uma", "do", "da",
            "il", "di", "che", "e", "per", "non", "con", "sono", "lo",
            // nl
            "het", "een", "van", "en", "op", "te", "dat", "zijn", "voor", "niet", "met",
            // la (lorem ipsum, quotations)
            "et", "sed", "ut", "est", "ad", "cum", "non", "no", "ea"));

    /** {@code functionWordRatio} is null when the text is too short or not mostly Latin script. */
    public record Score(int words, double latinShare, Double functionWordRatio) {

        /** Enough text, in a script we can judge, that does not read as language. */
        public boolean unreadable() {
            return functionWordRatio != null && functionWordRatio < 0.04;
        }
    }

    private TextQuality() {
    }

    public static Score score(String text) {
        int words = 0;
        int latin = 0;
        int function = 0;
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String w = m.group();
            words++;
            if (isLatin(w)) {
                latin++;
                if (FUNCTION_WORDS.contains(w.toLowerCase(java.util.Locale.ROOT))) {
                    function++;
                }
            }
        }
        double latinShare = words == 0 ? 0 : (double) latin / words;
        Double ratio = latin >= MIN_WORDS && latinShare >= 0.8 ? (double) function / latin : null;
        return new Score(words, round(latinShare), ratio == null ? null : round(ratio));
    }

    private static boolean isLatin(String w) {
        for (int i = 0; i < w.length(); i++) {
            if (Character.UnicodeScript.of(w.charAt(i)) != Character.UnicodeScript.LATIN) {
                return false;
            }
        }
        return true;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
