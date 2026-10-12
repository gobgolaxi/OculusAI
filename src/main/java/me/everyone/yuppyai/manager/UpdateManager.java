package me.everyone.yuppyai.manager;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.util.Msg;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** Asks the service whether a newer build exists, and stages it if so.
 *
 * <p>Staged, never swapped. The jar is written into the server's update folder,
 * which the server applies on its next start - replacing a jar underneath a
 * running plugin is how classloaders end up half in one version and half in
 * another. So an update here is always "ready on restart", and the restart is
 * the operator's.
 *
 * <p>Three things gate a download, because this path writes executable code
 * into a server directory. The URL must be https and must point at a host this
 * class was built to trust. The digest the service named must match what
 * arrived, byte for byte, or the file is discarded. And the whole thing is off
 * if the operator says so, in which case the check still runs and still
 * reports, it just hands over a link instead of a file.
 */
public final class UpdateManager implements Manager {

    /** Where a build may come from. Not configurable on purpose: a setting
     *  that lets a config file nominate an arbitrary download host is a
     *  setting that turns a leaked panel into remote code execution. */
    private static final Set<String> ALLOWED_HOSTS =
            Set.of("github.com", "objects.githubusercontent.com");

    /** A jar is a few hundred kilobytes. Ten megabytes is far past anything
     *  legitimate and stops a bad answer from filling the disk. */
    private static final long MAX_BYTES = 10L * 1024L * 1024L;

    private static final long FIRST_CHECK_TICKS = 20L * 45L;

    private final YuppyAI plugin;
    private final AtomicReference<String> staged = new AtomicReference<>();
    private HttpClient client;
    private BukkitTask task;

    public UpdateManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        if (!plugin.config().updateEnabled()) {
            return;
        }
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        long period = Math.max(1L, plugin.config().updateCheckHours()) * 20L * 60L * 60L;
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::check, FIRST_CHECK_TICKS, period);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** The version waiting in the update folder, if a check found one. */
    public String stagedVersion() {
        return staged.get();
    }

    private void check() {
        String current = plugin.buildVersion();
        String flavour = plugin.buildFlavour();
        if (flavour.isBlank()) {
            return;
        }

        JsonObject answer = plugin.api().latestBuild(flavour).join();
        if (answer == null || !answer.has("version")) {
            return;
        }
        String newest = answer.get("version").getAsString().trim();
        if (newest.isEmpty() || newest.equals(current) || !newer(newest, current)) {
            return;
        }
        if (newest.equals(staged.get())) {
            return;
        }

        String url = answer.has("url") ? answer.get("url").getAsString() : "";
        String digest = answer.has("sha256") ? answer.get("sha256").getAsString() : "";

        if (!plugin.config().updateDownload() || url.isBlank() || digest.isBlank()) {
            announce(newest, false);
            return;
        }
        if (!trusted(url)) {
            plugin.getLogger().warning("Refusing the update at " + url
                    + ": only https downloads from " + String.join(", ", ALLOWED_HOSTS)
                    + " are accepted.");
            announce(newest, false);
            return;
        }
        if (download(url, digest)) {
            staged.set(newest);
            announce(newest, true);
        }
    }

    static boolean trusted(String url) {
        if (!url.startsWith("https://")) {
            return false;
        }
        try {
            String host = URI.create(url).getHost();
            return host != null && ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Dotted-number comparison, newest wins; anything unparseable is treated
     *  as not newer, so a malformed answer can never trigger a download. */
    static boolean newer(String candidate, String current) {
        int[] left = parse(candidate);
        int[] right = parse(current);
        if (left == null || right == null) {
            return false;
        }
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static int[] parse(String version) {
        String core = version.split("-", 2)[0].trim();
        if (core.isEmpty()) {
            return null;
        }
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return out;
    }

    private boolean download(String url, String expected) {
        Path jar = plugin.pluginJar();
        if (jar == null) {
            return false;
        }
        Path folder = plugin.getServer().getUpdateFolderFile().toPath();
        Path temporary = null;
        try {
            Files.createDirectories(folder);
            temporary = Files.createTempFile(folder, "yuppyai-", ".part");

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                plugin.getLogger().warning("Update download answered "
                        + response.statusCode() + "; keeping the current build.");
                return false;
            }

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long total = 0L;
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(temporary)) {
                byte[] chunk = new byte[16 * 1024];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    total += read;
                    if (total > MAX_BYTES) {
                        plugin.getLogger().warning(
                                "Update download is over " + (MAX_BYTES / 1024 / 1024)
                                + " MB; refusing it.");
                        return false;
                    }
                    sha.update(chunk, 0, read);
                    out.write(chunk, 0, read);
                }
            }

            String actual = hex(sha.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                plugin.getLogger().warning("Update digest does not match what the service named"
                        + " (expected " + expected + ", got " + actual + "); discarding it.");
                return false;
            }

            // The server applies an update only when the staged file carries the
            // same name as the installed jar.
            Path destination = folder.resolve(jar.getFileName().toString());
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            return true;
        } catch (IOException | InterruptedException | NoSuchAlgorithmException error) {
            plugin.getLogger().warning("Could not stage the update: " + error.getMessage());
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A leftover .part is noise, not a failure worth reporting.
                }
            }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    private void announce(String version, boolean stagedNow) {
        String line = stagedNow
                ? "YuppyAI " + version + " is downloaded and will be applied on the next restart."
                : "YuppyAI " + version + " is out; this server runs " + plugin.buildVersion() + ".";
        plugin.getLogger().info(line);

        String site = plugin.config().site();
        TextComponent message = new TextComponent(TextComponent.fromLegacyText(
                Msg.parse(plugin.config().prefix()) + Msg.parse("<gray>" + line)));
        if (!stagedNow && !site.isBlank()) {
            TextComponent link = new TextComponent(TextComponent.fromLegacyText(
                    Msg.parse(" <yellow>[Скачать]")));
            link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, site));
            link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text(TextComponent.fromLegacyText(site))));
            message.addExtra(link);
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (online.hasPermission("yuppyai.alerts")) {
                    online.spigot().sendMessage(message);
                }
            }
        });
    }

    /** For the diagnostics command. */
    public List<String> describe() {
        String waiting = staged.get();
        return List.of(
                "version " + plugin.buildVersion() + " (" + plugin.buildFlavour() + ")",
                "checks " + (plugin.config().updateEnabled()
                        ? "every " + plugin.config().updateCheckHours() + "h" : "off"),
                "downloads " + (plugin.config().updateDownload() ? "on" : "off"),
                waiting == null ? "nothing staged" : waiting + " staged for the next restart");
    }
}
