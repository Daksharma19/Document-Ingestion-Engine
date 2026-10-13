package com.docextract.strategy.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordRegexFieldMapperTest {

    private final KeywordRegexFieldMapper mapper = new KeywordRegexFieldMapper();
    private final ObjectMapper json = new ObjectMapper();

    private static final String INVOICE = """
            ACME Ltd
            Invoice No: INV-2024-017
            Invoice Date: 12 March 2024
            Subtotal: $1,000.00
            Tax: $180.00
            Total: $1,180.00
            """;

    private JsonNode schema(String props) throws Exception {
        return json.readTree("{\"type\":\"object\",\"properties\":" + props + "}");
    }

    @Test
    void extractsLabelledFieldsAndCoercesTypes() throws Exception {
        var result = mapper.map(INVOICE, schema("""
                {"invoiceNumber":{"type":"string"},
                 "invoiceDate":{"type":"string","format":"date"},
                 "total":{"type":"number"}}"""));

        assertThat(result.fields())
                .containsEntry("invoiceNumber", "INV-2024-017")
                .containsEntry("invoiceDate", "2024-03-12")
                .containsEntry("total", new BigDecimal("1180.00"));
        assertThat(result.confidence().get("total")).isEqualTo(KeywordRegexFieldMapper.LABELLED);
    }

    @Test
    void totalDoesNotMatchInsideSubtotal() throws Exception {
        var result = mapper.map("Subtotal: 50.00\nTotal: 59.00", schema("{\"total\":{\"type\":\"number\"}}"));
        assertThat(result.fields().get("total")).isEqualTo(new BigDecimal("59.00"));
    }

    @Test
    void usesXLabelsHint() throws Exception {
        var result = mapper.map("Amount Payable: 12.50",
                schema("{\"grandTotal\":{\"type\":\"number\",\"x-labels\":[\"Amount Payable\"]}}"));
        assertThat(result.fields().get("grandTotal")).isEqualTo(new BigDecimal("12.50"));
    }

    @Test
    void fallsBackWithLowConfidenceWhenUnlabelled() throws Exception {
        var result = mapper.map("Paid on 2024-01-05, amounts 10.00 and 99.99",
                schema("{\"paidDate\":{\"type\":\"string\",\"format\":\"date\"},\"amountDue\":{\"type\":\"number\"}}"));
        assertThat(result.fields()).containsEntry("paidDate", "2024-01-05")
                .containsEntry("amountDue", new BigDecimal("99.99"));
        assertThat(result.confidence().get("paidDate")).isEqualTo(KeywordRegexFieldMapper.FALLBACK);
    }

    @Test
    void missingFieldIsNullWithZeroConfidence() throws Exception {
        var result = mapper.map("nothing here", schema("{\"notes\":{\"type\":\"string\"},\"items\":{\"type\":\"array\"}}"));
        assertThat(result.fields()).containsEntry("notes", null).containsEntry("items", null);
        assertThat(result.confidence()).containsEntry("notes", 0.0);
    }

    @Test
    void parsesCommonDateFormats() {
        assertThat(ValueParsers.parseDate("2024-03-12")).hasValueSatisfying(d -> assertThat(d).hasToString("2024-03-12"));
        assertThat(ValueParsers.parseDate("12/03/2024")).hasValueSatisfying(d -> assertThat(d).hasToString("2024-03-12"));
        assertThat(ValueParsers.parseDate("March 12, 2024")).hasValueSatisfying(d -> assertThat(d).hasToString("2024-03-12"));
        assertThat(ValueParsers.parseDate("31/02/2024")).isEmpty();
    }

    @Test
    void skipsALongerLabelThatStartsWithTheFieldName() throws Exception {
        var result = mapper.map("Total Items: 3\nGrand Total: £23.47", schema("{\"total\":{\"type\":\"number\"}}"));
        assertThat(result.fields()).containsEntry("total", new BigDecimal("23.47"));
    }

    @Test
    void subtotalIsNotGuessedFromTheLargestAmount() throws Exception {
        var result = mapper.map("Paid 10.00 and 99.99", schema("{\"subtotal\":{\"type\":\"number\"}}"));
        assertThat(result.fields()).containsEntry("subtotal", null);
    }

    @Test
    void stringValueStopsAtAColumnGapAndDropsTrailingNoise() {
        assertThat(KeywordRegexFieldMapper.cleanString("NW-55012.  a    |      a    |")).hasValue("NW-55012");
        assertThat(KeywordRegexFieldMapper.cleanString("ACME Supplies Ltd")).hasValue("ACME Supplies Ltd");
        assertThat(KeywordRegexFieldMapper.cleanString(" | ")).isEmpty();
    }

    @Test
    void identifierFieldsRejoinDigitGroupsSplitByOcr() throws Exception {
        var result = mapper.map("Invoice Number: NW-550 12", schema("{\"invoiceNumber\":{\"type\":\"string\"},\"notes\":{\"type\":\"string\"}}"));
        assertThat(result.fields()).containsEntry("invoiceNumber", "NW-55012");
        assertThat(KeywordRegexFieldMapper.isIdentifier("description")).isFalse();
    }

    @Test
    void unlabelledVendorIsTheFirstNameLikeLine() throws Exception {
        var result = mapper.map("\n12/01/2024\nCorner Coffee House\n12 High Street\nTotal: 4.00",
                schema("{\"merchant\":{\"type\":\"string\"}}"));
        assertThat(result.fields()).containsEntry("merchant", "Corner Coffee House");
        assertThat(result.confidence().get("merchant")).isEqualTo(KeywordRegexFieldMapper.FALLBACK);
    }
}
