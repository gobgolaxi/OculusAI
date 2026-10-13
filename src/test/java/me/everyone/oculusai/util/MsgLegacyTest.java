package me.everyone.oculusai.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("legacy colour codes")
class MsgLegacyTest {

    @Test
    @DisplayName("an ampersand code becomes a colour")
    void translatesCodes() {
        assertEquals("§cno analysing", Msg.parse("&cno analysing"));
        assertEquals("§lbold", Msg.parse("&lbold"));
        assertEquals("§creset§r", Msg.parse("&creset&r"));
    }

    @Test
    @DisplayName("an uppercase code is accepted and normalised")
    void acceptsUppercase() {
        assertEquals("§cred", Msg.parse("&Cred"));
    }

    @Test
    @DisplayName("an ampersand that is not a code stays an ampersand")
    void leavesPlainAmpersands() {
        assertEquals("tom & jerry", Msg.parse("tom & jerry"));
        assertEquals("a&", Msg.parse("a&"));
        assertEquals("&z", Msg.parse("&z"));
    }

    @Test
    @DisplayName("the HTML escapes still survive the translation")
    void keepsEscapes() {
        // &amp; begins with an ampersand followed by `a`, which is itself a
        // colour code, so the order these two run in is load-bearing: codes
        // first would eat the escape and leave a stray colour.
        assertEquals("&", Msg.parse("&amp;"));
        assertEquals("<red>", Msg.parse("&lt;red&gt;"));
        assertEquals("&amp;", Msg.parse("&amp;amp;"));
    }

    @Test
    @DisplayName("tags and ampersand codes mix")
    void mixesWithTags() {
        String out = Msg.parse("<white>%player% <dark_gray>| &cno analysing");
        assertTrue(out.contains("§cno analysing"), out);
        assertTrue(out.startsWith("§f"), out);
    }

    @Test
    @DisplayName("strip removes what the parser produced")
    void stripsBoth() {
        assertEquals("no analysing", Msg.strip("&cno analysing"));
        assertEquals("no analysing", Msg.strip("<red>no analysing"));
    }
}
