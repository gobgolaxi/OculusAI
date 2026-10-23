package me.everyone.oculusai.network;


import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.core.TickLoop;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class ApiClient implements Service {

    private final AtomicBoolean reachable = new AtomicBoolean(true);
    private final AtomicLong throttledLoggedAt = new AtomicLong(0L);
    private final AtomicLong probes = new AtomicLong(0L);
    private static final long THROTTLE_LOG_INTERVAL_MS = 60_000L;
    // Probe often, pull settings rarely. Recovery is what the short period is
    // for: while the service is down nothing is analysed, so thirty seconds of
    // it after every blip is thirty seconds of unchecked combat.
    private static final long PROBE_PERIOD_TICKS = 20L * 5L;
    /** Counting who is being analysed is a walk over the tracked players, so
     *  it is done every few seconds rather than every tick. The poll that
     *  sends it is minutes apart anyway. */
    private static final long PRESENCE_SAMPLE_TICKS = 20L * 5L;
    private static final int SETTINGS_EVERY_PROBES = 6;
    // Training is minutes of work behind one request. The ordinary timeout is
    // a second and a half, so sending /train on it guarantees a timeout, and a
    // timeout is what marks the service unreachable - which is why analysis
    // used to pause for every training run the plugin started itself. The run
    // is fire-and-forget from here: the service keeps going whatever this
    // client does, so the call gets room to answer and is not allowed to vote
    // on whether the service is up.
    private static final long TRAIN_TIMEOUT_MS = 10L * 60L * 1000L;

    /** How long a dataset push may take. A capture of a few hundred windows
     *  is a quarter of a megabyte of JSON, and the service has to write it
     *  down before it answers. */
    private static final long DATASET_TIMEOUT_MS = 60L * 1000L;

    private final OculusAI plugin;
    private HttpClient client;
    private ScheduledFuture<?> probeTask;
    private volatile List<Exclusion> exclusions = List.of();
    /** Which dataset schemas the service will accept. Empty until the first
     *  settings fetch lands, which is why the dataset manager treats empty as
     *  "assume only what has always worked" rather than as "nothing works". */
    private volatile Set<Integer> serviceSchemas = Set.of();
    /** What the service last said this key is entitled to. Read from the
     *  settings poll: the plan can lapse between restarts, and until this
     *  arrived the only way to find out which model was answering was to ask
     *  somebody with the panel open. */
    private volatile String servicePlan = "";
    private volatile String serviceModel = "";
    private volatile boolean modelDemoted;
    /** Last counts taken on the main thread, for the settings poll to send.
     *  Volatile because the sampler and the poll are different threads. */
    private volatile int onlinePlayers;
    private volatile int analysedPlayers;
    private TickLoop.Handle presenceTask;

    private record Exclusion(String world, String region) {}

    public ApiClient(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(plugin.config().apiTimeoutMs()))
                .executor(plugin.worker().executor())
                .build();
        long period = PROBE_PERIOD_TICKS * 50L;
        probeTask = plugin.worker().repeat(this::probe, period, period, TimeUnit.MILLISECONDS);
        presenceTask = plugin.ticks().every(PRESENCE_SAMPLE_TICKS, this::samplePresence);
        refreshSettings();
    }

    @Override
    public void disable() {
        if (probeTask != null) {
            probeTask.cancel(false);
            probeTask = null;
        }
        if (presenceTask != null) {
            presenceTask.cancel();
            presenceTask = null;
        }
        onlinePlayers = 0;
        analysedPlayers = 0;
    }

    public CompletableFuture<Double> predict(Map<String, Double> features) {
        JsonObject body = new JsonObject();
        JsonObject values = new JsonObject();
        features.forEach(values::addProperty);
        body.add("features", values);

        return postVoting("/predict", body).thenApply(response -> {
            if (response == null || !response.has("probability")) {
                return Double.NaN;
            }
            return response.get("probability").getAsDouble();
        });
    }

    public boolean excluded(Player player) {
        String world = player.getWorld().getName();
        for (Exclusion exclusion : exclusions) {
            if (!exclusion.world().equalsIgnoreCase(world)) {
                continue;
            }
            if (exclusion.region().isBlank() || inWorldGuardRegion(player, exclusion.region())) {
                return true;
            }
        }
        return false;
    }

    private boolean inWorldGuardRegion(Player player, String wanted) {
        try {
            Object worldGuard = Class.forName("com.sk89q.worldguard.WorldGuard")
                    .getMethod("getInstance").invoke(null);
            Object platform = worldGuard.getClass().getMethod("getPlatform").invoke(worldGuard);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object manager = container.getClass().getMethod("get", World.class)
                    .invoke(container, player.getWorld());
            if (manager == null) return false;
            Object vector = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
                    .getMethod("asBlockVector", Location.class)
                    .invoke(null, player.getLocation());
            Object set = manager.getClass().getMethod("getApplicableRegions", vector.getClass())
                    .invoke(manager, vector);
            Method iterator = set.getClass().getMethod("iterator");
            Object it = iterator.invoke(set);
            while ((Boolean) it.getClass().getMethod("hasNext").invoke(it)) {
                Object region = it.getClass().getMethod("next").invoke(it);
                String id = (String) region.getClass().getMethod("getId").invoke(region);
                if (id.equalsIgnoreCase(wanted)) return true;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return false;
    }

    public Set<Integer> serviceSchemas() {
        return serviceSchemas;
    }

    /** The plan the service is serving this key under, as of the last poll.
     *  Empty until the first one answers. */
    public String servicePlan() {
        return servicePlan;
    }

    /** Which model actually answers for this key - "fast" or "pro". */
    public String serviceModel() {
        return serviceModel;
    }

    /** True when the key is asking for a better model than the plan now
     *  reaches: the subscription lapsed and Fast is answering. */
    public boolean modelDemoted() {
        return modelDemoted;
    }

    /** Counts the two figures the service cannot work out for itself.
     *
     *  <p>On the main thread, because that is the only place the online player
     *  list may be read: it is a live view of a collection the server mutates
     *  on its own thread, and walking it from the HTTP worker is how a plugin
     *  earns a ConcurrentModificationException in somebody else's crash
     *  report. The numbers are left in two fields for the poll to pick up. */
    private void samplePresence() {
        onlinePlayers = plugin.getServer().getOnlinePlayers().size();
        int analysing = 0;
        long stale = plugin.hudConfig().staleMs();
        for (PlayerData data : plugin.data().all()) {
            if (data.analysing(stale)) {
                analysing++;
            }
        }
        analysedPlayers = analysing;
    }

    /** How many players this server has, and how many of them the anticheat is
     *  scoring right now.
     *
     *  <p>Reported on the settings poll rather than on a schedule of its own:
     *  that poll already happens, and a second timer for two integers would be
     *  a second thing to go wrong. The service cannot derive either number -
     *  a window of combat deliberately carries no identity - so if the server
     *  does not say, nobody knows. */
    private String presenceQuery() {
        return "?players=" + onlinePlayers + "&analysing=" + analysedPlayers;
    }

    private void refreshSettings() {
        get("/plugin/settings" + presenceQuery()).thenAccept(response -> {
            if (response == null) return;
            if (response.has("feature_versions")) {
                Set<Integer> schemas = new java.util.LinkedHashSet<>();
                response.getAsJsonArray("feature_versions").forEach(element -> {
                    try {
                        schemas.add(element.getAsInt());
                    } catch (RuntimeException notANumber) {
                        // A schema list with junk in it is the service's
                        // problem; take the entries that parse.
                    }
                });
                serviceSchemas = Set.copyOf(schemas);
            }
            if (response.has("plan")) {
                servicePlan = response.get("plan").getAsString();
            }
            if (response.has("model_tier")) {
                serviceModel = response.get("model_tier").getAsString();
            }
            modelDemoted = response.has("model_demoted")
                    && response.get("model_demoted").getAsBoolean();
            if (!response.has("analysis_exclusions")) return;
            List<Exclusion> loaded = new ArrayList<>();
            response.getAsJsonArray("analysis_exclusions").forEach(element -> {
                if (!element.isJsonObject()) return;
                JsonObject item = element.getAsJsonObject();
                String world = item.has("world") ? item.get("world").getAsString().trim() : "";
                String region = item.has("region") ? item.get("region").getAsString().trim() : "";
                if (!world.isBlank()) loaded.add(new Exclusion(world, region));
            });
            exclusions = List.copyOf(loaded);
        });
    }

    public CompletableFuture<JsonObject> addSamples(String label, String player, String uuid,
                                                    List<String> featureNames, List<double[]> rows,
                                                    boolean enabled, String source, String person,
                                                    List<double[]> context, int featureVersion) {
        JsonObject body = new JsonObject();
        body.addProperty("label", label);
        body.addProperty("player", player);
        body.addProperty("uuid", uuid);
        body.addProperty("enabled", enabled);
        body.addProperty("source", source);
        body.addProperty("person", person == null ? "" : person);
        body.addProperty("feature_version", featureVersion);

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
        HttpRequest request = builder("/train", plugin.config().datasetKey())
                .timeout(Duration.ofMillis(TRAIN_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new JsonObject().toString()))
                .build();
        return send(request, false);
    }

    public CompletableFuture<JsonObject> removeSession(String id) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        return post("/sessions/remove", body);
    }

    /** The newest published build for this jar's flavour. Does not vote on
     *  reachability: a version check failing says nothing about whether the
     *  service can answer a prediction, and must never pause analysis. */
    public CompletableFuture<JsonObject> latestBuild(String flavour) {
        String path = "/plugin/latest?flavour="
                + java.net.URLEncoder.encode(flavour, java.nio.charset.StandardCharsets.UTF_8);
        return send(builder(path, plugin.config().apiKey()).GET().build(), false);
    }

    /** Hands up one join: this name, from this address, on this server.
     *
     *  <p>The address travels raw and is not kept raw: the service hashes it
     *  under a secret of its own and stores the hash plus the first half of
     *  the address, which is what makes "how many addresses does this name
     *  use" answerable without a table of who plays from where. Does not vote
     *  on reachability - a join report failing says nothing about whether the
     *  model can answer. */
    public CompletableFuture<JsonObject> reputationSeen(String player, String uuid,
                                                        String address) {
        JsonObject body = new JsonObject();
        body.addProperty("player", player);
        body.addProperty("uuid", uuid);
        body.addProperty("address", address);
        return post("/reputation/seen", body);
    }

    /** What the service has on a name across every server it answers for. */
    public CompletableFuture<JsonObject> reputation(String player) {
        String path = "/reputation/" + java.net.URLEncoder.encode(player,
                java.nio.charset.StandardCharsets.UTF_8);
        return get(path);
    }

    /** Sends one replay up for the panel to play back.
     *
     *  <p>On the dataset timeout rather than the ordinary one. A replay is a
     *  few hundred kilobytes that the service has to write to disk before it
     *  answers, and the ordinary timeout is a second and a half: sending this
     *  on it would time out on a slow link, and a timeout on the predict path
     *  is what marks the service unreachable. This one does not vote. */
    public CompletableFuture<JsonObject> uploadReplay(JsonObject replay) {
        HttpRequest.Builder builder = builder("/replays", plugin.config().apiKey())
                .timeout(Duration.ofMillis(Math.max(plugin.config().apiTimeoutMs(),
                        DATASET_TIMEOUT_MS)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(replay.toString()));
        return send(builder.build(), false);
    }

    public CompletableFuture<JsonObject> status() {
        return get("/status");
    }

    public CompletableFuture<JsonObject> reportKick(String player, String uuid,
                                                   double probability, double buffer,
                                                   String detail) {
        JsonObject body = new JsonObject();
        body.addProperty("player", player);
        body.addProperty("uuid", uuid);
        body.addProperty("probability", probability);
        body.addProperty("buffer", buffer);
        body.addProperty("detail", detail);
        return post("/events/kick", body);
    }

    public CompletableFuture<JsonObject> reportAlert(String player, String uuid,
                                                     double probability, double buffer) {
        JsonObject body = new JsonObject();
        body.addProperty("player", player);
        body.addProperty("uuid", uuid);
        body.addProperty("probability", probability);
        body.addProperty("buffer", buffer);
        return post("/events/alert", body);
    }

    public CompletableFuture<JsonObject> reportPlayer(String player, String uuid,
                                                      String detail, String by) {
        JsonObject body = new JsonObject();
        body.addProperty("player", player);
        body.addProperty("uuid", uuid);
        body.addProperty("detail", detail);
        body.addProperty("by", by);
        return post("/events/report", body);
    }

    public CompletableFuture<JsonObject> linkStart(String server, String version) {
        JsonObject body = new JsonObject();
        body.addProperty("server", server);
        body.addProperty("version", version);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(plugin.config().apiUrl() + "/link/start"))
                .timeout(Duration.ofMillis(plugin.config().apiTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return send(builder.build(), false);
    }

    public CompletableFuture<JsonObject> linkStatus(String token) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(plugin.config().apiUrl() + "/link/status/" + token))
                .timeout(Duration.ofMillis(plugin.config().apiTimeoutMs()))
                .GET()
                .build();
        return send(request, false);
    }

    private CompletableFuture<JsonObject> post(String path, JsonObject body) {
        HttpRequest.Builder builder = builder(path, plugin.config().apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return send(builder.build(), false);
    }

    /** Only prediction is allowed to decide that the service is down. */
    private CompletableFuture<JsonObject> postVoting(String path, JsonObject body) {
        HttpRequest.Builder builder = builder(path, plugin.config().apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return send(builder.build(), true);
    }

    private CompletableFuture<JsonObject> postDataset(String path, JsonObject body) {
        // A dataset push is not a question, it is a delivery: a few hundred
        // windows, a quarter of a megabyte of JSON, and the service writes a
        // CSV and answers. The ordinary timeout is a second and a half, which
        // a big capture can easily outlast - and a timeout is read as "the
        // service is down", so the capture ends up in the retry queue for a
        // reason that has nothing to do with the service being down.
        HttpRequest.Builder builder = builder(path, plugin.config().datasetKey())
                .timeout(Duration.ofMillis(Math.max(plugin.config().apiTimeoutMs(),
                        DATASET_TIMEOUT_MS)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return send(builder.build(), false);
    }

    private CompletableFuture<JsonObject> get(String path) {
        return send(builder(path, plugin.config().apiKey()).GET().build(), false);
    }

    private void probe() {
        if (probes.getAndIncrement() % SETTINGS_EVERY_PROBES == 0) {
            refreshSettings();
        }
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
        return send(builder("/health", plugin.config().apiKey()).GET().build(), true);
    }

    private HttpRequest.Builder builder(String path, String key) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(plugin.config().apiUrl() + path))
                .timeout(Duration.ofMillis(plugin.config().apiTimeoutMs()));
        if (key != null && !key.isBlank()) {
            builder.header("X-OculusAI-Key", key);
        }
        return builder;
    }

    /** ``votes`` is whether this request may change {@link #reachable}.
     *  Only prediction and the health probe are allowed to: an event report,
     *  settings refresh or link/pair request failing says nothing about
     *  whether the model can answer. */
    private CompletableFuture<JsonObject> send(HttpRequest request, boolean votes) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (votes) {
                        int status = response.statusCode();
                        if (status / 100 == 2 || status == 429) {
                            markReachable(true);
                        } else if (status >= 500) {
                            // A 500 from the health/predict endpoint is the
                            // service saying it cannot answer. A 4xx is a
                            // request/key problem, not a reason to declare the
                            // whole service unreachable.
                            markReachable(false);
                        }
                    }
                    if (response.statusCode() == 429) {
                        noteThrottled(response.body());
                        return null;
                    }
                    if (response.statusCode() / 100 != 2) {
                        plugin.getLogger().warning("Service answered " + response.statusCode()
                                + " for " + request.uri().getPath() + ": "
                                + abbreviate(response.body()));
                        if (response.statusCode() == 403
                                && response.body() != null
                                && response.body().contains("\"001\"")) {
                            plugin.getLogger().warning("The service does not accept this key"
                                    + " from this address: the server address in the panel is not"
                                    + " the one this server connects from. /oai connect rebinds it.");
                        }
                        return null;
                    }
                    return new JsonParser().parse(response.body()).getAsJsonObject();
                })
                .exceptionally(throwable -> {
                    if (votes) {
                        markReachable(false);
                    }
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
