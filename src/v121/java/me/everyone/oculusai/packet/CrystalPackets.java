package me.everyone.oculusai.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import java.util.List;
import java.util.logging.Level;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.entity.EntityType;

/** Что из сущностей увидел клиент - для CrystalPredict и наводки на кристаллы.
 *
 * <p>Исходящие пакеты появления и удаления: когда клиенту сказали о сущности
 * и где стоит каждый кристалл, о котором он знает. Удар по кристаллу раньше
 * пакета его появления значит, что клиент угадал его номер, а не увидел.
 */
public final class CrystalPackets implements Service {

    private final OculusAI plugin;
    private final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
    private PacketListener listener;

    public CrystalPackets(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        listener = new PacketAdapter(plugin, ListenerPriority.MONITOR,
                PacketType.Play.Server.SPAWN_ENTITY, PacketType.Play.Server.ENTITY_DESTROY) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    handle(event);
                } catch (Throwable throwable) {
                    plugin.getLogger().log(Level.WARNING,
                            "Error reading " + event.getPacket().getType(), throwable);
                }
            }
        };
        protocolManager.addPacketListener(listener);
    }

    @Override
    public void disable() {
        if (listener != null) {
            protocolManager.removePacketListener(listener);
            listener = null;
        }
    }

    private void handle(PacketEvent event) {
        if (event.isCancelled() || event.getPlayer() == null) {
            return;
        }
        PlayerData data = plugin.data().get(event.getPlayer());
        if (data == null) {
            return;
        }
        PacketContainer packet = event.getPacket();
        if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
            Integer id = packet.getIntegers().readSafely(0);
            if (id == null) {
                return;
            }
            EntityType type = packet.getEntityTypeModifier().readSafely(0);
            boolean crystal = type != null && type.name().equals("END_CRYSTAL");
            data.crystal().spawned(id, crystal,
                    packet.getDoubles().read(0), packet.getDoubles().read(1), packet.getDoubles().read(2));
            return;
        }
        List<Integer> ids = packet.getIntLists().readSafely(0);
        if (ids != null) {
            for (int id : ids) {
                data.crystal().destroyed(id);
            }
        }
    }
}
