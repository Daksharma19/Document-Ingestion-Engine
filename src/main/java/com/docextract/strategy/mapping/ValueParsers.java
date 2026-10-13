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
    // Grouping covers both 1,234,567.00 and the Indian lakh style 12,34,567.00.
    static final Pattern AMOUNT = Pattern.compile(
            "(?:[$€£₹]|USD|EUR|GBP|INR|Rs\\.?)?\\s?(-?\\d{1,3}(?:,\\d{2,3})*,\\d{3}(?:\\.\\d+)?|-?\\d+(?:\\.\\d+)?)");

    private static final String MONTHS =
            "Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?";

    static final Pattern DATE = Pattern.compile(
            "\\b(\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}"
                    + "|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{4}"
                    + "|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2}(?![\\d.])"
                    + "|\\d{1,2}(?:st|nd|rd|th)?[\\s-]+(?:" + MONTHS + ")\\.?[\\s,-]+\\d{4}"
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
        while (m.find()) {
            // "8.5%" is a rate, not money.
            if (!s.substring(m.end()).matches("(?s)\\s*%.*")) {
                return Optional.of(new BigDecimal(m.group(1).replace(",", "")));
            }
        }
        return Optional.empty();
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

    private static final Pattern YEAR_FIRST = Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");
    private static final Pattern DAY_FIRST = Pattern.compile("(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2}|\\d{4})");

    static Optional<LocalDate> parseDate(String raw) {
        String t = raw.trim();
        // All-numeric dates: normalise any separator; year-first is unambiguous, otherwise day-first.
        Matcher m = YEAR_FIRST.matcher(t);
        if (m.matches()) {
            return safeDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        m = DAY_FIRST.matcher(t);
        if (m.matches()) {
            int year = Integer.parseInt(m.group(3));
            return safeDate(year < 100 ? 2000 + year : year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        }
        String cleaned = t.replaceAll("(?i)(\\d)(st|nd|rd|th)\\b", "$1").replace("-", " ")
                .replace(",", "").replace(".", "").replaceAll("\\s+", " ").trim();
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

    private static Optional<LocalDate> safeDate(int y, int mo, int d) {
        try {
            return Optional.of(LocalDate.of(y, mo, d));
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
    }
}
