package com.docextract.parse.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TextQualityTest {

    public static final String PROSE = "With combined experiences and direct access to development groups, we are the experts in "
            + "IBM DB2 for i. The DB2 for i Center of Excellence can help you achieve and exceed your business requirements "
            + "and gain more confidence and satisfaction in IBM product data management products and solutions.";

    /** The same text through a font whose encoding is shifted by 3 letters, as on the real Redbook page. */
    public static String shifted(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            out.append(Character.isUpperCase(c) ? (char) ('A' + (c - 'A' + 3) % 26)
                    : Character.isLowerCase(c) ? (char) ('a' + (c - 'a' + 3) % 26) : c);
        }
        return out.toString();
    }

    @Test
    void proseReadsAsLanguage() {
        assertThat(TextQuality.score(PROSE).functionWordRatio()).isGreaterThan(0.2);
        assertThat(TextQuality.score(PROSE).unreadable()).isFalse();
    }

    @Test
    void shiftedEncodingDoesNot() {
        TextQuality.Score s = TextQuality.score(shifted(PROSE));
        assertThat(s.latinShare()).isEqualTo(1.0);
        assertThat(s.unreadable()).isTrue();
    }

    @Test
    void germanAndLoremIpsumAreLanguage() {
        String de = "Heute lest ihr die letzte Ausgabe der Pendlerzeitung. Damit geht ein Stück Mediengeschichte zu Ende. "
                + "Der Dank gilt ebenso unseren Werbekunden, die das erst möglich gemacht haben, und wir freuen uns auf die Zukunft.";
        String lorem = "Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut "
                + "labore et dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores et ea rebum.";
        assertThat(TextQuality.score(de).unreadable()).isFalse();
        assertThat(TextQuality.score(lorem).unreadable()).isFalse();
    }

    @Test
    void shortOrNonLatinTextIsNotJudged() {
        assertThat(TextQuality.score("Invoice 42 Total 100.00").functionWordRatio()).isNull();
        assertThat(TextQuality.score("यह एक हिंदी वाक्य है ".repeat(10)).functionWordRatio()).isNull();
    }
}
