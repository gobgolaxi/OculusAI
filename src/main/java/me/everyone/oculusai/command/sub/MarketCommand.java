package me.everyone.oculusai.command.sub;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

/** The settings marketplace, from the server side.
 *
 * <p>Tuning this anticheat well is work, and every operator who gets it right
 * does so by fighting the same fight alone. A preset is somebody else's
 * finished fight: a named set of thresholds that can be looked at, applied and
 * undone.
 *
 * <p>What travels is never the configuration file. The file holds the API key
 * and the dataset key - the credentials that bind this server to a paying
 * account - and publishing it would publish those under the publisher's own
 * name. Two things stop that. The service keeps a whitelist and stores nothing
 * outside it, which is the authority; and this class refuses to put the secret
 * sections on the wire at all, which means they are not in a request body, a
 * proxy log or a crash report on the way there either. */
public final class MarketCommand extends SubCommand {

    /** Config sections that never leave this server. Everything the service
     *  accepts is tuning, and none of it lives under these. */
    private static final List<String> SECRET = List.of("api", "discord", "dataset");

    public MarketCommand(OculusAI plugin) {
        super(plugin, "market", List.of("presets", "shop"), "oculusai.command",
                "/oai market [list|info|apply|publish]",
                "Browse and apply settings other operators published.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "info" -> info(sender, args);
            case "apply", "install" -> apply(sender, args);
            case "publish", "share" -> publish(sender, args);
            default -> list(sender, args);
        }
    }

    private void list(CommandSender sender, String[] args) {
        String query = args.length > 2 ? String.join(" ", slice(args, 2)) : "";
        replyKey(sender, "market.searching");
        plugin.api().marketList(query).thenAccept(answer -> plugin.mainThread().run(() -> {
            if (answer == null || !answer.has("presets")) {
                replyKey(sender, "market.failed");
                return;
            }
            List<JsonElement> presets = new ArrayList<>();
            answer.getAsJsonArray("presets").forEach(presets::add);
            if (presets.isEmpty()) {
                replyKey(sender, "market.empty");
                return;
            }
            for (JsonElement element : presets) {
                JsonObject preset = element.getAsJsonObject();
                reply(sender, "<dark_gray>- <white>" + text(preset, "name")
                        + " <dark_gray>(" + text(preset, "id") + ") "
                        + "<gray>" + text(preset, "author")
                        + " <dark_gray>· <gray>" + number(preset, "installs") + "↓");
            }
            replyKey(sender, "market.apply-hint");
        }));
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 3) {
            replyKey(sender, "market.usage-info");
            return;
        }
        plugin.api().marketPreset(args[2]).thenAccept(preset -> plugin.mainThread().run(() -> {
            if (preset == null || !preset.has("values")) {
                replyKey(sender, "market.not-found");
                return;
            }
            reply(sender, "<white>" + text(preset, "name") + " <gray>"
                    + text(preset, "author"));
            if (!text(preset, "blurb").isBlank()) {
                reply(sender, "<gray>" + text(preset, "blurb"));
            }
            JsonObject values = preset.getAsJsonObject("values");
            // entrySet, not keySet: the Gson that ships with 1.16.5 predates
            // keySet, and this class is compiled into both jars.
            for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                reply(sender, "<dark_gray>  " + entry.getKey() + " <white>"
                        + entry.getValue().getAsString());
            }
        }));
    }

    private void apply(CommandSender sender, String[] args) {
        if (args.length < 3) {
            replyKey(sender, "market.usage-apply");
            return;
        }
        plugin.api().marketInstall(args[2]).thenAccept(preset -> plugin.mainThread().run(() -> {
            if (preset == null || !preset.has("values")) {
                replyKey(sender, "market.not-found");
                return;
            }
            JsonObject values = preset.getAsJsonObject("values");
            int written = 0;
            for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                // The service only ever sends whitelisted tuning, but this is
                // the side that writes to disk: a path that is not already in
                // this server's config is not one the preset gets to invent.
                if (!plugin.getConfig().contains(entry.getKey())) {
                    continue;
                }
                plugin.getConfig().set(entry.getKey(), parse(entry.getValue()));
                written++;
            }
            plugin.saveConfig();
            plugin.reloadEverything();
            replyKey(sender, "market.applied", Map.of(
                    "name", text(preset, "name"),
                    "value", Integer.toString(written)));
        }));
    }

    private void publish(CommandSender sender, String[] args) {
        if (args.length < 3) {
            replyKey(sender, "market.usage-publish");
            return;
        }
        String name = String.join(" ", slice(args, 2));
        JsonObject values = shareable();
        replyKey(sender, "market.publishing");
        plugin.api().marketPublish(name, "", values)
                .thenAccept(answer -> plugin.mainThread().run(() -> {
                    if (answer == null) {
                        replyKey(sender, "market.failed");
                        return;
                    }
                    if (answer.has("note") && !answer.has("preset")) {
                        reply(sender, "<red>" + answer.get("note").getAsString());
                        return;
                    }
                    JsonObject preset = answer.getAsJsonObject("preset");
                    replyKey(sender, "market.published", Map.of(
                            "name", text(preset, "name"),
                            "value", text(preset, "id")));
                }));
    }

    /** This server's settings, flattened, minus everything secret. */
    private JsonObject shareable() {
        JsonObject out = new JsonObject();
        collect(plugin.getConfig(), "", out);
        return out;
    }

    private void collect(ConfigurationSection section, String prefix, JsonObject out) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (prefix.isEmpty() && SECRET.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) {
                collect(nested, path, out);
            } else if (value instanceof Boolean flag) {
                out.addProperty(path, flag);
            } else if (value instanceof Number number) {
                out.addProperty(path, number);
            } else if (value instanceof String text) {
                out.addProperty(path, text);
            }
        }
    }

    private static Object parse(JsonElement element) {
        if (!element.isJsonPrimitive()) {
            return element.toString();
        }
        var primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            double number = primitive.getAsDouble();
            // Whole numbers go back as integers: a window of 20.0 ticks in a
            // config file reads as a mistake even though it works.
            return number == Math.rint(number) && Math.abs(number) < Integer.MAX_VALUE
                    ? (Object) (int) number : (Object) number;
        }
        return primitive.getAsString();
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }

    private static String number(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "0";
    }

    private static String[] slice(String[] args, int from) {
        String[] rest = new String[Math.max(0, args.length - from)];
        System.arraycopy(args, from, rest, 0, rest.length);
        return rest;
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        List<String> options = new ArrayList<>(List.of("list", "info", "apply", "publish"));
        options.removeIf(option -> !option.startsWith(args[1].toLowerCase(Locale.ROOT)));
        return options;
    }
}
