package com.docextract.validation;

import java.util.List;

public record ValidationReport(List<Violation> violations) {

    public boolean isValid() {
        return violations.isEmpty();
    }
}
