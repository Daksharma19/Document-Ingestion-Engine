package com.docextract.strategy.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same generic schema against invoices with different conventions (US, EU VAT, Indian GST POS slip),
 * to check the mapper is not tuned to one template.
 */
class GenericInvoiceMappingTest {

    private final ObjectMapper json = new ObjectMapper();
    private final KeywordRegexFieldMapper mapper = new KeywordRegexFieldMapper();

    private final JsonNode schema = read("""
            {"type":"object","properties":{
              "vendor":{"type":"string"},"invoiceNumber":{"type":"string"},
              "invoiceDate":{"type":"string","format":"date"},"dueDate":{"type":"string","format":"date"},
              "currency":{"type":"string"},"subtotal":{"type":"number"},"tax":{"type":"number"},"total":{"type":"number"},
              "lineItems":{"type":"array","items":{"type":"object","properties":{
                 "description":{"type":"string"},"quantity":{"type":"number"},"unitPrice":{"type":"number"},"amount":{"type":"number"}}}}}}""");

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> fields) {
        return (List<Map<String, Object>>) fields.get("lineItems");
    }

    @Test
    void usStyleInvoice() {
        var f = mapper.map("""
                Blue Harbor Consulting LLC
                1200 Market St, San Francisco, CA 94103
                INVOICE
                Invoice #: 10442
                Date of Issue: March 3, 2025
                Payment Due: April 2, 2025
                Bill To: Northwind Traders

                Description              Qty   Unit Price    Amount
                Strategy workshop          2      1,250.00   2,500.00
                Travel expenses            1        310.40     310.40

                Subtotal                                   $2,810.40
                Sales Tax (8.5%)                              238.88
                Balance Due                                $3,049.28
                """, schema).fields();
        assertThat(f).containsEntry("vendor", "Blue Harbor Consulting LLC")
                .containsEntry("invoiceNumber", "10442")
                .containsEntry("invoiceDate", "2025-03-03")
                .containsEntry("dueDate", "2025-04-02")
                .containsEntry("currency", "USD")
                .containsEntry("subtotal", new BigDecimal("2810.40"))
                .containsEntry("tax", new BigDecimal("238.88"))
                .containsEntry("total", new BigDecimal("3049.28"));
        assertThat(items(f)).hasSize(2);
        assertThat(items(f).getFirst()).containsEntry("description", "Strategy workshop")
                .containsEntry("quantity", new BigDecimal("2")).containsEntry("unitPrice", new BigDecimal("1250.00"))
                .containsEntry("amount", new BigDecimal("2500.00"));
    }

    @Test
    void europeanVatInvoiceWithDottedDate() {
        var f = mapper.map("""
                Müller Druck GmbH
                Rechnung / Invoice No. RE-2025-0042
                Invoice Date: 14.02.2025
                Item                     Amount
                Business cards (500)     EUR 89.00
                Flyers A5 (1000)         EUR 140.00
                Net Total: EUR 229.00
                VAT 19%                  43.51
                Total Amount: EUR 272.51
                """, schema).fields();
        assertThat(f).containsEntry("invoiceNumber", "RE-2025-0042")
                .containsEntry("invoiceDate", "2025-02-14")
                .containsEntry("currency", "EUR")
                .containsEntry("subtotal", new BigDecimal("229.00"))
                .containsEntry("tax", new BigDecimal("43.51"))
                .containsEntry("total", new BigDecimal("272.51"));
        assertThat(items(f)).extracting(i -> i.get("description"))
                .containsExactly("Business cards (500) EUR", "Flyers A5 (1000) EUR");
    }

    @Test
    void indianGstSlipWithItemNamesAboveTheirNumbersAndLakhGrouping() {
        var f = mapper.map("""
                Sharma Electronics Pvt Ltd
                GSTIN: 07AABCS1234F1Z5
                Bill No: SE/24-25/0917
                Dated: 05-11-2024
                ITEM HSN QTY RATE AMOUNT
                LED TV 43 INCH
                85287217 1 32,000.00 32,000.00
                Wall mount kit
                83024190 2 750.00 1,500.00
                Taxable Amount 33,500.00
                CGST 9% 3,015.00
                SGST 9% 3,015.00
                Grand Total Rs. 39,530.00
                """, schema).fields();
        assertThat(f).containsEntry("vendor", "Sharma Electronics Pvt Ltd")
                .containsEntry("invoiceNumber", "SE/24-25/0917")
                .containsEntry("invoiceDate", "2024-11-05")
                .containsEntry("currency", "INR")
                .containsEntry("subtotal", new BigDecimal("33500.00"))
                .containsEntry("tax", new BigDecimal("6030.00"))
                .containsEntry("total", new BigDecimal("39530.00"));
        assertThat(items(f)).extracting(i -> i.get("description")).containsExactly("LED TV 43 INCH", "Wall mount kit");
        assertThat(items(f).get(1)).containsEntry("quantity", new BigDecimal("2"))
                .containsEntry("amount", new BigDecimal("1500.00"));
    }

    @Test
    void conceptsAreRecognisedFromManyFieldNamings() {
        assertThat(InvoiceVocabulary.conceptOf("grand_total")).hasValue(InvoiceVocabulary.Concept.TOTAL);
        assertThat(InvoiceVocabulary.conceptOf("amountDue")).hasValue(InvoiceVocabulary.Concept.TOTAL);
        assertThat(InvoiceVocabulary.conceptOf("billNo")).hasValue(InvoiceVocabulary.Concept.INVOICE_NUMBER);
        assertThat(InvoiceVocabulary.conceptOf("totalTax")).hasValue(InvoiceVocabulary.Concept.TAX);
        assertThat(InvoiceVocabulary.conceptOf("taxableAmount")).hasValue(InvoiceVocabulary.Concept.SUBTOTAL);
        assertThat(InvoiceVocabulary.conceptOf("payment_due_date")).hasValue(InvoiceVocabulary.Concept.DUE_DATE);
        assertThat(InvoiceVocabulary.conceptOf("sellerName")).hasValue(InvoiceVocabulary.Concept.VENDOR);
        assertThat(InvoiceVocabulary.conceptOf("notes")).isEmpty();
    }

    @Test
    void numericDatesInAnySeparator() {
        assertThat(ValueParsers.parseDate("28-09-2026")).hasValueSatisfying(d -> assertThat(d).hasToString("2026-09-28"));
        assertThat(ValueParsers.parseDate("2025/02/14")).hasValueSatisfying(d -> assertThat(d).hasToString("2025-02-14"));
        assertThat(ValueParsers.parseDate("14.02.25")).hasValueSatisfying(d -> assertThat(d).hasToString("2025-02-14"));
        assertThat(ValueParsers.parseDate("3rd March 2025")).hasValueSatisfying(d -> assertThat(d).hasToString("2025-03-03"));
        assertThat(ValueParsers.parseDate("12-Mar-2024")).hasValueSatisfying(d -> assertThat(d).hasToString("2024-03-12"));
        assertThat(ValueParsers.parseDate("31-02-2024")).isEmpty();
    }

    @Test
    void aLabelAtTheEndOfALineDoesNotTakeTheNextLinesNumber() {
        var f = mapper.map("TAX TAXABLE AMT RATE TAX AMOUNT\nSOMETHING 54.33 2.50% 1.36", schema).fields();
        assertThat(f.get("tax")).isNull();
    }
}
