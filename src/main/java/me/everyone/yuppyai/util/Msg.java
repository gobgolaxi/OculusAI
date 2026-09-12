package me.everyone.yuppyai.util;

import net.md_5.bungee.api.ChatColor;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Msg {

    private static final Pattern TAG = Pattern.compile("<(/?)([^<>]+)>");
    private static final Map<String, String> TOKENS = createTokens();

    private Msg() {
    }

    public static String parse(String input) {
        return colourize(input == null ? "" : input);
    }

    public static String parse(String input, Map<String, String> placeholders) {
        return colourize(fill(input, placeholders));
    }

    public static String fill(String input, Map<String, String> placeholders) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String result = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace('%' + entry.getKey() + '%', entry.getValue());
        }
        return result;
    }

    private static String colourize(String input) {
        // Expand <gradient:from:to>...</gradient> first, so the per-character
        // colour codes it emits are plain §x sequences the tag pass below will
        // simply leave alone. Nested tags like <bold> are preserved through it.
        String gradiented = applyGradients(input);
        StringBuilder out = new StringBuilder();
        Matcher matcher = TAG.matcher(gradiented);
        int cursor = 0;
        while (matcher.find()) {
            out.append(gradiented, cursor, matcher.start());
            String slash = matcher.group(1);
            String token = matcher.group(2).trim().toLowerCase(Locale.ROOT);
            out.append(resolveTag(slash, token));
            cursor = matcher.end();
        }
        out.append(gradiented.substring(cursor));
        return out.toString()
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String resolveTag(String slash, String token) {
        if (!slash.isEmpty()) {
            return closingTag(token);
        }
        if ("newline".equals(token)) {
            return "\n";
        }
        if (token.startsWith("#")) {
            return colour(token);
        }
        return TOKENS.getOrDefault(token, "");
    }

    private static final Pattern GRADIENT =
            Pattern.compile("<gradient:(#?[a-fA-F0-9]{6}):(#?[a-fA-F0-9]{6})>(.*?)</gradient>", Pattern.DOTALL);

    private static String applyGradients(String input) {
        StringBuilder out = new StringBuilder();
        Matcher matcher = GRADIENT.matcher(input);
        int cursor = 0;
        while (matcher.find()) {
            out.append(input, cursor, matcher.start());
            out.append(gradient(matcher.group(3), matcher.group(1), matcher.group(2)));
            cursor = matcher.end();
        }
        out.append(input.substring(cursor));
        return out.toString();
    }

    private static String gradient(String text, String fromHex, String toHex) {
        int from = parseHex(fromHex);
        int to = parseHex(toHex);
        int visible = visibleLength(text);
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') {
                int end = text.indexOf('>', i);
                if (end > i) {
                    out.append(text, i, end + 1);
                    i = end;
                    continue;
                }
            }
            if (visible > 1) {
                double t = (double) index / (visible - 1);
                out.append(hexColour(lerp(from, to, t)));
            }
            out.append(c);
            index++;
        }
        return out.toString();
    }

    private static int visibleLength(String text) {
        int count = 0;
        boolean inTag = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') {
                inTag = true;
            } else if (c == '>') {
                inTag = false;
            } else if (!inTag) {
                count++;
            }
        }
        return count;
    }

    private static int parseHex(String hex) {
        String cleaned = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            return Integer.parseInt(cleaned, 16);
        } catch (NumberFormatException ignored) {
            return 0xFFFFFF;
        }
    }

    private static String hexColour(int rgb) {
        return String.format(Locale.ROOT, "§x§%x§%x§%x§%x§%x§%x",
                (rgb >> 20) & 0xF, (rgb >> 16) & 0xF, (rgb >> 12) & 0xF,
                (rgb >> 8) & 0xF, (rgb >> 4) & 0xF, rgb & 0xF);
    }

    private static int lerp(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static String closingTag(String token) {
        return switch (token) {
            case "bold", "italic", "underlined", "underline", "strikethrough", "st",
                    "obfuscated", "magic", "gradient", "reset" -> ChatColor.RESET.toString();
            default -> "";
        };
    }

    private static String colour(String token) {
        try {
            return ChatColor.of(token).toString();
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static Map<String, String> createTokens() {
        Map<String, String> tokens = new HashMap<>();
        tokens.put("black", ChatColor.BLACK.toString());
        tokens.put("dark_blue", ChatColor.DARK_BLUE.toString());
        tokens.put("dark_green", ChatColor.DARK_GREEN.toString());
        tokens.put("dark_aqua", ChatColor.DARK_AQUA.toString());
        tokens.put("dark_red", ChatColor.DARK_RED.toString());
        tokens.put("dark_purple", ChatColor.DARK_PURPLE.toString());
        tokens.put("gold", ChatColor.GOLD.toString());
        tokens.put("gray", ChatColor.GRAY.toString());
        tokens.put("grey", ChatColor.GRAY.toString());
        tokens.put("dark_gray", ChatColor.DARK_GRAY.toString());
        tokens.put("dark_grey", ChatColor.DARK_GRAY.toString());
        tokens.put("blue", ChatColor.BLUE.toString());
        tokens.put("green", ChatColor.GREEN.toString());
        tokens.put("aqua", ChatColor.AQUA.toString());
        tokens.put("red", ChatColor.RED.toString());
        tokens.put("light_purple", ChatColor.LIGHT_PURPLE.toString());
        tokens.put("yellow", ChatColor.YELLOW.toString());
        tokens.put("white", ChatColor.WHITE.toString());
        tokens.put("bold", ChatColor.BOLD.toString());
        tokens.put("italic", ChatColor.ITALIC.toString());
        tokens.put("underlined", ChatColor.UNDERLINE.toString());
        tokens.put("underline", ChatColor.UNDERLINE.toString());
        tokens.put("strikethrough", ChatColor.STRIKETHROUGH.toString());
        tokens.put("st", ChatColor.STRIKETHROUGH.toString());
        tokens.put("obfuscated", ChatColor.MAGIC.toString());
        tokens.put("magic", ChatColor.MAGIC.toString());
        tokens.put("reset", ChatColor.RESET.toString());
        return tokens;
    }

    public static String round(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    public static String percent(double fraction) {
        return Math.round(fraction * 100.0D) + "%";
    }

    public static String heatColour(double fraction) {
        double clamped = Math.max(0.0D, Math.min(1.0D, fraction));
        int red = (int) Math.round(80 + 175 * clamped);
        int green = (int) Math.round(220 - 190 * clamped);
        return String.format(Locale.ROOT, "#%02x%02x%02x", red, green, 70);
    }

    public static String bar(double fraction, int width) {
        double clamped = Math.max(0.0D, Math.min(1.0D, fraction));
        int filled = (int) Math.round(clamped * width);
        StringBuilder builder = new StringBuilder("<").append(heatColour(clamped)).append('>');
        builder.append("|".repeat(filled));
        builder.append("<dark_gray>").append(".".repeat(width - filled));
        return builder.toString();
    }
}
