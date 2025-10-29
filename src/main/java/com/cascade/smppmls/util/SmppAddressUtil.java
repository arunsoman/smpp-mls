package com.cascade.smppmls.util;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.TypeOfNumber;

/**
 * Utility class for determining proper TON (Type of Number) and NPI (Numbering Plan Indicator)
 * for SMPP addresses based on the number format.
 */
public class SmppAddressUtil {

    /**
     * Address information containing TON, NPI, and normalized address
     */
    @Getter
    @AllArgsConstructor
    public static class AddressInfo {
        private final TypeOfNumber ton;
        private final NumberingPlanIndicator npi;
        private final String address;
    }

    /**
     * Determine TON/NPI for source address (sender)
     * 
     * @param sourceAddr The source address (can be alphanumeric or numeric)
     * @return AddressInfo with appropriate TON, NPI, and normalized address
     */
    public static AddressInfo getSourceAddressInfo(String sourceAddr) {
        if (sourceAddr == null || sourceAddr.isEmpty()) {
            return new AddressInfo(TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, "");
        }

        // Remove any whitespace
        String cleaned = sourceAddr.trim();

        // Check if it's alphanumeric (contains letters)
        if (cleaned.matches(".*[a-zA-Z].*")) {
            // Alphanumeric sender ID
            return new AddressInfo(TypeOfNumber.ALPHANUMERIC, NumberingPlanIndicator.UNKNOWN, cleaned);
        }

        // Remove all non-digit characters for numeric analysis
        String digits = cleaned.replaceAll("\\D", "");

        if (digits.isEmpty()) {
            return new AddressInfo(TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, cleaned);
        }

        // Check if it starts with + or 00 (international format)
        if (cleaned.startsWith("+") || cleaned.startsWith("00")) {
            // International number
            return new AddressInfo(TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, digits);
        }

        // Check if it's a short code (typically 3-6 digits)
        if (digits.length() >= 3 && digits.length() <= 6) {
            // Short code
            return new AddressInfo(TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, digits);
        }

        // Check if it looks like an international number without prefix (e.g., starts with country code)
        // Afghanistan: 93, most international numbers are 10-15 digits
        if (digits.length() >= 10 && digits.length() <= 15) {
            // Likely international format without prefix
            return new AddressInfo(TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, digits);
        }

        // Default to national number
        return new AddressInfo(TypeOfNumber.NATIONAL, NumberingPlanIndicator.ISDN, digits);
    }

    /**
     * Determine TON/NPI for destination address (recipient)
     * 
     * @param destAddr The destination address (typically numeric)
     * @return AddressInfo with appropriate TON, NPI, and normalized address
     */
    public static AddressInfo getDestinationAddressInfo(String destAddr) {
        if (destAddr == null || destAddr.isEmpty()) {
            return new AddressInfo(TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, "");
        }

        // Remove any whitespace
        String cleaned = destAddr.trim();

        // Remove all non-digit characters
        String digits = cleaned.replaceAll("\\D", "");

        if (digits.isEmpty()) {
            return new AddressInfo(TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, cleaned);
        }

        // Check if it starts with + or 00 (international format)
        if (cleaned.startsWith("+") || cleaned.startsWith("00")) {
            // International number
            return new AddressInfo(TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, digits);
        }

        // Check if it's a short code (typically 3-6 digits)
        if (digits.length() >= 3 && digits.length() <= 6) {
            // Short code
            return new AddressInfo(TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, digits);
        }

        // Check if it looks like an international number (10-15 digits)
        if (digits.length() >= 10 && digits.length() <= 15) {
            // International format
            return new AddressInfo(TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, digits);
        }

        // Default to national number
        return new AddressInfo(TypeOfNumber.NATIONAL, NumberingPlanIndicator.ISDN, digits);
    }
}
