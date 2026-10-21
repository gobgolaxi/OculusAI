package me.everyone.oculusai.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("update checks")
class UpdaterTest {

    @Test
    @DisplayName("a higher version is newer, component by component")
    void ordersVersions() {
        assertTrue(Updater.newer("1.3.8", "1.3.7"));
        assertTrue(Updater.newer("1.4.0", "1.3.99"));
        assertTrue(Updater.newer("2.0", "1.9.9"));
        assertTrue(Updater.newer("1.3.8.1", "1.3.8"));
    }

    @Test
    @DisplayName("the same or older version is not newer")
    void refusesSameOrOlder() {
        assertFalse(Updater.newer("1.3.7", "1.3.7"));
        assertFalse(Updater.newer("1.3.7", "1.3.8"));
        assertFalse(Updater.newer("1.3", "1.3.0"));
        assertFalse(Updater.newer("1.2.9", "1.10.0"));
    }

    @Test
    @DisplayName("a version that cannot be parsed is never newer")
    void malformedIsNeverNewer() {
        // This decides whether executable code is downloaded, so anything the
        // comparison cannot read has to mean "do nothing" rather than "try".
        assertFalse(Updater.newer("", "1.3.7"));
        assertFalse(Updater.newer("latest", "1.3.7"));
        assertFalse(Updater.newer("1.3.x", "1.3.7"));
        assertFalse(Updater.newer("../../etc", "1.3.7"));
        assertFalse(Updater.newer("1.3.8", "not-a-version"));
    }

    @Test
    @DisplayName("a pre-release suffix is compared on its numbers")
    void suffixIgnored() {
        assertTrue(Updater.newer("1.3.8-rc1", "1.3.7"));
        assertFalse(Updater.newer("1.3.7-rc1", "1.3.7"));
    }

    @Test
    @DisplayName("release notes are trimmed to one chat line")
    void notesAreOneLine() {
        // The notes come from whoever published the build, and a newline in
        // them would split the reminder into two messages the client renders
        // on their own.
        assertEquals("Faster windows", Updater.firstLine("Faster windows\nand a fix"));
        assertEquals("Faster windows", Updater.firstLine("  Faster windows  "));
        assertEquals("", Updater.firstLine(null));
        assertEquals("", Updater.firstLine("   "));
        assertEquals(120, Updater.firstLine("x".repeat(400)).length());
    }

    @Test
    @DisplayName("only https on the allowed hosts is trusted")
    void trustsOnlyTheAllowlist() {
        assertTrue(Updater.trusted(
                "https://github.com/gobgolaxi/OculusAI/releases/download/v1.3.8/OculusAI.jar"));
        assertTrue(Updater.trusted("https://objects.githubusercontent.com/x.jar"));

        assertFalse(Updater.trusted("http://github.com/x.jar"));
        assertFalse(Updater.trusted("https://evil.example/x.jar"));
        assertFalse(Updater.trusted("file:///tmp/x.jar"));
        assertFalse(Updater.trusted(""));
    }

    @Test
    @DisplayName("a host that merely contains an allowed name is not trusted")
    void refusesLookalikeHosts() {
        // The check is on the parsed host, not on the text of the URL, so none
        // of the usual ways of smuggling a hostname past a substring test work.
        assertFalse(Updater.trusted("https://github.com.evil.example/x.jar"));
        assertFalse(Updater.trusted("https://notgithub.com/x.jar"));
        assertFalse(Updater.trusted("https://evil.example/?u=github.com/x.jar"));
        assertFalse(Updater.trusted("https://evil.example/#github.com"));
        assertFalse(Updater.trusted("https://user@evil.example/x.jar"));
    }
}
