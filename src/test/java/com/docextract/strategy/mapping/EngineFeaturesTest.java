package com.docextract.strategy.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Nested objects, booleans, value shapes implied by field names, addresses, statement tables. */
class EngineFeaturesTest {

    private final ObjectMapper json = new ObjectMapper();
    private final KeywordRegexFieldMapper mapper = new KeywordRegexFieldMapper();

    private JsonNode schema(String props) throws Exception {
        return json.readTree("{\"type\":\"object\",\"properties\":" + props + "}");
    }

    private static final String GST_INVOICE = """
            ACME TRADERS PVT LTD
            GSTIN: 07AAACA1234A1Z5
            Address: 14 Industrial Area, Phase 2
            Okhla, New Delhi 110020
            Phone: +91 98765 43210
            Email: billing@acmetraders.in

            Bill To:
            Buyer Name: Sharma Stores
            Buyer GSTIN: 09BBBCS5678B1Z2
            Invoice No: AT/2026/0042
            Invoice Date: 12-10-2026
            Paid: Yes
            Total: 11,800.00
            """;

    @Test
    void nestedPartyObjectsAreReadAsFlattenedFields() throws Exception {
        var r = mapper.map(GST_INVOICE, schema("""
                {"supplier":{"type":"object","properties":{"name":{"type":"string"},"gstin":{"type":"string"},
                   "address":{"type":"string"},"phone":{"type":"string"},"email":{"type":"string"}}},
                 "buyer":{"type":"object","properties":{"name":{"type":"string"},"gstin":{"type":"string"}}},
                 "invoiceNumber":{"type":"string"}}"""));
        @SuppressWarnings("unchecked")
        Map<String, Object> supplier = (Map<String, Object>) r.fields().get("supplier");
        @SuppressWarnings("unchecked")
        Map<String, Object> buyer = (Map<String, Object>) r.fields().get("buyer");
        assertThat(supplier).containsEntry("name", "ACME TRADERS PVT LTD")
                .containsEntry("gstin", "07AAACA1234A1Z5")
                .containsEntry("address", "14 Industrial Area, Phase 2, Okhla, New Delhi 110020")
                .containsEntry("phone", "+91 98765 43210")
                .containsEntry("email", "billing@acmetraders.in");
        // The buyer's GSTIN comes from its own label, never the supplier's (the first GSTIN on the page).
        assertThat(buyer).containsEntry("name", "Sharma Stores").containsEntry("gstin", "09BBBCS5678B1Z2");
        assertThat(r.confidence().get("supplier")).isBetween(0.5, 1.0);
        assertThat(r.fields()).containsEntry("invoiceNumber", "AT/2026/0042");
    }

    @Test
    void objectWithNothingFoundIsNull() throws Exception {
        var f = mapper.map("Total: 5.00", schema("""
                {"shipper":{"type":"object","properties":{"name":{"type":"string"},"address":{"type":"string"}}}}""")).fields();
        assertThat(f).containsEntry("shipper", null);
    }

    @Test
    void booleansFromLabelledYesNo() throws Exception {
        var f = mapper.map(GST_INVOICE + "Reverse Charge: No\n", schema("""
                {"paid":{"type":"boolean"},"reverseCharge":{"type":"boolean"},"signed":{"type":"boolean"}}""")).fields();
        assertThat(f).containsEntry("paid", true).containsEntry("reverseCharge", false).containsEntry("signed", null);
    }

    @Test
    void fieldNamesImplyValueShapesEvenWithoutLabels() throws Exception {
        var f = mapper.map("""
                PRIYA NAIR
                priya.nair@example.com | +91 99887 76655 | linkedin.com/in/priya-nair | github.com/priyan
                Bank: HDFC Bank   IFSC: HDFC0001234   IBAN: GB82 WEST 1234 5698 7654 32
                PAN ABCDE1234F
                """, schema("""
                {"email":{"type":"string"},"phone":{"type":"string"},"linkedin":{"type":"string"},
                 "github":{"type":"string"},"ifscCode":{"type":"string"},"iban":{"type":"string"},
                 "pan":{"type":"string"},"swiftCode":{"type":"string"}}""")).fields();
        assertThat(f).containsEntry("email", "priya.nair@example.com")
                .containsEntry("phone", "+91 99887 76655")
                .containsEntry("linkedin", "linkedin.com/in/priya-nair")
                .containsEntry("github", "github.com/priyan")
                .containsEntry("ifscCode", "HDFC0001234")
                .containsEntry("iban", "GB82 WEST 1234 5698 7654 32")
                .containsEntry("pan", "ABCDE1234F")
                // A SWIFT code is not distinctive enough to guess without a label.
                .containsEntry("swiftCode", null);
    }

    @Test
    void labelledValueMustHaveTheImpliedShape() throws Exception {
        var f = mapper.map("Phone: call reception\nMobile: 916-555-0123 (office)\n", schema("""
                {"phone":{"type":"string"},"mobile":{"type":"string"}}""")).fields();
        assertThat(f).containsEntry("phone", null).containsEntry("mobile", "916-555-0123");
    }

    @Test
    void payslipTotalsAreNotTakenForTheInvoiceTotal() throws Exception {
        var f = mapper.map("""
                SALARY SLIP - SEPTEMBER 2026
                Gross Earnings: 85,000.00
                Total Deductions: 12,400.00
                Net Pay: 72,600.00
                """, schema("""
                {"totalDeductions":{"type":"number"},"netPay":{"type":"number"},"totalEarnings":{"type":"number"}}""")).fields();
        assertThat(f).containsEntry("totalDeductions", new BigDecimal("12400.00"))
                .containsEntry("netPay", new BigDecimal("72600.00"))
                .containsEntry("totalEarnings", null);
    }

    @Test
    void statementTableUsesTheBalanceToTellDebitsFromCredits() throws Exception {
        var r = mapper.map("""
                STATEMENT OF ACCOUNT
                Account No: 50100123456789
                Opening Balance   10,000.00
                Date   Narration   Withdrawal Amt.   Deposit Amt.   Closing Balance
                01/09/2026   UPI-Swiggy-Order   450.00   9,550.00
                03/09/2026   NEFT CR-ACME SALARY   50,000.00   59,550.00
                05/09/2026   ATM WDL MG ROAD   2,000.00   57,550.00
                Closing Balance   57,550.00
                """, schema("""
                {"transactions":{"type":"array","items":{"type":"object","properties":{
                   "date":{"type":"string","format":"date"},"description":{"type":"string"},
                   "debit":{"type":"number"},"credit":{"type":"number"},"balance":{"type":"number"}}}}}"""));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tx = (List<Map<String, Object>>) r.fields().get("transactions");
        assertThat(tx).hasSize(3);
        assertThat(tx.get(0)).containsEntry("date", "2026-09-01").containsEntry("description", "UPI-Swiggy-Order")
                .containsEntry("debit", new BigDecimal("450.00")).containsEntry("balance", new BigDecimal("9550.00"))
                .doesNotContainKey("credit");
        assertThat(tx.get(1)).containsEntry("credit", new BigDecimal("50000.00")).doesNotContainKey("debit");
        assertThat(tx.get(2)).containsEntry("debit", new BigDecimal("2000.00"));
    }

    @Test
    void blockLabelSkipsALineThatCarriesItsOwnLabels() {
        String n = LayoutNormalizer.normalise("""
                Bill To:   Ship To:
                Ship Mode:   Standard Class
                Sean Miller   Gagnoa, Fromager,
                """);
        assertThat(n).contains("Bill To: Sean Miller").doesNotContain("Ship To: Standard Class");
    }

    @Test
    void posSlipFixes() throws Exception {
        var f = mapper.map("""
                CGST(2.50%) 0.53 SGST(2.50%) 0.53    1    21.00    21.00
                Amount Payable    INR 57.00
                Amount in words    Rupees Fifty Seven Only
                Total Discount    -0.01
                TAX    TAXABLE AMT    RATE    TAX AMOUNT
                CGST    54.33    2.50%    1.36
                SGST    54.33    2.50%    1.36
                """, schema("""
                {"cgst":{"type":"number"},"sgst":{"type":"number"},"igst":{"type":"number"},
                 "amountInWords":{"type":"string"}}""")).fields();
        assertThat(f).containsEntry("cgst", new BigDecimal("1.36")).containsEntry("sgst", new BigDecimal("1.36"))
                .containsEntry("igst", null).containsEntry("amountInWords", "Rupees Fifty Seven Only");
    }

    @Test
    void anAddressIsNeverTakenForAPartyName() throws Exception {
        var f = mapper.map("""
                TO    Jens Martensson    DATE    10/13/2026
                CUSTOMER ID    BK06948    SHIP TO    321 Sycamore, Albany, NY 34567
                """, schema("{\"customerName\":{\"type\":\"string\"}}")).fields();
        assertThat(f.get("customerName")).isNull();
    }

    @Test
    void repeatedAddressLinesAreNotDuplicated() {
        String text = LayoutNormalizer.normalise("""
                Registered Address:
                Village Kherki Daula, Delhi Jaipur Highway, Gurugram- 122001
                State Code: 09
                """);
        assertThat(KeywordRegexFieldMapper.continueAddress(text, "Village Kherki Daula, Delhi Jaipur Highway, Gurugram- 122001"))
                .isEqualTo("Village Kherki Daula, Delhi Jaipur Highway, Gurugram- 122001");
    }
}
