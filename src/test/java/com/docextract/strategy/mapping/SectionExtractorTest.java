package com.docextract.strategy.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SectionExtractorTest {

    private static final String REPORT = """
            SITE INSPECTION REPORT
            Inspector: J. Rao
            FINDINGS
            • Fire exit on level 2 was blocked by
            stacked pallets.
            • Two extinguishers past their service
            date.
            RECOMMENDATIONS
            Clear the exit within 24 hours and re-
            service all extinguishers this week.
            Notes:
            Next visit in March.
            Terms:
            Order ID : 991
            """;

    @Test
    void bulletsUnderAHeadingBecomeItemsWithWrappedLinesJoined() {
        assertThat(SectionExtractor.items(REPORT, Set.of("findings"))).hasValue(List.of(
                "Fire exit on level 2 was blocked by stacked pallets.",
                "Two extinguishers past their service date."));
    }

    @Test
    void paragraphUnderAHeadingRejoinsHyphenatedBreaks() {
        assertThat(SectionExtractor.paragraph(REPORT, Set.of("recommendations")))
                .hasValue("Clear the exit within 24 hours and reservice all extinguishers this week.");
        assertThat(SectionExtractor.paragraph(REPORT, Set.of("notes"))).hasValue("Next visit in March.");
    }

    @Test
    void aColonLabelFollowedByAnotherLabelIsNotASection() {
        assertThat(SectionExtractor.paragraph(REPORT, Set.of("terms"))).isEmpty();
        assertThat(SectionExtractor.paragraph(REPORT, Set.of("warranty"))).isEmpty();
    }

    @Test
    void mapperUsesSectionsAndTheTitleLineForTheSubjectsName() throws Exception {
        var schema = new ObjectMapper().readTree("""
                {"type":"object","properties":{"name":{"type":"string"},"inspector":{"type":"string"},
                  "findings":{"type":"array","items":{"type":"string"}},"recommendations":{"type":"string"},
                  "nextVisit":{"type":"string"}}}""");
        var f = new KeywordRegexFieldMapper().map(REPORT, schema).fields();
        assertThat(f).containsEntry("name", "SITE INSPECTION REPORT")
                .containsEntry("inspector", "J. Rao")
                .containsEntry("nextVisit", null);
        assertThat((List<?>) f.get("findings")).hasSize(2);
        assertThat((String) f.get("recommendations")).startsWith("Clear the exit");
    }
}
