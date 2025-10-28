package com.cascade.smppmls.util;

import java.util.Objects;

/**
 * Small utility to normalize MSISDNs to E.164 format (leading + and country code).
 *
 * Assumptions:
 * - Default country code is provided (e.g. "93" for Afghanistan).
 * - Input may include +, 00, leading 0, or plain national/local format.
 * - Non-digit characters are stripped.
 *
 * Examples:
 * - "93791234567" -> +93791234567
 * - "+93791234567" -> +93791234567
 * - "0093791234567" -> +93791234567
 * - "0791234567" -> +93791234567  (leading 0 removed and default country code applied)
 * - "791234567" -> +93791234567  (assume local without leading 0)
 */
public final class MsisdnUtils {

    private MsisdnUtils() {
    }

    public static String normalizeToE164(String input, String defaultCountryCode) {
        Objects.requireNonNull(defaultCountryCode, "defaultCountryCode must not be null");
        if (input == null) return null;

        String digits = input.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;

        // If starts with international 00 prefix, strip it
        if (digits.startsWith("00") && digits.length() > 2) {
            digits = digits.substring(2);
        }

        // If starts with '+', the digits string will not have it because we stripped non-digits
        // Now we handle common lengths:
        // - If digits already start with country code (e.g., 93...), and length >= countryCode.length()+minSubscriber
        if (digits.startsWith(defaultCountryCode)) {
            // e.g., 93XXXXXXXXX -> +93XXXXXXXXX
            return "+" + digits;
        }

        // If digits starts with a single leading '0' (national format), remove it and prepend country
        if (digits.startsWith("0") && digits.length() > 1) {
            String withoutZero = digits.substring(1);
            return "+" + defaultCountryCode + withoutZero;
        }

        // If digits length looks like local subscriber (e.g., 7-9 digits), prepend country code
        // Common Afghan mobile subscriber lengths are 9 (e.g., 79xxxxxxx) after country code
        if (digits.length() <= 9) {
            return "+" + defaultCountryCode + digits;
        }

        // Fallback: if none of the above matched, just prefix with +
        return "+" + digits;
    }
}
