package com.docextract.schema;

import com.docextract.schema.SchemaCatalog.SchemaPreset;
import com.docextract.validation.expr.RuleExpression;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SchemaCatalogTest {

    private final SchemaCatalog catalog = new SchemaCatalog(new ObjectMapper());
    private final DocumentClassifier classifier = new DocumentClassifier(catalog);

    @Test
    void everyBuiltInSchemaIsAValidJsonSchemaWithParseableRules() {
        assertThat(catalog.all()).hasSizeGreaterThanOrEqualTo(15);
        Set<String> ids = new HashSet<>();
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        for (SchemaPreset p : catalog.all()) {
            assertThat(ids.add(p.id())).as("unique id " + p.id()).isTrue();
            assertThat(p.label()).isNotBlank();
            assertThat(p.schema().path("properties").isObject()).as(p.id() + " has properties").isTrue();
            assertThatCode(() -> factory.getSchema(p.schema())).doesNotThrowAnyException();
            p.rules().forEach(r -> assertThatCode(() -> RuleExpression.parse(r)).as(p.id() + ": " + r).doesNotThrowAnyException());
            p.schema().path("required").forEach(req ->
                    assertThat(p.schema().path("properties").has(req.asText())).as(p.id() + " requires a declared field").isTrue());
        }
        assertThat(catalog.all().getFirst().id()).isEqualTo("invoice");
        assertThat(catalog.find("BANK-STATEMENT")).isPresent();
    }

    @Test
    void classifierPicksTheDocumentType() {
        assertThat(classifier.classify("""
                TAX INVOICE
                GSTIN: 07AAACA1234A1Z5   State Code: 07
                Place of Supply: Delhi
                HSN   Qty   Rate   Amount
                CGST 9%   90.00   SGST 9%   90.00
                """).preset().id()).isEqualTo("gst-invoice");
        assertThat(classifier.classify("""
                HDFC BANK
                Statement of Account
                Opening Balance 10,000.00
                Date  Narration  Withdrawal  Deposit  Closing Balance
                """).preset().id()).isEqualTo("bank-statement");
        assertThat(classifier.classify("""
                Salary Slip for September 2026
                Employee ID: E-102   Basic: 40,000   HRA: 16,000
                Gross Earnings 85,000   Deductions 12,400   Net Pay 72,600
                """).preset().id()).isEqualTo("payslip");
        assertThat(classifier.classify("""
                PRIYA NAIR
                SUMMARY
                Backend engineer.
                EXPERIENCE
                EDUCATION
                SKILLS
                """).preset().id()).isEqualTo("resume");
        assertThat(classifier.classify("Hello there, see you on Monday.").preset().id()).isEqualTo("generic");
        // Shared words ("invoice", "tax invoice") must not tie a GST invoice with the basic invoice.
        assertThat(classifier.classify("""
                TAX INVOICE
                GSTIN: 07AAACA1234A1Z5
                Invoice No: AT/1
                Place of Supply: Delhi
                Total: 1,180.00
                """).preset().id()).isEqualTo("gst-invoice");
        assertThat(classifier.classify("""
                INVOICE
                Invoice No: INV-9
                Bill To: Sharma Stores
                Subtotal: 100.00
                Amount Due: 118.00
                """).preset().id()).startsWith("invoice");
        // A document titled INVOICE stays an invoice even when it lists delivery dates and payment terms.
        assertThat(classifier.classify("""
                INVOICE
                SHADOW BEAUTY    Make all checks payable to
                CUSTOMER ID    BK06948    SHIP TO    321 Sycamore, Albany
                SALESPERSON    SHIPPING METHOD    DELIVERY DATE    PAYMENT TERMS    DUE DATE
                QTY    ITEM #    DESCRIPTION    UNIT PRICE    LINE TOTAL
                """).preset().id()).startsWith("invoice");
        assertThat(classifier.classify("""
                PURCHASE ORDER
                PO Number: PO-77   Order Date: 2026-10-01
                Vendor: Acme   Ship To: Plant 2   Delivery Date: 2026-10-20
                """).preset().id()).isEqualTo("purchase-order");
    }
}
