package me.everyone.oculusai.npc;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers.ItemSlot;
import com.comphenix.protocol.wrappers.EnumWrappers.NativeGameMode;
import com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction;
import com.comphenix.protocol.wrappers.Pair;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class NpcPackets implements NpcProtocol {

    /** Packet types this server build will not let ProtocolLib construct.
     *
     * <p>ProtocolLib builds an outgoing packet by instantiating the server's
     * own packet class, which it can only do for versions it has been taught.
     * 5.4.0 knows up to 1.21.8, so on a newer server some packets - head
     * rotation and the swing animation among them - throw on creation.
     *
     * <p>Before this latch the throw travelled up through tick(), so one
     * unsupported cosmetic packet cost the whole NPC tick: the manikin stopped
     * dealing damage and stopped being registered with the hitbox tracker,
     * and the log took a stack trace every tick. Now the effect is dropped,
     * reported once, and everything that does work keeps working. */
    private final Set<PacketType> unsupported = ConcurrentHashMap.newKeySet();

    @Override
    public void spawn(Player viewer, Npc npc) {
        PacketContainer info = packet(PacketType.Play.Server.PLAYER_INFO);
        if (info == null) {
            return;
        }
        info.getPlayerInfoActions().write(0, EnumSet.of(
                PlayerInfoAction.ADD_PLAYER,
                PlayerInfoAction.UPDATE_LISTED,
                PlayerInfoAction.UPDATE_GAME_MODE));
        info.getPlayerInfoDataLists().write(1, List.of(new PlayerInfoData(
                npc.uuid(), 0, true, NativeGameMode.SURVIVAL, npc.profile(),
                WrappedChatComponent.fromText(npc.name()))));
        send(viewer, info);

        PacketContainer spawn = packet(PacketType.Play.Server.SPAWN_ENTITY);
        if (spawn == null) {
            return;
        }
        spawn.getIntegers().write(0, npc.entityId());
        spawn.getUUIDs().write(0, npc.uuid());
        spawn.getEntityTypeModifier().write(0, EntityType.PLAYER);
        spawn.getDoubles().write(0, npc.x()).write(1, npc.y()).write(2, npc.z());
        spawn.getBytes()
                .write(0, angle(npc.pitch()))
                .write(1, angle(npc.yaw()))
                .write(2, angle(npc.yaw()));
        send(viewer, spawn);

        look(viewer, npc);
    }

    @Override
    public void despawn(Player viewer, Npc npc) {
        PacketContainer destroy = packet(PacketType.Play.Server.ENTITY_DESTROY);
        if (destroy != null) {
            destroy.getIntLists().write(0, List.of(npc.entityId()));
            send(viewer, destroy);
        }

        PacketContainer info = packet(PacketType.Play.Server.PLAYER_INFO_REMOVE);
        if (info != null) {
            info.getUUIDLists().write(0, List.of(npc.uuid()));
            send(viewer, info);
        }
    }

    @Override
    public void hurt(Player viewer, Npc npc) {
        PacketContainer packet = packet(PacketType.Play.Server.HURT_ANIMATION);
        if (packet == null) {
            return;
        }
        packet.getIntegers().write(0, npc.entityId());
        packet.getFloat().write(0, 0.0F);
        send(viewer, packet);
    }

    @Override
    public void death(Player viewer, Npc npc) {
        PacketContainer packet = packet(PacketType.Play.Server.ENTITY_STATUS);
        if (packet == null) {
            return;
        }
        packet.getIntegers().write(0, npc.entityId());
        packet.getBytes().write(0, (byte) 3);
        send(viewer, packet);
    }

    @Override
    public void move(Player viewer, Npc npc, double dx, double dy, double dz) {
        PacketContainer packet = packet(PacketType.Play.Server.REL_ENTITY_MOVE_LOOK);
        if (packet == null) {
            return;
        }
        packet.getIntegers().write(0, npc.entityId());
        packet.getShorts().write(0, delta(dx)).write(1, delta(dy)).write(2, delta(dz));
        packet.getBytes().write(0, angle(npc.yaw())).write(1, angle(npc.pitch()));
        packet.getBooleans().write(0, true);
        send(viewer, packet);
    }

    @Override
    public void look(Player viewer, Npc npc) {
        PacketContainer head = packet(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        if (head != null) {
            head.getIntegers().write(0, npc.entityId());
            head.getBytes().write(0, angle(npc.yaw()));
            send(viewer, head);
        }

        PacketContainer body = packet(PacketType.Play.Server.ENTITY_LOOK);
        if (body != null) {
            body.getIntegers().write(0, npc.entityId());
            body.getBytes().write(0, angle(npc.yaw())).write(1, angle(npc.pitch()));
            body.getBooleans().write(0, true);
            send(viewer, body);
        }
    }

    @Override
    public void swing(Player viewer, Npc npc) {
        PacketContainer packet = packet(PacketType.Play.Server.ANIMATION);
        if (packet == null) {
            return;
        }
        packet.getIntegers().write(0, npc.entityId()).write(1, 0);
        send(viewer, packet);
    }

    @Override
    public void equip(Player viewer, Npc npc) {
        PacketContainer packet = packet(PacketType.Play.Server.ENTITY_EQUIPMENT);
        if (packet == null) {
            return;
        }
        packet.getIntegers().write(0, npc.entityId());
        packet.getSlotStackPairLists().write(0, List.of(
                new Pair<>(ItemSlot.MAINHAND, gear(Material.NETHERITE_SWORD)),
                new Pair<>(ItemSlot.HEAD, gear(Material.IRON_HELMET)),
                new Pair<>(ItemSlot.CHEST, gear(Material.IRON_CHESTPLATE)),
                new Pair<>(ItemSlot.LEGS, gear(Material.IRON_LEGGINGS)),
                new Pair<>(ItemSlot.FEET, gear(Material.IRON_BOOTS))));
        send(viewer, packet);
    }

    private static byte angle(float degrees) {
        return (byte) (degrees * 256.0F / 360.0F);
    }

    private static short delta(double blocks) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(blocks * 4096.0D)));
    }

    private void send(Player viewer, PacketContainer packet) {
        ProtocolLibrary.getProtocolManager().sendServerPacket(viewer, packet);
    }

    /** Creates a packet, or null if this server build cannot. */
    private PacketContainer packet(PacketType type) {
        if (unsupported.contains(type)) {
            return null;
        }
        try {
            return ProtocolLibrary.getProtocolManager().createPacket(type);
        } catch (RuntimeException | LinkageError failure) {
            unsupported.add(type);
            JavaPlugin.getProvidingPlugin(NpcPackets.class).getLogger().log(Level.WARNING,
                    "ProtocolLib cannot create " + type.name() + " packets on this server"
                            + " build, so the sparring manikin will do without that effect."
                            + " ProtocolLib 5.4.0 supports Minecraft up to 1.21.8; a newer"
                            + " build of ProtocolLib fixes this. Reported once.", failure);
            return null;
        }
    }

    private static ItemStack gear(Material material) {
        return new ItemStack(material);
    }
}
