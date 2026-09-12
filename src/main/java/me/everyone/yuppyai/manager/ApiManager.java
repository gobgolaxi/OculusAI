package me.everyone.yuppyai.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.everyone.yuppyai.YuppyAI;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.scheduler.BukkitTask;

public final class ApiManager implements Manager {

    private final AtomicBoolean reachable = new AtomicBoolean(true);
    private final AtomicLong throttledLoggedAt = new AtomicLong(0L);
    private static final long THROTTLE_LOG_INTERVAL_MS = 60_000L;
    private static final long PROBE_PERIOD_TICKS = 20L * 30L;

    private final YuppyAI plugin;
    private final Executor executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "YuppyAI-API");
        thread.setDaemon(true);
        return thread;
    });
    private HttpClient client;
    private BukkitTask probeTask;

    public ApiManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(plugin.config().apiTimeoutMs()))
                .executor(executor)
                .build();
        probeTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::probe, PROBE_PERIOD_TICKS, PROBE_PERIOD_TICKS);
    }

    @Override
    public void disable() {
        if (probeTask != null) {
            probeTask.cancel();
            probeTask = null;
        }
    }

    public CompletableFuture<Double> predict(Map<String, Double> features) {
        JsonObject body = new JsonObject();
        JsonObject values = new JsonObject();
        features.forEach(values::addProperty);
        body.add("features", values);

        return post("/predict", body).thenApply(response -> {
            if (response == null || !response.has("probability")) {
                return Double.NaN;
            }
            return response.get("probability").getAsDouble();
        });
    }

    public CompletableFuture<JsonObject> addSamples(String label, String player, String uuid,
                                                    List<String> featureNames, List<double[]> rows,
                                                    boolean enabled, String source, String person,
                                                    List<double[]> context) {
        JsonObject body = new JsonObject();
        body.addProperty("label", label);
        body.addProperty("player", player);
        body.addProperty("uuid", uuid);
        body.addProperty("enabled", enabled);
        body.addProperty("source", source);
        body.addProperty("person", person == null ? "" : person);

        if (context != null && context.size() == rows.size() && anyScored(context)) {
            body.add("scores", column(context, 0));
            body.add("buffers", column(context, 1));
        }

        JsonArray names = new JsonArray();
        featureNames.forEach(names::add);
        body.add("feature_names", names);

        JsonArray samples = new JsonArray();
        for (double[] row : rows) {
            JsonArray sample = new JsonArray();
            for (double value : row) {
                sample.add(value);
            }
            samples.add(sample);
        }
        body.add("samples", samples);

        return postDataset("/samples", body);
    }

    static JsonArray column(List<double[]> context, int index) {
        JsonArray array = new JsonArray();
        for (double[] pair : context) {
            if (Double.isNaN(pair[index])) {
                array.add((Number) null);
            } else {
                array.add(pair[index]);
            }
        }
        return array;
    }

    static boolean anyScored(List<double[]> context) {
        for (double[] pair : context) {
            if (!Double.isNaN(pair[0])) {
                return true;
            }
        }
        return false;
    }

    public CompletableFuture<JsonObject> removeSamples(String label, String uuid) {
        JsonObject body = new JsonObject();
        body.addProperty("label", label);
        body.addProperty("uuid", uuid);
        return postDataset("/samples/remove", body);
    }

    public CompletableFuture<JsonObject> sessions() {
        return get("/sessions");
    }

    public CompletableFuture<JsonObject> toggleSession(String id) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        return post("/sessions/toggle", body);
    }

    public CompletableFuture<JsonObject> train() {
        return postDataset("/train", new JsonObject());
    }

    public CompletableFuture<JsonObject> removeSession(String id) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        return post("/sessions/remove", body);
    }

    public CompletableFuture<JsonObject> status() {
        return get("/status");
    }

    public CompletableFuture<JsonObject> moderateChat(String player, String uuid, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("player", player);
        body.addProperty("uuid", uuid);
        body.addProperty("message", message);
        return post("/moderate/chat", body);
    }

    private CompletableFuture<JsonObject> post(String path, JsonObject body) {
        HttpRequest.Builder builder = builder(path, plugin.config().apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return send(builder.build());
    }

    private CompletableFuture<JsonObject> postDataset(String path, JsonObject body) {
        HttpRequest.Builder builder = builder(path, plugin.config().datasetKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return send(builder.build());
    }

    private CompletableFuture<JsonObject> get(String path) {
        return send(builder(path, plugin.config().apiKey()).GET().build());
    }

    private void probe() {
        if (reachable.get()) {
            return;
        }
        health().thenAccept(response -> {
            if (response != null) {
                markReachable(true);
            }
        });
    }

    private CompletableFuture<JsonObject> health() {
        return get("/health");
    }

    private HttpRequest.Builder builder(String path, String key) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(plugin.config().apiUrl() + path))
                .timeout(Duration.ofMillis(plugin.config().apiTimeoutMs()));
        if (key != null && !key.isBlank()) {
            builder.header("X-YuppyAI-Key", key);
        }
        return builder;
    }

    private CompletableFuture<JsonObject> send(HttpRequest request) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    markReachable(true);
                    if (response.statusCode() == 429) {
                        noteThrottled(response.body());
                        return null;
                    }
                    if (response.statusCode() / 100 != 2) {
                        plugin.getLogger().warning("Service answered " + response.statusCode()
                                + " for " + request.uri().getPath() + ": "
                                + abbreviate(response.body()));
                        return null;
                    }
                    return new JsonParser().parse(response.body()).getAsJsonObject();
                })
                .exceptionally(throwable -> {
                    markReachable(false);
                    return null;
                });
    }

    private void noteThrottled(String body) {
        long now = System.currentTimeMillis();
        long last = throttledLoggedAt.get();
        if (now - last < THROTTLE_LOG_INTERVAL_MS || !throttledLoggedAt.compareAndSet(last, now)) {
            return;
        }
        plugin.getLogger().warning("Service is rate limiting this key: " + abbreviate(body)
                + " (checks are skipped while over the limit)");
    }

    private void markReachable(boolean value) {
        if (reachable.getAndSet(value) == value) {
            return;
        }
        if (value) {
            plugin.getLogger().info("Service at " + plugin.config().apiUrl() + " is answering again");
        } else {
            plugin.getLogger().warning("Service at " + plugin.config().apiUrl()
                    + " is not answering; analysis is paused");
        }
    }

    private String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 197) + "...";
    }

    public boolean reachable() {
        return reachable.get();
    }
}
