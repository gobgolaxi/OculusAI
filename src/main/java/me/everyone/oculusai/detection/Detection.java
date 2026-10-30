package me.everyone.oculusai.detection;


import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.core.TickLoop;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
            // A buffer is evidence about the last few seconds, not a
            // permanent record, so a player who has stopped fighting must not
            // carry their peak into the next fight.
            //
            // Both halves are needed, and the swing is the important one.
            // Readings alone stopping does not mean the fight stopped: a
            // window is dropped whenever it falls under min-rotation, under
            // min-attack-rotation or under min-attacks, which happens
            // constantly in a real fight - chasing, missing, drinking, waiting
            // out a cooldown. Keyed on readings alone and measured against the
            // two-second combat window, this threw away a buffer of 6.9 in the
            // middle of a PvP fight, which is also exactly how a cheater who
            // pauses every few seconds would never reach the punishment
            // threshold at all.
            long quiet = plugin.config().checkAimResetAfterMs();
            if (plugin.config().checkAimResetAfterCombat()
                    && data.buffer() > 0.0D
                    && data.idle(quiet)
                    && !data.inCombat(quiet)) {
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

    /** Имена каналов сырого окна, в том порядке, в каком их кладёт
     *  {@link #ticksOf}. Уходит в запись рядом с числами: формат должен
     *  читаться без знания этого файла. */
    public static final List<String> TICK_CHANNELS =
            List.of("yaw", "pitch", "attacked", "aim_error",
                    "ticks_since_attack", "teleport",
                    // Геометрия цели. Самый сильный признак доводки прицела -
                    // не вращение само по себе, а как шла ошибка до хитбокса;
                    // до этих каналов она существовала одним числом в тике
                    // удара, а в остальных тридцати одном тике окна её не
                    // было вовсе.
                    "target_angle", "target_edge_angle", "target_distance",
                    "target_id", "targets_near",
                    // Контекст движения: без него страйф-флик бегущего боком
                    // легита и ровная доводка выглядят одинаково.
                    "swung_empty", "on_ground", "sprinting", "speed",
                    // Настоящее время между тиками. Ровность интервала - сама
                    // по себе признак, а серверный тик её сглаживает.
                    "since_last_ms");

    /** Окно как последовательность, а не как сводка.
     *
     *  <p>Колонки, которые считает {@link FeatureExtractor}, - это среднее,
     *  разброс и ещё десяток чисел о двадцати тиках. Здесь те же тики как
     *  есть, подряд по каналам: это вход траекторного кандидата на сервисе
     *  (trajectory.py), который сам решает, что из них считать - скорость,
     *  ускорение, расстановку ударов. Поэтому и пишутся сырыми: переопредели
     *  производный канал, и ни одна записанная сессия не обесценится. */
    /** Чем записывается «этого числа в тике не было».
     *
     *  <p>Не NaN. {@code JsonObject.toString()} у Gson пишет NaN голым словом,
     *  а это невалидный JSON: он доезжал только потому, что разбор на стороне
     *  Python такое слово принимает как расширение формата. Пока NaN был
     *  только у {@code aim_error}, это было незаметной мелочью; с геометрией
     *  цели отсутствующих значений стало большинство - цели рядом нет почти
     *  всегда, - и держать формат на случайности нельзя.
     *
     *  <p>Минус единица однозначна: все величины, которые могут отсутствовать,
     *  неотрицательны - углы, расстояние, промах прицела. А то, что значение
     *  отсутствует, модель читает не по самому числу, а по соседнему каналу:
     *  {@code target_id} равен -1, когда цели нет, {@code attacked} равен
     *  нулю, когда удара не было. */
    public static final double NO_VALUE = -1.0D;

    private static double finite(double value) {
        return Double.isFinite(value) ? value : NO_VALUE;
    }

    private static double[] ticksOf(List<RotationSample> window) {
        double[] flat = new double[window.size() * TICK_CHANNELS.size()];
        int at = 0;
        for (RotationSample sample : window) {
            flat[at++] = sample.yaw();
            flat[at++] = sample.pitch();
            flat[at++] = sample.attacked() ? 1.0D : 0.0D;
            flat[at++] = finite(sample.aimError());
            flat[at++] = sample.ticksSinceAttack();
            flat[at++] = sample.teleport() ? 1.0D : 0.0D;
            flat[at++] = finite(sample.targetAngle());
            flat[at++] = finite(sample.targetEdgeAngle());
            flat[at++] = finite(sample.targetDistance());
            flat[at++] = sample.targetId();
            flat[at++] = sample.targetsNear();
            flat[at++] = sample.swungEmpty() ? 1.0D : 0.0D;
            flat[at++] = sample.onGround() ? 1.0D : 0.0D;
            flat[at++] = sample.sprinting() ? 1.0D : 0.0D;
            flat[at++] = finite(sample.speed());
            flat[at++] = sample.sinceLastMs();
        }
        return flat;
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

        // A window being recorded is written down first and analysed anyway.
        //
        // Recording used to return here, which meant a player on the dataset
        // roster was not analysed at all: no readings, no buffer, no alerts -
        // while config.yml promised the opposite, and while the upload format
        // had carried a score and a buffer per window from the start and had
        // been sending them empty. Somebody recording a cheater could not see
        // what the model made of them, which is most of the reason to record
        // one.
        //
        // The row goes down before the request rather than after the answer,
        // on purpose: a capture must not depend on the network. A window
        // measured while the service is unreachable is still a window, and it
        // is kept with an empty score instead of being lost.
        int epoch = data.recordedEpoch();
        // Одно и то же сырое окно уходит и в запись, и в запрос: на записи с
        // него учится траекторная модель, на запросе она же его и читает.
        // Считается один раз - иначе та же сборка делалась бы дважды за
        // окно, и ничто не гарантировало бы, что это одни и те же числа.
        double[] ticks = ticksOf(window);
        int row = data.recording()
                ? data.record(new ArrayList<>(features.keySet()), toArray(features), ticks)
                : -1;

        if (!plugin.api().reachable()) {
            return;
        }
        if (inFlight.putIfAbsent(data.uuid(), Boolean.TRUE) != null) {
            return;
        }

        // Тики едут только если их читает модель на слоте. Сервис говорит это
        // в настройках: 512 чисел на запрос, которые колоночная модель не
        // смотрит, - это трафик раз в секунду на каждого бойца.
        boolean wantsTicks = plugin.api().predictWantsTicks();
        CompletableFuture<Double> answer = plugin.api().predict(data.uuid(), features,
                wantsTicks ? ticks : null, wantsTicks ? TICK_CHANNELS.size() : 0);
        answer.thenAccept(probability -> {
            if (probability == null || Double.isNaN(probability)) {
                return;
            }
            plugin.mainThread().run(() -> {
                if (!data.recording() && probability < plugin.config().suspicion()) {
                    data.recordScale(scale);
                }
                accept(data, probability);
                // After accept(), so the buffer on the row is the buffer this
                // window produced rather than the one it started from. The
                // service derives peak_buffer from this column: taken before,
                // the peak of every session would be short by one step, and
                // the last window - the one that got somebody punished -
                // would show the buffer it was about to leave behind.
                //
                // The probability is the model's raw answer for this window,
                // not the smoothed value the buffer integrates. Smoothing is
                // a property of the sequence; a row describes one window.
                data.scoreRecorded(epoch, row, probability, data.buffer());
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
                plugin.config().bufferMax(),
                plugin.config().bufferMaxStep());
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
