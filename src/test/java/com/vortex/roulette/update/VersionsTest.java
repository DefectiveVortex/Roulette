package com.vortex.roulette.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionsTest {

    static void older(String a, String b) {
        assertTrue(Versions.compare(a, b) < 0, a + " < " + b);
        assertTrue(Versions.compare(b, a) > 0, b + " > " + a);
    }

    static void same(String a, String b) {
        assertEquals(0, Versions.compare(a, b), a + " == " + b);
    }

    @Test
    void numericParts() {
        older("1.0", "1.0.1");
        older("1.9", "1.10");
        older("1.0.9", "1.1");
        older("1.99", "2.0");
        same("1.0", "1.0.0");
        same("v1.2", "1.2");
        same("1.2+build.7", "1.2");
    }

    @Test
    void preReleasesSortBelowTheRelease() {
        older("1.1-beta.2", "1.1");
        older("1.1-alpha", "1.1-beta");
        older("1.1-beta", "1.1-rc.1");
        older("1.1-beta.2", "1.1-beta.10");
        older("1.1-beta2", "1.1-beta10");
        older("1.1-beta", "1.1-beta.1");
        older("1.0", "1.1-alpha.1");
        older("1.2.0-beta.2", "1.3.0-alpha.1");
        same("1.1-BETA.1", "1.1-beta.1");
    }

    @Test
    void isNewer() {
        assertTrue(Versions.isNewer("1.0.1", "1.0"));
        assertFalse(Versions.isNewer("1.0", "1.0"));
        assertFalse(Versions.isNewer("1.0-beta.1", "1.0"));
    }
}
