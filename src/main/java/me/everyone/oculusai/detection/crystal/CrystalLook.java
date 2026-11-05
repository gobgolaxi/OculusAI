package me.everyone.oculusai.detection.crystal;

import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.EntityTracker;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.entity.Player;

/** Куда смотрит игрок - для наводки на кристаллы и якоря в каждом тике.
 *
 * <p>Зовётся из обоих мостов пакетов перед тиком: глаза берутся из трекера
 * сущностей (его снимок обновляет поток игрока), поворот - из самого пакета.
 * Одна функция на две версии, чтобы определение глаз не разошлось.
 */
public final class CrystalLook {

    /** Высота глаз от ног в долях роста, как у прицела. */
    private static final double EYE_RATIO = 1.62D / 1.8D;

    private CrystalLook() {
    }

    public static void update(OculusAI plugin, Player player, PlayerData data,
                              float yaw, float pitch) {
        EntityTracker.Tracked self = plugin.tracker().get(data.entityId());
        if (self == null) {
            return;
        }
        data.crystal().look(player.getWorld().getName(),
                self.x(), self.y() + self.height() * EYE_RATIO, self.z(), yaw, pitch);
    }
}
