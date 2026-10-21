package me.everyone.oculusai.update;


import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.core.TickLoop;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.util.Msg;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Asks the service whether a newer build exists, and stages it if so.
 *
 * <p>Staged, never swapped. The jar is written into the server's update folder,
 * which the server applies on its next start - replacing a jar underneath a
 * running plugin is how classloaders end up half in one version and half in
 * another. So an update here is always "ready on restart", and the restart is
 * the operator's.
 *
 * <p>Finding a new build is useless if nobody reads the line that says so.
 * The service is asked on a slow timer - a build is published a few times a
 * month, not a few times an hour - but the answer is repeated to admins in
 * chat on a fast one, so somebody who logs in long after the check still gets
 * told. The repeat stops the moment the server runs the newest build.
 *
 * <p>Three things gate a download, because this path writes executable code
 * into a server directory. The URL must be https and must point at a host this
 * class was built to trust. The digest the service named must match what
 * arrived, byte for byte, or the file is discarded. And the whole thing is off
 * if the operator says so, in which case the check still runs and still
 * reports, it just hands over a link instead of a file.
 */
public final class Updater implements Service {

    /** Where a build may come from. Not configurable on purpose: a setting
     *  that lets a config file nominate an arbitrary download host is a
     *  setting that turns a leaked panel into remote code execution. */
    private static final Set<String> ALLOWED_HOSTS =
            Set.of("github.com", "objects.githubusercontent.com");

    /** A jar is a few hundred kilobytes. Ten megabytes is far past anything
     *  legitimate and stops a bad answer from filling the disk. */
    private static final long MAX_BYTES = 10L * 1024L * 1024L;

    private static final long FIRST_CHECK_TICKS = 20L * 45L;

    private final OculusAI plugin;
    private final AtomicReference<String> staged = new AtomicReference<>();
    /** The newest version the service named, once it turned out to be newer
     *  than this jar. Held so the reminder can repeat it without asking the
     *  service again, and cleared when this jar becomes that version. */
    private final AtomicReference<String> pending = new AtomicReference<>();
    /** The release notes that came with it, first line only. */
    private final AtomicReference<String> notes = new AtomicReference<>("");
    /** The main-thread timer that repeats the line in chat. Runs only while
     *  something is pending: an up-to-date server schedules nothing. Volatile
     *  because it is started from the thread the service answers on and read
     *  from the main thread the timer itself runs on. */
    private volatile TickLoop.Handle reminder;
    private HttpClient client;
    private ScheduledFuture<?> task;
    /** Downloads are minutes of blocking I/O. They must not occupy the shared
     *  worker: that worker also runs detection, history flushes and the
     *  service calls, and one 10 MB jar used to stall all of them. */
    private ExecutorService downloads;

    public Updater(OculusAI plugin) {
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
        downloads = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "OculusAI-update-download");
            thread.setDaemon(true);
            return thread;
        });
        long period = Math.max(1L, plugin.config().updateCheckHours()) * 20L * 60L * 60L;
        task = plugin.worker().repeat(this::check, FIRST_CHECK_TICKS * 50L, period * 50L,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void reload() {
        // The check interval, the download switch and the reminder interval
        // all come from config.yml, so a reload has to rebuild the timers
        // rather than keep running on the old numbers. What was already found
        // is kept: the new build has not gone anywhere.
        String found = pending.get();
        String waiting = staged.get();
        disable();
        pending.set(found);
        staged.set(waiting);
        enable();
        if (found != null) {
            startReminder();
        }
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        stopReminder();
        pending.set(null);
        notes.set("");
        if (downloads != null) {
            downloads.shutdownNow();
            downloads = null;
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

        plugin.api().latestBuild(flavour).thenAccept(answer -> consider(answer, current));
    }

    private void consider(JsonObject answer, String current) {
        if (answer == null || !answer.has("version")) {
            return;
        }
        String newest = answer.get("version").getAsString().trim();
        if (newest.isEmpty() || newest.equals(current) || !newer(newest, current)) {
            return;
        }
        if (newest.equals(staged.get())) {
            // Already downloaded and waiting for a restart. The reminder keeps
            // saying so, so there is nothing new to announce.
            startReminder();
            return;
        }

        notes.set(firstLine(answer.has("notes") ? answer.get("notes").getAsString() : ""));
        boolean first = !newest.equals(pending.getAndSet(newest));
        if (first) {
            announce(newest, false);
        }
        startReminder();

        String url = answer.has("url") ? answer.get("url").getAsString() : "";
        String digest = answer.has("sha256") ? answer.get("sha256").getAsString() : "";

        if (!plugin.config().updateDownload() || url.isBlank() || digest.isBlank()) {
            return;
        }
        if (!trusted(url)) {
            plugin.getLogger().warning("Refusing the update at " + url
                    + ": only https downloads from " + String.join(", ", ALLOWED_HOSTS)
                    + " are accepted.");
            return;
        }
        ExecutorService executor = downloads;
        if (executor == null) {
            return;
        }
        try {
            executor.execute(() -> {
                if (download(url, digest)) {
                    staged.set(newest);
                    announce(newest, true);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopping) {
            // The plugin is disabling; leaving the old build alone is correct.
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
            temporary = Files.createTempFile(folder, "oculusai-", ".part");

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
            // The original URL was checked before the request. Redirects may
            // have taken us somewhere else, so check where the bytes actually
            // came from as well.
            if (response.uri() == null || !trusted(response.uri().toString())) {
                plugin.getLogger().warning("Update download redirected to an untrusted "
                        + "location; refusing the file.");
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

    // ----------------------------------------------------------------- telling
    //  people about it

    /** Turns the reminder on. Idempotent: a check that finds the same build
     *  again must not stack a second timer onto the first. */
    private void startReminder() {
        if (reminder != null) {
            return;
        }
        long seconds = plugin.config().updateRemindSeconds();
        if (seconds <= 0L) {
            // Nought means "say it once". The announcement has already gone
            // out by the time this is reached.
            return;
        }
        reminder = plugin.ticks().every(seconds * 20L, this::remind);
    }

    private void stopReminder() {
        if (reminder != null) {
            reminder.cancel();
            reminder = null;
        }
    }

    /** The repeat, on the main thread. Chat only - the console heard it once
     *  from {@link #announce} and does not need it every quarter minute. */
    private void remind() {
        String version = pending.get();
        if (version == null) {
            stopReminder();
            return;
        }
        // The jar may have been replaced by a restart since the last check:
        // nothing to nag about then, and the line would be wrong.
        if (!newer(version, plugin.buildVersion())) {
            pending.set(null);
            staged.set(null);
            stopReminder();
            return;
        }
        TextComponent message = line(version, version.equals(staged.get()));
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission("oculusai.alerts")) {
                online.spigot().sendMessage(message);
            }
        }
    }

    /** The one line everybody gets, built from the language file so the
     *  wording lives beside every other message rather than in this class. */
    private TextComponent line(String version, boolean stagedNow) {
        Map<String, String> values = Map.of(
                "version", version,
                "current", plugin.buildVersion());
        String body = plugin.lang().text(stagedNow ? "update.staged" : "update.available",
                values);

        TextComponent message = new TextComponent(TextComponent.fromLegacyText(
                Msg.parse(plugin.config().prefix()) + body));

        String note = notes.get();
        if (note != null && !note.isBlank()) {
            message.addExtra(new TextComponent(TextComponent.fromLegacyText(
                    plugin.lang().text("update.notes", Map.of("value", note)))));
        }

        // A staged build needs no link: it is already on disk, and clicking
        // through to download it by hand is how an operator ends up with two.
        String site = plugin.config().site();
        if (!stagedNow && !site.isBlank()) {
            TextComponent link = new TextComponent(TextComponent.fromLegacyText(
                    plugin.lang().text("update.download")));
            link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, site));
            link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text(TextComponent.fromLegacyText(
                            plugin.lang().text("update.hover")))));
            message.addExtra(link);
        }
        return message;
    }

    /** The first announcement: the console log, and chat for whoever is on. */
    private void announce(String version, boolean stagedNow) {
        plugin.getLogger().info(stagedNow
                ? "OculusAI " + version + " is downloaded and will be applied on the next restart."
                : "OculusAI " + version + " is out; this server runs " + plugin.buildVersion() + ".");

        plugin.mainThread().run(() -> {
            TextComponent message = line(version, stagedNow);
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (online.hasPermission("oculusai.alerts")) {
                    online.spigot().sendMessage(message);
                }
            }
        });
    }

    /** Notes are free text written by whoever published the build. One line is
     *  what fits in chat, and a stray newline would otherwise split the
     *  message into two the client renders separately. */
    static String firstLine(String value) {
        if (value == null) {
            return "";
        }
        String line = value.strip();
        int newline = line.indexOf('\n');
        if (newline >= 0) {
            line = line.substring(0, newline).strip();
        }
        return line.length() > 120 ? line.substring(0, 117) + "..." : line;
    }

    /** The version a check has found, or null while this jar is the newest
     *  thing the service knows about. */
    public String pendingVersion() {
        return pending.get();
    }

    /** Asks the service now and answers the sender, for /oai update. Runs the
     *  call off the main thread, like the timer does, and replies on it. */
    public void checkNow(CommandSender sender) {
        String flavour = plugin.buildFlavour();
        if (flavour.isBlank()) {
            // A jar built outside Gradle has no flavour stamped into it, so
            // there is no way to know which of the two builds to compare with.
            sender.sendMessage(plugin.config().prefix()
                    + plugin.lang().text("update.unknown-build"));
            return;
        }
        sender.sendMessage(plugin.config().prefix() + plugin.lang().text("update.checking"));
        String current = plugin.buildVersion();
        plugin.api().latestBuild(flavour).thenAccept(answer -> {
            consider(answer, current);
            plugin.mainThread().run(() -> {
                String found = pending.get();
                if (found == null) {
                    sender.sendMessage(plugin.config().prefix()
                            + plugin.lang().text("update.current",
                                    Map.of("version", current)));
                } else if (sender instanceof Player player) {
                    player.spigot().sendMessage(line(found, found.equals(staged.get())));
                } else {
                    sender.sendMessage(line(found, found.equals(staged.get())).toLegacyText());
                }
            });
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
                "reminder " + (plugin.config().updateRemindSeconds() > 0
                        ? "every " + plugin.config().updateRemindSeconds() + "s while out of date"
                        : "once per check"),
                pending.get() == null ? "up to date" : pending.get() + " available",
                waiting == null ? "nothing staged" : waiting + " staged for the next restart");
    }
}
