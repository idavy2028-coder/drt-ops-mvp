package com.idavy.drtops.domain.onboard;

final class OnboardText {

    static final int MAX_AUDIT_TEXT_LENGTH = 500;

    private OnboardText() {
    }

    static String requireAuditText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.codePointCount(0, value.length()) > MAX_AUDIT_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + MAX_AUDIT_TEXT_LENGTH + " characters");
        }
        return value;
    }
}
