package com.docextract.strategy.mapping;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Value shapes the mapper knows from a field's <em>name</em> alone, for any document type: an {@code email} field
 * holds an e-mail address, {@code phone}/{@code mobile} a phone number, {@code gstin} a GSTIN, and so on.
 * A labelled value must contain the shape (the match is extracted, so "Phone: +1 916-555-0123 (office)" gives the
 * number). Without a label only <b>distinctive</b> shapes are searched for in the whole text: an IBAN or e-mail
 * address cannot be mistaken for something else, but a bare SWIFT code could be any capitalised word.
 */
public final class CommonFormats {

    private CommonFormats() {
    }

    /** @param distinctive whether an unlabelled match anywhere in the document can be trusted */
    public record Format(String name, Pattern pattern, boolean distinctive) {
    }

    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    // 8-15 digits with optional +country code, brackets, spaces, dots or dashes; never part of a longer number.
    static final Pattern PHONE = Pattern.compile(
            "(?<![\\w.+-])(?:\\+\\d{1,3}[\\s.-]?)?(?:\\(\\d{1,5}\\)[\\s.-]?)?\\d{2,5}(?:[\\s.-]?\\d{2,5}){1,3}(?![\\w-])");
    static final Pattern WEBSITE = Pattern.compile(
            "(?i)(?<![@\\w.])(?:https?://)?(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|org|net|io|in|co|ai|dev|edu|gov|biz|info|me|app|uk|us|au|de)(?:/[^\\s]*)?(?![\\w@])");
    static final Pattern LINKEDIN = Pattern.compile("(?i)(?:https?://)?(?:[a-z]{2,3}\\.)?linkedin\\.com/[^\\s]+");
    static final Pattern GITHUB = Pattern.compile("(?i)(?:https?://)?github\\.com/[^\\s]+");
    static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\\b");
    static final Pattern IFSC = Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b");
    static final Pattern GSTIN = Pattern.compile("\\b\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]\\b");
    static final Pattern PAN = Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b");
    static final Pattern SWIFT = Pattern.compile("\\b[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?\\b");
    static final Pattern POSTAL_CODE = Pattern.compile("\\b(?:\\d{5}(?:-\\d{4})?|\\d{6}|[A-Z]\\d[A-Z] ?\\d[A-Z]\\d|[A-Z]{1,2}\\d[A-Z\\d]? ?\\d[A-Z]{2})\\b");
    static final Pattern TIME = Pattern.compile("(?i)\\b(?:[01]?\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d)?(?:\\s?[AP]M)?\\b");
    static final Pattern PERCENT = Pattern.compile("\\d{1,3}(?:\\.\\d+)?\\s?%");

    /** The shape implied by a field name, if any. */
    public static Optional<Format> forField(String fieldName) {
        String n = fieldName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.contains("email") || n.equals("mail")) {
            return Optional.of(new Format("email", EMAIL, true));
        }
        if (n.contains("linkedin")) {
            return Optional.of(new Format("linkedin", LINKEDIN, true));
        }
        if (n.contains("github")) {
            return Optional.of(new Format("github", GITHUB, true));
        }
        if (n.matches(".*(website|homepage|url|webaddress|portfolio)$") || n.equals("web") || n.equals("site")) {
            return Optional.of(new Format("website", WEBSITE, true));
        }
        if (n.contains("iban")) {
            return Optional.of(new Format("iban", IBAN, true));
        }
        if (n.contains("ifsc")) {
            return Optional.of(new Format("ifsc", IFSC, true));
        }
        if (n.contains("gstin") || n.matches(".*gst(no|number|regno|registration)")) {
            return Optional.of(new Format("gstin", GSTIN, true));
        }
        if (n.matches("(.*_)?pan(no|number|card)?|.*pan(no|number)")) {
            return Optional.of(new Format("pan", PAN, true));
        }
        if (n.matches(".*(swift|bic)(code)?")) {
            return Optional.of(new Format("swift", SWIFT, false));
        }
        if (n.matches(".*(phone|mobile|telephone|tel|cell|fax|contactno|contactnumber|whatsapp)(no|number|num)?\\d?")) {
            return Optional.of(new Format("phone", PHONE, false));
        }
        if (n.matches(".*(zip|postal|postcode|pincode|pin)(code)?")) {
            return Optional.of(new Format("postal-code", POSTAL_CODE, false));
        }
        if (n.matches(".*time$") && !n.matches(".*(lifetime|overtime|fulltime|parttime)$")) {
            return Optional.of(new Format("time", TIME, true));
        }
        return Optional.empty();
    }

    /** First match of the format in a labelled value; phone numbers need at least 8 digits. */
    static Optional<String> find(Format format, String raw) {
        java.util.regex.Matcher m = format.pattern().matcher(raw);
        while (m.find()) {
            String v = m.group().trim();
            if (format.name().equals("phone") && v.replaceAll("\\D", "").length() < 8) {
                continue;
            }
            return Optional.of(v.replaceAll("[.,;)]+$", ""));
        }
        return Optional.empty();
    }
}
