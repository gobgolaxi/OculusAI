package me.everyone.yuppyai.manager;

import com.google.gson.JsonObject;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class AutoModManager implements Manager {

    private final YuppyAI plugin;

    public AutoModManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.config().autoModEnabled();
    }

    public boolean bypass(Player player) {
        return player.hasPermission("yuppyai.automod.bypass");
    }

    public CompletableFuture<Decision> check(Player player, String message) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(Decision.allow());
        }

        return plugin.api().moderateChat(player.getName(), player.getUniqueId().toString(), message)
                .thenApply(this::toDecision)
                .exceptionally(throwable -> Decision.fallback());
    }

    public void notifyBlocked(Player player, String message, Decision decision) {
        String preview = abbreviate(message);
        String reason = decision.reason().isBlank() ? decision.category() : decision.reason();
        String formatted = plugin.config().prefix() + plugin.lang().textOr("automod.staff-blocked",
                "<red>AutoMod blocked <white>%player%<gray>: <white>%category%<gray> | <white>%message%");
        formatted = formatted
                .replace("%player%", player.getName())
                .replace("%category%", friendlyCategory(decision.category()))
                .replace("%message%", preview)
                .replace("%reason%", reason);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("yuppyai.automod.alerts") || online.hasPermission("yuppyai.alerts")) {
                online.sendMessage(Msg.parse(formatted));
            }
        }
        plugin.getLogger().info("AutoMod blocked " + player.getName()
                + " [" + decision.category() + "]: " + preview + " (" + reason + ")");
    }

    private Decision toDecision(JsonObject response) {
        if (response == null) {
            return Decision.fallback();
        }
        String action = string(response, "action", "allow");
        String category = string(response, "category", "safe");
        String reason = string(response, "reason", "");
        double score = response.has("score") ? response.get("score").getAsDouble() : 0.0D;
        return new Decision(action, category, reason, score);
    }

    private String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString()
                : fallback;
    }

    private String friendlyCategory(String category) {
        return switch ((category == null ? "" : category).toLowerCase(Locale.ROOT)) {
            case "advertising" -> "advertising";
            case "scam" -> "scam";
            case "distribution" -> "distribution";
            case "links" -> "links";
            case "sexual" -> "sexual";
            case "toxicity" -> "toxicity";
            default -> category == null || category.isBlank() ? "unknown" : category;
        };
    }

    private String abbreviate(String message) {
        String flat = message == null ? "" : message.replace('\n', ' ').strip();
        return flat.length() <= 120 ? flat : flat.substring(0, 117) + "...";
    }

    public record Decision(String action, String category, String reason, double score) {
        public static Decision allow() {
            return new Decision("allow", "safe", "", 0.0D);
        }

        public static Decision fallback() {
            return new Decision("allow", "fallback", "service unavailable", 0.0D);
        }

        public boolean blocked() {
            return "block".equalsIgnoreCase(action);
        }
    }
}
