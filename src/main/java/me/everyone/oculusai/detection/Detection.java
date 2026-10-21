package me.everyone.oculusai.detection;


import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.core.TickLoop;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.detection.CritTiming;
import me.everyone.oculusai.detection.FeatureExtractor;
import me.everyone.oculusai.detection.FeatureSchemas;
import me.everyone.oculusai.detection.FeatureVector;
import me.everyone.oculusai.detection.RotationSample;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.entity.Player;

public final class Detection implements Service {

    private final OculusAI plugin;
    private final Map<UUID, Boolean> inFlight = new ConcurrentHashMap<>();
    private TickLoop.Handle task;
    /** Which probability /oai prob prints. True is the smoothed value that
     *  feeds the buffer; false is the model's raw answer. The buffer itself
     *  always runs - this switch must never change detection. */
    private volatile boolean mathEnabled = true;

    public Detection(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        long period = Math.max(2, plugin.config().windowTicks() / 2);
        task = plugin.ticks().every(period, this::tick);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        inFlight.clear();
    }

    public void forget(UUID uuid) {
        inFlight.remove(uuid);
    }

    public boolean mathEnabled() {
        return mathEnabled;
    }

    /** Switches only what /oai prob displays. The buffer keeps integrating
     *  exactly as before, so Aim alerts and punishments do not change. */
    public boolean toggleMath() {
        mathEnabled = !mathEnabled;
        return mathEnabled;
    }

    private void tick() {
        if (!plugin.config().analysisEnabled()) {
            return;
        }
        for (PlayerData data : plugin.data().all()) {
            Player player = data.player();
            if (!player.isOnline()) {
                continue;
            }
            // A buffer is evidence about the last few seconds, not a permanent
            // record. Once readings stop arriving the player has changed what
            // they are doing, so the next fight must not inherit the peak of
            // the previous one.
            if (plugin.config().checkAimResetAfterCombat()
                    && data.buffer() > 0.0D
                    && data.idle(plugin.config().combatMs())) {
                data.resetBuffer();
                plugin.alerts().reset(data.uuid(), "aim");
            }
            if (plugin.bypass().isBypassed(player)) {
                continue;
            }
            if (plugin.api().excluded(player)) {
                continue;
            }
            analyse(data);
        }
    }

    private void analyse(PlayerData data) {
        List<RotationSample> window = data.snapshotWindow(plugin.config().windowTicks());
        if (window == null || !data.inCombat(plugin.config().combatMs())) {
            return;
        }

        int attacks = 0;
        double rotation = 0.0D;
        for (int i = 0; i < window.size(); i++) {
            if (window.get(i).attacked()) {
                attacks++;
            }
            if (i > 0) {
                rotation += Math.abs(FeatureExtractor.wrapDegrees(
                        window.get(i).yaw() - window.get(i - 1).yaw()));
            }
        }
        if (attacks < plugin.config().minAttacks()) {
            return;
        }
        double scale = FeatureExtractor.scaleOf(window);
        FeatureVector vector = FeatureExtractor.extract(window, data.baselineScale());
        if (vector == null) {
            return;
        }

        Map<String, Double> features = new LinkedHashMap<>(vector.toMap());
        features.putAll(CritTiming.of(data.attackHistory())
                .toMap(FeatureExtractor.critRate(window)));
        data.running().add(aimValues(vector));
        features.putAll(data.running().toMap());

        if (!data.recording() && rotation < plugin.config().minRotation()) {
            return;
        }
        if (!data.recording() && attackLinkedRotation(window) < plugin.config().minAttackRotation()) {
            return;
        }

        if (data.recording()) {
            data.record(new ArrayList<>(features.keySet()), toArray(features));
            return;
        }
        if (!plugin.api().reachable()) {
            return;
        }
        if (inFlight.putIfAbsent(data.uuid(), Boolean.TRUE) != null) {
            return;
        }

        plugin.api().predict(features).thenAccept(probability -> {
            if (probability == null || Double.isNaN(probability)) {
                return;
            }
            plugin.mainThread().run(() -> {
                if (!data.recording() && probability < plugin.config().suspicion()) {
                    data.recordScale(scale);
                }
                accept(data, probability);
            });
        }).whenComplete((ignored, throwable) -> inFlight.remove(data.uuid()));
    }

    /** Rotation close enough to an attack to belong to it.
     *
     *  <p>The attack packet carries no rotation of its own, and the server can
     *  receive the look before the hit. In that order the flick that set up
     *  the hit is one sample before the sample marked as attacked, so looking
     *  only at samples marked in place skipped exactly the fast hit-and-turn
     *  case and the window was never analysed. A delta counts when either end
     *  or the sample right after it is an attack. */
    static double attackLinkedRotation(List<RotationSample> window) {
        double total = 0.0D;
        for (int i = 1; i < window.size(); i++) {
            RotationSample previous = window.get(i - 1);
            RotationSample current = window.get(i);
            boolean nearAttack = previous.attacked() || current.attacked()
                    || (i + 1 < window.size() && window.get(i + 1).attacked());
            if (!nearAttack) {
                continue;
            }
            double yaw = Math.abs(FeatureExtractor.wrapDegrees(current.yaw() - previous.yaw()));
            double pitch = Math.abs(current.pitch() - previous.pitch());
            total += Math.hypot(yaw, pitch);
        }
        return total;
    }

    private void accept(PlayerData data, double probability) {
        // The buffer integrates the smoothed reading, not the raw one. Recording
        // still gets the raw value - a session is a record of what was measured
        // - but the thing that decides a kick should not be swayed by one
        // degenerate window. This path never depends on mathEnabled: that flag
        // only chooses which probability a live display prints.
        data.rawProbability(probability);
        data.applyProbability(data.smooth(probability),
                plugin.config().suspicion(),
                plugin.config().gain(),
                plugin.config().decay(),
                plugin.config().bufferLeak(),
                plugin.config().bufferMax());
        plugin.history().record(data);
        plugin.journal().record(data);

        // The alert, its history entry and the service report belong to the
        // check, not to the model: Detection only says that a new reading is
        // in. Punishment still follows an alert, so a suppressed duplicate
        // cannot kick twice for the same buffer.
        boolean alerted = plugin.checks().onPrediction(data);
        if (alerted && data.buffer() >= plugin.config().punishAt()) {
            plugin.punishments().handle(data);
        }
    }

    private double[] aimValues(FeatureVector vector) {
        Map<String, Double> map = vector.toMap();
        double[] values = new double[FeatureSchemas.AIM.length];
        for (int i = 0; i < values.length; i++) {
            Double value = map.get(FeatureSchemas.AIM[i]);
            values[i] = value == null ? 0.0D : value;
        }
        return values;
    }

    private double[] toArray(Map<String, Double> features) {
        double[] values = new double[features.size()];
        int index = 0;
        for (double value : features.values()) {
            values[index++] = value;
        }
        return values;
    }
}
