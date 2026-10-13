package com.docextract.strategy.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Non-invoice documents: nothing here relies on the invoice vocabulary, only on the schema and the layout. */
class GenericSchemaMappingTest {

    private final ObjectMapper json = new ObjectMapper();
    private final KeywordRegexFieldMapper mapper = new KeywordRegexFieldMapper();

    private JsonNode schema(String props) throws Exception {
        return json.readTree("{\"type\":\"object\",\"properties\":" + props + "}");
    }

    @Test
    void labReportWithLabelsDatesAndNumbers() throws Exception {
        var f = mapper.map("""
                CITY DIAGNOSTICS LAB
                Patient Name: Asha Verma          Age: 34
                Sample Collected: 03/04/2025      Report Date: 05/04/2025
                Haemoglobin: 13.2 g/dL
                Fasting Glucose: 92 mg/dL
                Referred By: Dr. R. Mehta
                """, schema("""
                {"patientName":{"type":"string"},"age":{"type":"integer"},
                 "sampleCollected":{"type":"string","format":"date"},"reportDate":{"type":"string","format":"date"},
                 "haemoglobin":{"type":"number"},"fastingGlucose":{"type":"number"},"referredBy":{"type":"string"},
                 "dischargeDate":{"type":"string","format":"date"}}""")).fields();
        assertThat(f).containsEntry("patientName", "Asha Verma")
                .containsEntry("age", 34)
                .containsEntry("sampleCollected", "2025-04-03")
                .containsEntry("reportDate", "2025-04-05")
                .containsEntry("haemoglobin", new BigDecimal("13.2"))
                .containsEntry("fastingGlucose", new BigDecimal("92"))
                .containsEntry("referredBy", "Dr. R. Mehta")
                .containsEntry("dischargeDate", null);
    }

    @Test
    void shippingFormWithHeaderRowOverValueRow() throws Exception {
        var f = mapper.map("""
                BILL OF LADING
                CARRIER   SERVICE LEVEL   PIECES   WEIGHT KG
                BlueDart   Express   3   12.5
                Tracking: 7731 0042 9918
                """, schema("""
                {"carrier":{"type":"string"},"serviceLevel":{"type":"string"},"pieces":{"type":"integer"},
                 "weightKg":{"type":"number"},"tracking":{"type":"string","pattern":"\\\\d{4} \\\\d{4} \\\\d{4}"}}""")).fields();
        assertThat(f).containsEntry("carrier", "BlueDart")
                .containsEntry("serviceLevel", "Express")
                .containsEntry("pieces", 3)
                .containsEntry("weightKg", new BigDecimal("12.5"))
                .containsEntry("tracking", "7731 0042 9918");
    }

    @Test
    void schemaPatternEnumAndFormatFindUnlabelledValues() throws Exception {
        var f = mapper.map("""
                Rahul Sharma
                rahul.sharma@example.org | +91 98765 43210 | https://github.com/rsharma
                Seniority: Mid-level Backend Engineer
                PAN ABCDE1234F
                """, schema("""
                {"email":{"type":"string","format":"email"},"website":{"type":"string","format":"uri"},
                 "pan":{"type":"string","pattern":"[A-Z]{5}[0-9]{4}[A-Z]"},
                 "level":{"type":"string","enum":["Junior","Mid-level","Senior"]},
                 "linkedin":{"type":"string","format":"uri","pattern":"linkedin\\\\.com/\\\\S+"}}""")).fields();
        assertThat(f).containsEntry("email", "rahul.sharma@example.org")
                .containsEntry("website", "https://github.com/rsharma")
                .containsEntry("pan", "ABCDE1234F")
                .containsEntry("level", "Mid-level")
                .containsEntry("linkedin", null);
    }

    @Test
    void unlabelledTotalIsNeverGuessedFromPhoneNumbersOrYears() throws Exception {
        var f = mapper.map("RAHUL SHARMA\n+91 9868421785\nB.Tech 2023 - 2027",
                schema("{\"total\":{\"type\":\"number\"}}")).fields();
        assertThat(f).containsEntry("total", null);
    }

    @Test
    void blockLabelTakesTheCellBelow() throws Exception {
        var f = mapper.map("""
                Applicant:   Reference No   APP-2291
                Meera Iyer   Submitted   12 June 2025
                """, schema("{\"applicant\":{\"type\":\"string\"},\"referenceNo\":{\"type\":\"string\"},"
                + "\"submitted\":{\"type\":\"string\",\"format\":\"date\"}}")).fields();
        assertThat(f).containsEntry("applicant", "Meera Iyer")
                .containsEntry("referenceNo", "APP-2291")
                .containsEntry("submitted", "2025-06-12");
    }
}
