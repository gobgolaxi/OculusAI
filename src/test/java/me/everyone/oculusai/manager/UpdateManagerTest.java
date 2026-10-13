package me.everyone.oculusai.manager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("update checks")
class UpdateManagerTest {

    @Test
    @DisplayName("a higher version is newer, component by component")
    void ordersVersions() {
        assertTrue(UpdateManager.newer("1.3.8", "1.3.7"));
        assertTrue(UpdateManager.newer("1.4.0", "1.3.99"));
        assertTrue(UpdateManager.newer("2.0", "1.9.9"));
        assertTrue(UpdateManager.newer("1.3.8.1", "1.3.8"));
    }

    @Test
    @DisplayName("the same or older version is not newer")
    void refusesSameOrOlder() {
        assertFalse(UpdateManager.newer("1.3.7", "1.3.7"));
        assertFalse(UpdateManager.newer("1.3.7", "1.3.8"));
        assertFalse(UpdateManager.newer("1.3", "1.3.0"));
        assertFalse(UpdateManager.newer("1.2.9", "1.10.0"));
    }

    @Test
    @DisplayName("a version that cannot be parsed is never newer")
    void malformedIsNeverNewer() {
        // This decides whether executable code is downloaded, so anything the
        // comparison cannot read has to mean "do nothing" rather than "try".
        assertFalse(UpdateManager.newer("", "1.3.7"));
        assertFalse(UpdateManager.newer("latest", "1.3.7"));
        assertFalse(UpdateManager.newer("1.3.x", "1.3.7"));
        assertFalse(UpdateManager.newer("../../etc", "1.3.7"));
        assertFalse(UpdateManager.newer("1.3.8", "not-a-version"));
    }

    @Test
    @DisplayName("a pre-release suffix is compared on its numbers")
    void suffixIgnored() {
        assertTrue(UpdateManager.newer("1.3.8-rc1", "1.3.7"));
        assertFalse(UpdateManager.newer("1.3.7-rc1", "1.3.7"));
    }

    @Test
    @DisplayName("only https on the allowed hosts is trusted")
    void trustsOnlyTheAllowlist() {
        assertTrue(UpdateManager.trusted(
                "https://github.com/gobgolaxi/OculusAI/releases/download/v1.3.8/OculusAI.jar"));
        assertTrue(UpdateManager.trusted("https://objects.githubusercontent.com/x.jar"));

        assertFalse(UpdateManager.trusted("http://github.com/x.jar"));
        assertFalse(UpdateManager.trusted("https://evil.example/x.jar"));
        assertFalse(UpdateManager.trusted("file:///tmp/x.jar"));
        assertFalse(UpdateManager.trusted(""));
    }

    @Test
    @DisplayName("a host that merely contains an allowed name is not trusted")
    void refusesLookalikeHosts() {
        // The check is on the parsed host, not on the text of the URL, so none
        // of the usual ways of smuggling a hostname past a substring test work.
        assertFalse(UpdateManager.trusted("https://github.com.evil.example/x.jar"));
        assertFalse(UpdateManager.trusted("https://notgithub.com/x.jar"));
        assertFalse(UpdateManager.trusted("https://evil.example/?u=github.com/x.jar"));
        assertFalse(UpdateManager.trusted("https://evil.example/#github.com"));
        assertFalse(UpdateManager.trusted("https://user@evil.example/x.jar"));
    }
}
