package com.docextract.strategy.mapping;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ValueParsers {

    private ValueParsers() {
    }

    // Optional currency symbol/code, then a number with optional thousands separators and decimals.
    static final Pattern AMOUNT = Pattern.compile(
            "(?:[$€£₹]|USD|EUR|GBP|INR)?\\s?(-?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|-?\\d+(?:\\.\\d+)?)");

    private static final String MONTHS =
            "Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?";

    static final Pattern DATE = Pattern.compile(
            "\\b(\\d{4}-\\d{2}-\\d{2}"
                    + "|\\d{1,2}/\\d{1,2}/\\d{4}"
                    + "|\\d{1,2}\\s+(?:" + MONTHS + ")\\.?\\s+\\d{4}"
                    + "|(?:" + MONTHS + ")\\.?\\s+\\d{1,2},?\\s+\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    // Slash dates are read day-first (dd/MM/yyyy); documented assumption, US documents will misparse.
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            caseInsensitive("d MMMM uuuu"),
            caseInsensitive("d MMM uuuu"),
            caseInsensitive("MMMM d uuuu"),
            caseInsensitive("MMM d uuuu"));

    private static DateTimeFormatter caseInsensitive(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    static Optional<BigDecimal> firstAmount(String s) {
        Matcher m = AMOUNT.matcher(s);
        return m.find() ? Optional.of(new BigDecimal(m.group(1).replace(",", ""))) : Optional.empty();
    }

    /** Dates are blanked out first so their digits are not read as amounts. */
    static List<BigDecimal> allAmounts(String s) {
        List<BigDecimal> out = new ArrayList<>();
        Matcher m = AMOUNT.matcher(DATE.matcher(s).replaceAll(" "));
        while (m.find()) {
            out.add(new BigDecimal(m.group(1).replace(",", "")));
        }
        return out;
    }

    static Optional<LocalDate> firstDate(String s) {
        Matcher m = DATE.matcher(s);
        while (m.find()) {
            Optional<LocalDate> d = parseDate(m.group(1));
            if (d.isPresent()) {
                return d;
            }
        }
        return Optional.empty();
    }

    static Optional<LocalDate> parseDate(String raw) {
        String cleaned = raw.replace(",", "").replace(".", "").replaceAll("\\s+", " ").trim();
        cleaned = cleaned.replaceAll("(?i)\\bSept\\b", "Sep");
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(cleaned, f));
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        return Optional.empty();
    }
}
