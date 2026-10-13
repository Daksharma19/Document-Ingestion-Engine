package com.docextract.strategy;

/** The tier exists but cannot run in this deployment (e.g. no API key). Never a fake result. */
public class TierNotConfiguredException extends RuntimeException {
    public TierNotConfiguredException(String message) {
        super(message);
    }
}
