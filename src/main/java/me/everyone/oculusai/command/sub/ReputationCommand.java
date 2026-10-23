package me.everyone.oculusai.command.sub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.util.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /oai reputation <name|uuid>} - who a player is, and what the whole
 *  service has seen them do.
 *
 *  <p>The argument is a name because that is what an operator has in front of
 *  them, but a name is not who anybody is: two people can hold the same one
 *  on two servers, and one person can change theirs between kicks. The
 *  identity is the account id - the UUID - and that is what the service files
 *  everything under. So a name that matches one identity answers directly,
 *  and a name that matches several prints the shortlist with a UUID against
 *  each, to be asked again by UUID.
 *
 *  <p>An identity from an offline-mode server is marked as weak, because it
 *  is: there the UUID is computed from the name, so it says nothing the name
 *  did not already say, and the only thing linking two sessions is the
 *  address.
 *
 *  <p>The reading is for a person, never for a punishment. Addresses are
 *  shared by households, phone networks and whole carriers, so this prints
 *  what was observed and leaves the decision where it belongs.
 */
public final class ReputationCommand extends SubCommand {

    public ReputationCommand(OculusAI plugin) {
        super(plugin, "reputation", List.of("rep", "who"), "oculusai.reputation",
                "/oai reputation <name|uuid>",
                "Who a player is, and their reputation across every server.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            replyKey(sender, "reputation.usage", Map.of("value", usageText()));
            return;
        }
        if (!plugin.config().reputationEnabled()) {
            replyKey(sender, "reputation.disabled");
            return;
        }
        // An online player is asked about by their UUID rather than by the
        // name that was typed: the server already knows which of the people
        // called that is standing in front of the operator, so there is no
        // reason to make them disambiguate a name the server could resolve.
        Player online = plugin.getServer().getPlayerExact(args[0]);
        String query = online != null ? online.getUniqueId().toString() : args[0];

        replyKey(sender, "reputation.asking", Map.of("player", args[0]));
        plugin.reputation().lookup(query)
                .thenAccept(answer -> plugin.mainThread().run(() -> show(sender, args[0], answer)));
    }

    private void show(CommandSender sender, String typed, JsonObject answer) {
        if (answer == null) {
            replyKey(sender, "reputation.failed");
            return;
        }
        if (!bool(answer, "found")) {
            JsonArray candidates = array(answer, "candidates");
            // size(), not isEmpty(): JsonArray grew isEmpty in Gson 2.8.7, and
            // the 1.16.5 jar compiles against the older Gson that Paper
            // bundled there. One source tree, two Gsons.
            if (candidates.size() == 0) {
                replyKey(sender, "reputation.unknown", Map.of("player", typed));
                return;
            }
            shortlist(sender, typed, candidates);
            return;
        }
        identity(sender, answer);
    }

    /** Several identities under one name. Printed rather than guessed
     *  between: which of them the operator means is not something the service
     *  can know, and picking the worst would be an accusation. */
    private void shortlist(CommandSender sender, String typed, JsonArray candidates) {
        sender.sendMessage(plugin.config().prefix() + plugin.lang().text("reputation.ambiguous",
                Map.of("player", typed, "count", Integer.toString(candidates.size()))));
        for (JsonElement element : candidates) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            String id = text(row, "id", "");
            sender.sendMessage(plugin.lang().text("reputation.candidate",
                    Map.of("player", text(row, "player", typed),
                            "id", id,
                            "short", text(row, "short", ""),
                            "kind", kind(row),
                            "score", bar(number(row, "score")),
                            "kicks", Integer.toString((int) number(row, "kicks")),
                            "bans", Integer.toString((int) number(row, "bans")),
                            "servers", Integer.toString((int) number(row, "servers")),
                            "seen", text(row, "last_seen", ""))));
        }
        sender.sendMessage(plugin.config().prefix()
                + plugin.lang().text("reputation.pick"));
    }

    private void identity(CommandSender sender, JsonObject answer) {
        sender.sendMessage(plugin.config().prefix() + plugin.lang().text("reputation.header",
                Map.of("player", text(answer, "player", "?"),
                        "id", text(answer, "id", ""),
                        "kind", kind(answer),
                        "score", bar(number(answer, "score")),
                        "addresses", Integer.toString(array(answer, "addresses").size()),
                        "servers", Integer.toString((int) number(answer, "servers")),
                        "joins", Integer.toString((int) number(answer, "joins")),
                        "kicks", Integer.toString((int) number(answer, "kicks")),
                        "alerts", Integer.toString((int) number(answer, "alerts")),
                        "bans", Integer.toString((int) number(answer, "bans")))));

        if (bool(answer, "weak")) {
            // Worth saying every time rather than once in the documentation:
            // somebody reading this row is about to decide something.
            sender.sendMessage(plugin.lang().text("reputation.weak"));
        }

        // The names this account has worn. The point of keying on the UUID:
        // a rename is a row in a history here rather than a new stranger.
        List<String> names = new ArrayList<>();
        for (JsonElement element : array(answer, "names")) {
            if (element.isJsonObject()) {
                names.add(text(element.getAsJsonObject(), "name", ""));
            }
        }
        if (names.size() > 1) {
            sender.sendMessage(plugin.lang().text("reputation.names",
                    Map.of("count", Integer.toString(names.size()),
                            "names", String.join("<dark_gray>, <gray>", names))));
        }

        for (JsonElement element : array(answer, "addresses")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            // This server's own observation carries the address itself - it is
            // in this server's connection log anyway. Rows from elsewhere
            // carry only the half the service keeps for display.
            String shown = row.has("address") && !row.get("address").getAsString().isBlank()
                    ? row.get("address").getAsString()
                    : text(row, "prefix", "?");
            sender.sendMessage(plugin.lang().text("reputation.address",
                    Map.of("address", shown,
                            "fingerprint", text(row, "fingerprint", ""),
                            "joins", Integer.toString((int) number(row, "joins")),
                            "seen", text(row, "last_seen", ""))));
        }

        JsonArray alts = array(answer, "alts");
        if (alts.size() > 0) {
            sender.sendMessage(plugin.lang().text("reputation.alts",
                    Map.of("count", Integer.toString(alts.size()))));
            for (JsonElement element : alts) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject alt = element.getAsJsonObject();
                sender.sendMessage(plugin.lang().text("reputation.alt",
                        Map.of("player", text(alt, "player", "?"),
                                "short", text(alt, "short", ""),
                                "kind", kind(alt),
                                "score", bar(number(alt, "score")),
                                "kicks", Integer.toString((int) number(alt, "kicks")),
                                "bans", Integer.toString((int) number(alt, "bans")))));
            }
        }
    }

    /** "premium" or "offline", in the reader's language: the difference
     *  between an account id and a name wearing one. */
    private String kind(JsonObject row) {
        String kind = text(row, "kind", "");
        return plugin.lang().textOr("reputation.kind." + kind, kind);
    }

    /** A score as something readable at a glance. The colour is the whole
     *  point of printing it this way: a number between nought and one means
     *  nothing to somebody mid-fight. */
    private String bar(double score) {
        double clamped = Math.max(0.0D, Math.min(1.0D, score));
        String colour = clamped >= 0.7D ? "<red>" : clamped >= 0.4D ? "<gold>" : "<green>";
        int filled = (int) Math.round(clamped * 5);
        return colour + "▌".repeat(Math.max(0, filled)) + "<dark_gray>"
                + "▌".repeat(Math.max(0, 5 - filled))
                + " " + colour + Msg.round(clamped, 2);
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).getAsBoolean();
        } catch (RuntimeException notABoolean) {
            return false;
        }
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static String text(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static double number(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsDouble() : 0.0D;
        } catch (RuntimeException notANumber) {
            return 0.0D;
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length <= 1 ? onlineNames(args.length == 0 ? "" : args[0]) : List.of();
    }
}
