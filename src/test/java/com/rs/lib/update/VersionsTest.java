package com.rs.lib.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionsTest {

    @Test
    void numericPartsMissingPartsAndPreReleases() {
        assertTrue(Versions.isNewer("1.10.0", "1.9.9"));
        assertTrue(Versions.isNewer("1.2.1", "1.2"));
        assertFalse(Versions.isNewer("1.2.0", "1.2"));
        assertFalse(Versions.isNewer("1.2", "1.2.0"));
        assertTrue(Versions.isNewer("2.0.0", "2.0.0-beta"));
        assertFalse(Versions.isNewer("2.0.0-beta", "2.0.0"));
        assertTrue(Versions.isNewer("2.0.0-beta", "1.9.0"));
        assertTrue(Versions.isNewer("2.0.0-beta.2", "2.0.0-beta.1"));
        assertTrue(Versions.isNewer("v1.3", "1.2.9"));
        assertFalse(Versions.isNewer("1.2.0+build.7", "1.2.0"));
        assertEquals(0, Versions.compare("v1.0", "1.0.0"));
    }

    @Test
    void unparsableIsNeverNewer() {
        assertFalse(Versions.isNewer("nightly", "1.0.0"));
        assertFalse(Versions.isNewer("2.0.0", "dev-build"));
        assertFalse(Versions.isNewer("", "1.0.0"));
        assertFalse(Versions.isNewer(null, "1.0.0"));
        assertFalse(Versions.isNewer("1..2", "1.0"));
        assertFalse(Versions.isValid("v"));
        assertTrue(Versions.isValid("1.0.0-SNAPSHOT"));
    }

    @Test
    void normalizeAndMajor() {
        assertEquals("1.4.0", Versions.normalize(" v1.4.0 "));
        assertEquals("v", Versions.normalize("v"));
        assertNull(Versions.normalize(null));
        assertFalse(Versions.isValid("vv1.4.0"));
        assertEquals(2, Versions.major("v2.10.1"));
        assertEquals(-1, Versions.major("x"));
    }
}
