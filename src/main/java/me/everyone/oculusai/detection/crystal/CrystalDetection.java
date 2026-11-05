package me.everyone.oculusai.detection.crystal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.core.TickLoop;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.entity.Player;

/** Кристалльные окна - на предсказание, показания - в буфер и Neuro (Crystal).
 *
 * <p>Раз в секунду решает, подключать ли игрокам предсказание: только когда
 * анализ включён, сервис доступен и на нём стоит кристалльная модель. Без
 * модели окна не режутся вовсе (если не идёт запись) - никакой работы впустую.
 *
 * <p>На одного игрока - не больше одного запроса в полёте, как у аима: серия
 * «поставил - ударил» быстрее ответа сервиса, и очередь из неё ничего не даёт.
 */
public final class CrystalDetection implements Service {

    private final OculusAI plugin;
    private final Map<UUID, Boolean> inFlight = new ConcurrentHashMap<>();
    private TickLoop.Handle task;

    public CrystalDetection(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        task = plugin.ticks().every(20L, this::attach);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (PlayerData data : plugin.data().all()) {
            data.crystal().sink(null);
        }
        inFlight.clear();
    }

    private boolean active() {
        return plugin.config().analysisEnabled()
                && plugin.api().reachable()
                && plugin.api().crystalModel();
    }

    private void attach() {
        boolean active = active();
        for (PlayerData data : plugin.data().all()) {
            CrystalTimeline timeline = data.crystal();
            if (active && !timeline.predicting()) {
                timeline.sink((columns, ticks) -> submit(data, columns, ticks));
            } else if (!active && timeline.predicting()) {
                timeline.sink(null);
            }
        }
    }

    private void submit(PlayerData data, double[] columns, double[] ticks) {
        if (inFlight.putIfAbsent(data.uuid(), Boolean.TRUE) != null) {
            return;
        }
        Map<String, Double> features = new LinkedHashMap<>();
        for (int i = 0; i < CrystalSchema.COLUMNS.size(); i++) {
            features.put(CrystalSchema.COLUMNS.get(i), columns[i]);
        }
        plugin.api().predictCrystal(data.uuid(), features, ticks, CrystalSchema.TICK_CHANNELS.size())
                .thenAccept(probability -> {
                    if (probability == null || Double.isNaN(probability)) {
                        return;
                    }
                    plugin.mainThread().run(() -> accept(data, probability));
                })
                .whenComplete((ignored, failure) -> inFlight.remove(data.uuid()));
    }

    private void accept(PlayerData data, double probability) {
        Player player = data.player();
        if (player == null || !player.isOnline() || plugin.bypass().isBypassed(player)
                || plugin.api().excluded(player)) {
            return;
        }
        data.crystal().applyProbability(probability,
                plugin.config().suspicion(),
                plugin.config().gain(),
                plugin.config().decay(),
                plugin.config().bufferLeak(),
                plugin.config().bufferMax(),
                plugin.config().bufferMaxStep());
        boolean alerted = plugin.checks().onCrystalPrediction(data);
        if (alerted && data.crystal().buffer() >= plugin.config().punishAt()) {
            plugin.punishments().handle(data);
        }
    }
}
