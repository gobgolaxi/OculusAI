package me.everyone.oculusai.packet;


import me.everyone.oculusai.core.Service;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.wrappers.EnumWrappers;
import java.util.logging.Level;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.detection.AimGeometry;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

public final class PacketBridge implements Service {

    private final OculusAI plugin;
    private final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
    private PacketListener listener;

    public PacketBridge(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        listener = new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Client.USE_ENTITY,
                // Махнул рукой, никого не ударив. Килоаура обычно машет
                // только по цели; человек промахивается и бьёт по воздуху.
                PacketType.Play.Client.ARM_ANIMATION,
                PacketType.Play.Client.POSITION,
                PacketType.Play.Client.POSITION_LOOK,
                PacketType.Play.Client.LOOK,
                // Движение без позиции и поворота - только флаг земли. На
                // 1.21 это GROUND; прежняя константа здесь не
                // зарегистрирована, и подписка на неё молча ничего не ловила.
                PacketType.Play.Client.GROUND) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                try {
                    handle(event);
                } catch (Throwable throwable) {
                    plugin.getLogger().log(Level.SEVERE,
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
        Player player = event.getPlayer();
        PlayerData data = plugin.data().get(player);
        if (data == null) {
            return;
        }
        // While the send-off is playing the cheater is frozen: attacks,
        // looks and positions are all dropped here, so nothing they do from
        // the moment of the verdict counts. Their own client keeps swinging
        // and turning on screen - no server can stop that - but the server
        // never hears any of it.
        if (plugin.punishments().isHeld(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        PacketType type = event.getPacketType();

        if (type == PacketType.Play.Client.ARM_ANIMATION) {
            // Флагом, а не записью: тик закрывается следующим пакетом
            // движения, и до него неизвестно, прилетит ли за этим свингом
            // атака. Порядок пакетов у клиента - сначала анимация, потом
            // USE_ENTITY, поэтому к закрытию тика ответ уже есть.
            data.noteEmptySwing();
            return;
        }

        if (type == PacketType.Play.Client.USE_ENTITY) {
            if (event.isCancelled()) {
                return;
            }
            var wrapped = event.getPacket().getEnumEntityUseActions().readSafely(0);
            if (wrapped != null && wrapped.getAction() == EnumWrappers.EntityUseAction.ATTACK) {
                int targetEntityId = event.getPacket().getIntegers().read(0);
                // Only a hit on a player counts as combat. A mob is a
                // different measurement entirely - it walks a straight line
                // and never strafes, so honest farming reads like a cheat -
                // and an entity nobody tracks is not proof of anything.
                if (!plugin.tracker().playerLike(targetEntityId)) {
                    return;
                }
                if (plugin.checks().onAttack(player, data, targetEntityId)) {
                    event.setCancelled(true);
                } else {
                    data.markAttack(aimError(data, targetEntityId), player.getFallDistance(),
                            player.isOnGround(), player.isSprinting(), critBlocked(player));
                }
            }
            return;
        }

        if (isMovement(type)) {
            // An elytra flight is a different instrument. Rotation steers the
            // body rather than a crosshair, pitch is held at extremes for
            // seconds at a time, and the whole window is smooth by
            // construction - which is what the model was taught to read as
            // assistance. Samples are dropped rather than merely left
            // unscored, and the window is cleared, so that a flight cannot
            // splice onto the ground fight that follows it.
            if (player.isGliding()) {
                data.clearWindow();
                return;
            }
            data.noteGround(player.isOnGround());
            data.noteSprint(player.isSprinting());
            if (type == PacketType.Play.Client.POSITION || type == PacketType.Play.Client.GROUND) {
                data.addSample(data.lastYaw(), data.lastPitch(),
                        plugin.config().windowTicks(),
                        AimGeometry.of(plugin.tracker(), data.entityId(),
                                data.lastYaw(), data.lastPitch()),
                        player.isOnGround(), player.isSprinting());
                return;
            }
            float yaw = event.getPacket().getFloat().readSafely(0) != null
                    ? event.getPacket().getFloat().read(0) : data.lastYaw();
            float pitch = event.getPacket().getFloat().readSafely(1) != null
                    ? event.getPacket().getFloat().read(1) : data.lastPitch();
            data.addSample(yaw, pitch, plugin.config().windowTicks(),
                    AimGeometry.of(plugin.tracker(), data.entityId(), yaw, pitch),
                    player.isOnGround(), player.isSprinting());
        }
    }

    /** Угол до цели в тике удара.
     *
     *  <p>Арифметика живёт в {@link AimGeometry}: тот же угол считается
     *  теперь в каждом тике, и два определения одного числа - это стык, на
     *  котором модель учится вместо поведения. */
    private double aimError(PlayerData data, int targetEntityId) {
        return AimGeometry.angleTo(plugin.tracker().get(data.entityId()),
                plugin.tracker().get(targetEntityId), data.lastYaw(), data.lastPitch());
    }

    private boolean critBlocked(Player player) {
        return player.isInsideVehicle()
                || player.isInWater()
                || player.hasPotionEffect(PotionEffectType.BLINDNESS);
    }

    private boolean isMovement(PacketType type) {
        return type == PacketType.Play.Client.POSITION
                || type == PacketType.Play.Client.POSITION_LOOK
                || type == PacketType.Play.Client.LOOK
                || type == PacketType.Play.Client.GROUND;
    }
}
