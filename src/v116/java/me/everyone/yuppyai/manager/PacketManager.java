package me.everyone.yuppyai.manager;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.wrappers.EnumWrappers;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import org.bukkit.entity.Player;

public final class PacketManager implements Manager {

    private static final double EYE_HEIGHT = 1.62D;

    private final YuppyAI plugin;
    private final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
    private PacketListener listener;

    public PacketManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        listener = new PacketAdapter(plugin, ListenerPriority.MONITOR,
                PacketType.Play.Client.USE_ENTITY,
                PacketType.Play.Client.POSITION,
                PacketType.Play.Client.POSITION_LOOK,
                PacketType.Play.Client.LOOK,
                PacketType.Play.Client.FLYING) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                try {
                    handle(event);
                } catch (Throwable throwable) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error reading " + event.getPacket().getType(), throwable);
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
        PacketType type = event.getPacketType();

        if (type == PacketType.Play.Client.USE_ENTITY) {
            EnumWrappers.EntityUseAction action = event.getPacket().getEntityUseActions().readSafely(0);
            if (action == EnumWrappers.EntityUseAction.ATTACK) {
                int targetEntityId = event.getPacket().getIntegers().read(0);
                data.markAttack(aimError(data, targetEntityId), player.getFallDistance());
            }
            return;
        }

        if (isFlying(type)) {
            if (type == PacketType.Play.Client.POSITION || type == PacketType.Play.Client.FLYING) {
                data.addSample(data.lastYaw(), data.lastPitch(), plugin.config().windowTicks());
                return;
            }
            float yaw = event.getPacket().getFloat().readSafely(0) != null ? event.getPacket().getFloat().read(0) : data.lastYaw();
            float pitch = event.getPacket().getFloat().readSafely(1) != null ? event.getPacket().getFloat().read(1) : data.lastPitch();
            data.addSample(yaw, pitch, plugin.config().windowTicks());
        }
    }

    private double aimError(PlayerData data, int targetEntityId) {
        TrackerManager.Tracked target = plugin.tracker().get(targetEntityId);
        TrackerManager.Tracked self = plugin.tracker().get(data.entityId());
        if (target == null || self == null) {
            return Double.NaN;
        }

        double eyeY = self.y() + EYE_HEIGHT;
        double toX = target.x() - self.x();
        double toY = target.centreY() - eyeY;
        double toZ = target.z() - self.z();
        double length = Math.sqrt(toX * toX + toY * toY + toZ * toZ);
        if (length < 0.1D) {
            return Double.NaN;
        }

        double yaw = Math.toRadians(data.lastYaw());
        double pitch = Math.toRadians(data.lastPitch());
        double cosPitch = Math.cos(pitch);
        double lookX = -cosPitch * Math.sin(yaw);
        double lookY = -Math.sin(pitch);
        double lookZ = cosPitch * Math.cos(yaw);

        double dot = (toX * lookX + toY * lookY + toZ * lookZ) / length;
        return Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, dot))));
    }

    private boolean isFlying(PacketType type) {
        return type == PacketType.Play.Client.POSITION
                || type == PacketType.Play.Client.POSITION_LOOK
                || type == PacketType.Play.Client.LOOK
                || type == PacketType.Play.Client.FLYING;
    }
}
