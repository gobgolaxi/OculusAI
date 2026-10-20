package me.everyone.oculusai.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import me.everyone.oculusai.OculusAI;
import org.bukkit.scheduler.BukkitTask;

/**
 * Captures that did not reach the service, kept until they do.
 *
 * A recording is work somebody did with their hands: half an hour of a player
 * fighting under a label. Before this, a failed upload meant that half hour
 * was gone - the request answered 403 or the service was down, {@code commit}
 * got a null, and the rows lived on only until the next restart. Now the batch
 * lands here instead and is offered again every {@link #RETRY_SECONDS}
 * seconds.
 *
 * The queue lives on disk as well as in memory. A capture that was made by
 * hand and has not reached the service must not die because the server was
 * restarted in the middle of an outage - that is exactly the moment an outage
 * is most likely. Each held capture is written next to the plugin's data as a
 * JSON file, and the queue is rebuilt from those files when the plugin starts.
 *
 * What it deliberately does not do:
 *
 * <ul>
 *   <li><b>Guard against a double send.</b> A batch the service stored but
 *       whose answer never came back will be sent again, and the service has
 *       no way to tell the second copy from new data. Losing a capture was
 *       judged worse than storing one twice.</li>
 * </ul>
 */
public final class UploadQueue implements Manager {

    /** Between attempts. Long enough not to hammer a service that is down,
     *  short enough that a blip costs a few seconds rather than a capture. */
    public static final int RETRY_SECONDS = 5;

    /** The queue is held in memory, so it cannot be allowed to grow without
     *  limit: a service that is down for an hour during a long recording
     *  session would otherwise push the server into the garbage collector. */
    private static final int MAX_BATCHES = 64;

    /** What a single failed send was, in full, so the retry is the same
     *  request rather than a reconstruction of it. */
    public record Batch(String label, String player, String uuid,
                        List<String> featureNames, List<double[]> rows,
                        boolean enabled, String source, String person,
                        List<double[]> context, int featureVersion) {
    }

    /** A batch on its way out and the file that carries it. */
    private record Held(Batch batch, Path file) {
    }

    private final OculusAI plugin;
    private final Deque<Held> pending = new ArrayDeque<>();
    /** One flight at a time: the task fires every five seconds whether or not
     *  the previous attempt has answered, and two attempts at the same batch
     *  would send it twice. */
    private final AtomicBoolean sending = new AtomicBoolean();
    private BukkitTask task;

    public UploadQueue(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        restore();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::flush,
                RETRY_SECONDS * 20L, RETRY_SECONDS * 20L);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        int lost = size();
        if (lost > 0) {
            plugin.getLogger().warning("Shutting down with " + lost
                    + " capture(s) still unsent; they are kept on disk and will be sent"
                    + " after the restart");
        }
    }

    /** Where the held captures are kept. */
    private Path folder() {
        Path folder = plugin.getDataFolder().toPath().resolve("unsent");
        try {
            Files.createDirectories(folder);
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not create the folder for unsent captures", failure);
        }
        return folder;
    }

    /** Takes a failed send. The batch is retried until the service accepts it. */
    public void offer(Batch batch) {
        if (batch.rows().isEmpty()) {
            return;
        }
        int waiting;
        boolean dropped = false;
        Path file = null;
        synchronized (pending) {
            // The oldest goes, not the newest: a capture made twenty minutes
            // ago is the one most likely to be stale, and dropping what just
            // happened would punish the person still recording.
            if (pending.size() >= MAX_BATCHES) {
                Held oldest = pending.pollFirst();
                dropped = true;
                if (oldest != null) {
                    delete(oldest.file());
                }
            }
            file = write(batch);
            pending.addLast(new Held(batch, file));
            waiting = pending.size();
        }
        if (dropped) {
            plugin.getLogger().severe("Unsent captures hit the limit of " + MAX_BATCHES
                    + "; the oldest was dropped. The service has been unreachable"
                    + " for a long time - check /oai diag");
        }
        plugin.getLogger().warning("Capture for " + batch.player() + " (" + batch.rows().size()
                + " windows) was not accepted; retrying every " + RETRY_SECONDS
                + "s, " + waiting + " waiting");
    }

    /** How many captures are still waiting. Shown by /oai diag. */
    public int size() {
        synchronized (pending) {
            return pending.size();
        }
    }

    /** How many windows are waiting across every held capture. */
    public int windows() {
        synchronized (pending) {
            return pending.stream().mapToInt(held -> held.batch().rows().size()).sum();
        }
    }

    /** Offers the oldest batch again. Called on the timer, and by /oai diag. */
    public void flush() {
        if (!sending.compareAndSet(false, true)) {
            return;
        }
        Held held;
        synchronized (pending) {
            held = pending.peekFirst();
        }
        if (held == null) {
            sending.set(false);
            return;
        }
        Batch batch = held.batch();

        plugin.api().addSamples(batch.label(), batch.player(), batch.uuid(),
                        batch.featureNames(), batch.rows(), batch.enabled(),
                        batch.source(), batch.person(), batch.context(),
                        batch.featureVersion())
                .whenComplete((response, failure) -> {
                    try {
                        if (response == null) {
                            return;
                        }
                        int left;
                        synchronized (pending) {
                            // Removed by identity, not by position: nothing else
                            // takes from the head, but a batch offered while this
                            // one was in flight must not be dropped by mistake.
                            pending.remove(held);
                            left = pending.size();
                        }
                        delete(held.file());
                        plugin.getLogger().info("Held capture for " + batch.player()
                                + " (" + batch.rows().size() + " windows) went through"
                                + (left == 0 ? "" : "; " + left + " still waiting"));
                    } catch (RuntimeException error) {
                        plugin.getLogger().log(Level.WARNING,
                                "Error finishing a held capture", error);
                    } finally {
                        sending.set(false);
                    }
                });
    }

    /** Writes a held capture down, so a restart does not lose it. */
    private Path write(Batch batch) {
        Path folder = folder();
        String stamp = java.time.LocalDateTime.now().toString()
                .replace(':', '-').replace('.', '-');
        Path file = folder.resolve(stamp + "-" + safe(batch.player()) + ".json");
        int counter = 1;
        while (Files.exists(file)) {
            counter++;
            file = folder.resolve(stamp + "-" + safe(batch.player()) + "-" + counter + ".json");
        }
        JsonObject json = new JsonObject();
        json.addProperty("label", batch.label());
        json.addProperty("player", batch.player());
        json.addProperty("uuid", batch.uuid());
        json.addProperty("enabled", batch.enabled());
        json.addProperty("source", batch.source());
        json.addProperty("person", batch.person());
        json.addProperty("feature_version", batch.featureVersion());
        JsonArray names = new JsonArray();
        batch.featureNames().forEach(names::add);
        json.add("feature_names", names);
        json.add("samples", numbers(batch.rows()));
        if (batch.context() != null && batch.context().size() == batch.rows().size()) {
            json.add("context", numbers(batch.context()));
        }
        try {
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not save an unsent capture to disk", failure);
        }
        return file;
    }

    /** Puts back everything that was on disk when the server stopped. */
    private void restore() {
        Path folder = folder();
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.json")) {
            for (Path path : stream) {
                files.add(path);
            }
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not read the folder of unsent captures", failure);
            return;
        }
        files.sort(Path::compareTo);
        int restored = 0;
        for (Path path : files) {
            Batch batch = read(path);
            if (batch == null) {
                continue;
            }
            synchronized (pending) {
                pending.addLast(new Held(batch, path));
            }
            restored++;
        }
        if (restored > 0) {
            plugin.getLogger().info("Restored " + restored
                    + " unsent capture(s) from disk; they will be sent again");
        }
    }

    private Batch read(Path path) {
        try {
            JsonObject json = new JsonParser()
                    .parse(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            List<String> names = new ArrayList<>();
            for (var element : json.getAsJsonArray("feature_names")) {
                names.add(element.getAsString());
            }
            List<double[]> rows = doubles(json.getAsJsonArray("samples"));
            List<double[]> context = json.has("context")
                    ? doubles(json.getAsJsonArray("context")) : List.of();
            return new Batch(json.get("label").getAsString(),
                    json.get("player").getAsString(),
                    json.has("uuid") ? json.get("uuid").getAsString() : "",
                    names, rows,
                    !json.has("enabled") || json.get("enabled").getAsBoolean(),
                    json.has("source") ? json.get("source").getAsString() : "manual",
                    json.has("person") ? json.get("person").getAsString() : "",
                    context,
                    json.has("feature_version") ? json.get("feature_version").getAsInt() : 5);
        } catch (Exception failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not read an unsent capture: " + path.getFileName(), failure);
            return null;
        }
    }

    private static List<double[]> doubles(JsonArray array) {
        List<double[]> rows = new ArrayList<>(array.size());
        for (var element : array) {
            JsonArray values = element.getAsJsonArray();
            double[] row = new double[values.size()];
            for (int i = 0; i < row.length; i++) {
                row[i] = values.get(i).isJsonNull() ? Double.NaN : values.get(i).getAsDouble();
            }
            rows.add(row);
        }
        return rows;
    }

    private static JsonArray numbers(List<double[]> rows) {
        JsonArray array = new JsonArray();
        for (double[] row : rows) {
            JsonArray values = new JsonArray();
            for (double value : row) {
                if (Double.isNaN(value)) {
                    values.add((Number) null);
                } else {
                    values.add(value);
                }
            }
            array.add(values);
        }
        return array;
    }

    private static String safe(String value) {
        return (value == null ? "unknown" : value).replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private void delete(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not delete a sent capture: " + file.getFileName(), failure);
        }
    }
}
