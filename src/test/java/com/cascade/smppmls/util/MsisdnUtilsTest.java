package com.cascade.smppmls.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class MsisdnUtilsTest {

    private static final String CC = "93";

    @Test
    public void testNormalizeWithPlus() {
        assertEquals("+93791234567", MsisdnUtils.normalizeToE164("+93791234567", CC));
    }

    @Test
    public void testNormalizeWith00() {
        assertEquals("+93791234567", MsisdnUtils.normalizeToE164("0093791234567", CC));
    }

    @Test
    public void testNormalizePlainWithCountry() {
        assertEquals("+93791234567", MsisdnUtils.normalizeToE164("93791234567", CC));
    }

    @Test
    public void testNormalizeWithLeadingZero() {
        assertEquals("+93791234567", MsisdnUtils.normalizeToE164("0791234567", CC));
    }

    @Test
    public void testNormalizeShortLocal() {
        assertEquals("+93791234567", MsisdnUtils.normalizeToE164("791234567", CC));
    }

    @Test
    public void testNormalizeNullAndEmpty() {
        assertNull(MsisdnUtils.normalizeToE164(null, CC));
        assertNull(MsisdnUtils.normalizeToE164("", CC));
    }
}
