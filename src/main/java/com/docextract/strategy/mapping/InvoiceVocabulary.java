package com.docextract.strategy.mapping;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Domain knowledge the mapper uses for any invoice/receipt schema: it recognises what a field
 * <em>means</em> from its name ({@code grandTotal}, {@code amount_due}, {@code billNo}, ...) and supplies the
 * label wordings that real documents use for that concept, most specific first. The first wording found in the
 * document wins, so "Amount Payable 57.00" beats an earlier "Total 57.06" before rounding.
 */
public final class InvoiceVocabulary {

    private InvoiceVocabulary() {
    }

    public enum Concept {
        SUBTOTAL(List.of("sub total", "subtotal", "sub-total", "taxable amount", "taxable value", "amount before tax",
                "total before tax", "net amount", "net total", "total excl. vat", "total excl vat", "total excluding tax")),
        TAX(List.of("total tax", "tax amount", "total gst", "gst amount", "total vat", "vat amount", "sales tax",
                "igst", "gst", "vat", "tax")),
        TOTAL(List.of("amount payable", "net payable", "total payable", "amount due", "balance due", "total due",
                "grand total", "invoice total", "total amount", "total bill amount", "bill amount", "total inr",
                "total incl. vat", "total including tax", "total")),
        DISCOUNT(List.of("total discount", "discount", "less discount")),
        INVOICE_NUMBER(List.of("invoice number", "invoice no.", "invoice no", "invoice #", "inv no", "inv. no.",
                "bill no.", "bill no", "bill number", "receipt no.", "receipt no", "receipt number", "document no")),
        ORDER_NUMBER(List.of("order number", "order no.", "order no", "order #", "po number", "po no", "purchase order")),
        DUE_DATE(List.of("due date", "payment due", "pay by", "due on", "due")),
        INVOICE_DATE(List.of("invoice date", "date of issue", "issue date", "issued on", "bill date", "invoice dt",
                "dated", "date")),
        // "from:" only with its colon, so prose ("from the date of purchase") does not match.
        VENDOR(List.of("sold by", "seller", "supplier", "vendor", "billed by", "merchant", "from:")),
        CUSTOMER(List.of("bill to", "billed to", "customer name", "sold to", "ship to", "customer", "client", "buyer")),
        TAX_ID(List.of("gstin", "gst no", "vat no", "vat number", "tax id", "tin", "ein", "abn")),
        CURRENCY(List.of("currency"));

        private final List<String> labels;

        Concept(List<String> labels) {
            this.labels = labels;
        }

        public List<String> labels() {
            return labels;
        }
    }

    /** What an invoice field means, judged from its name alone; empty when it is not a known invoice concept. */
    public static Optional<Concept> conceptOf(String fieldName) {
        String n = fieldName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.contains("currency")) {
            return Optional.of(Concept.CURRENCY);
        }
        if (n.contains("subtotal") || n.contains("taxable") || n.contains("pretax") || n.contains("beforetax") || n.equals("netamount")) {
            return Optional.of(Concept.SUBTOTAL);
        }
        if (n.contains("discount")) {
            return Optional.of(Concept.DISCOUNT);
        }
        if (n.matches(".*(gstin|taxid|vatno|vatnumber|tin|ein|abn)$")) {
            return Optional.of(Concept.TAX_ID);
        }
        if (n.matches("(total)?(tax|vat|gst|salestax)(amount|total)?")) {
            return Optional.of(Concept.TAX);
        }
        // Only the document's own date is the "invoice date"; deliveryDate, birthDate, shipDate... are other facts
        // and are found by their own names, never guessed.
        if (n.matches("(payment)?due(date|on|by)?|(payment|pay)(due)?(date|by)")) {
            return Optional.of(Concept.DUE_DATE);
        }
        if (n.matches("(invoice|issue|bill|document|doc|receipt|purchase|transaction|order)?(date|dt|dated)|dateofissue|issuedon")) {
            return Optional.of(Concept.INVOICE_DATE);
        }
        if (n.contains("date")) {
            return Optional.empty();
        }
        if (n.contains("total") || n.contains("amountdue") || n.contains("payable") || n.contains("balancedue")
                || n.equals("amount") || n.equals("grandtotal")) {
            return Optional.of(Concept.TOTAL);
        }
        if (n.matches("(order|po|purchaseorder)(number|no|num|id)?")) {
            return Optional.of(Concept.ORDER_NUMBER);
        }
        if (n.matches("(invoice|inv|bill|receipt|document)(number|no|num|id)?") || n.matches("(number|no)")) {
            return Optional.of(Concept.INVOICE_NUMBER);
        }
        // A party's id/number/phone/address is not the party's name: leave those to their own labels.
        if (n.matches(".*(id|number|no|num|code|phone|email|address|gstin|vat|tax)$")) {
            return Optional.empty();
        }
        if (n.matches(".*(vendor|seller|supplier|merchant|company|issuer|store|biller|shop).*")) {
            return Optional.of(Concept.VENDOR);
        }
        if (n.matches(".*(customer|buyer|client|billto|soldto|shipto|recipient).*")) {
            return Optional.of(Concept.CUSTOMER);
        }
        return Optional.empty();
    }

    /** ISO code for the first currency code, symbol or "Rs" in the text. */
    public static Optional<String> detectCurrency(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?<![A-Za-z])(USD|EUR|GBP|INR|AUD|CAD|JPY|CNY|AED|SGD|CHF)(?![A-Za-z])|([$€£₹¥])|(?<![A-Za-z])(Rs\\.?|Rupees)(?![A-Za-z])")
                .matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        if (m.group(1) != null) {
            return Optional.of(m.group(1));
        }
        if (m.group(3) != null) {
            return Optional.of("INR");
        }
        return Optional.of(switch (m.group(2)) {
            case "$" -> "USD";
            case "€" -> "EUR";
            case "£" -> "GBP";
            case "₹" -> "INR";
            default -> "JPY";
        });
    }
}
