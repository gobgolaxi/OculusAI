package me.everyone.yuppyai.npc;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers.ItemSlot;
import com.comphenix.protocol.wrappers.EnumWrappers.NativeGameMode;
import com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction;
import com.comphenix.protocol.wrappers.Pair;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class NpcPackets implements NpcProtocol {

    @Override
    public void spawn(Player viewer, Npc npc) {
        PacketContainer info = packet(PacketType.Play.Server.PLAYER_INFO);
        info.getPlayerInfoAction().write(0, PlayerInfoAction.ADD_PLAYER);
        info.getPlayerInfoDataLists().write(0, List.of(new PlayerInfoData(
                npc.profile(), 0, NativeGameMode.SURVIVAL,
                WrappedChatComponent.fromText(npc.name()))));
        send(viewer, info);

        PacketContainer spawn = packet(PacketType.Play.Server.NAMED_ENTITY_SPAWN);
        spawn.getIntegers().write(0, npc.entityId());
        spawn.getUUIDs().write(0, npc.uuid());
        spawn.getDoubles().write(0, npc.x()).write(1, npc.y()).write(2, npc.z());
        spawn.getBytes().write(0, angle(npc.yaw())).write(1, angle(npc.pitch()));
        send(viewer, spawn);

        look(viewer, npc);
    }

    @Override
    public void despawn(Player viewer, Npc npc) {
        PacketContainer destroy = packet(PacketType.Play.Server.ENTITY_DESTROY);
        destroy.getIntegerArrays().write(0, new int[] {npc.entityId()});
        send(viewer, destroy);

        PacketContainer info = packet(PacketType.Play.Server.PLAYER_INFO);
        info.getPlayerInfoAction().write(0, PlayerInfoAction.REMOVE_PLAYER);
        info.getPlayerInfoDataLists().write(0, List.of(new PlayerInfoData(
                npc.profile(), 0, NativeGameMode.SURVIVAL,
                WrappedChatComponent.fromText(npc.name()))));
        send(viewer, info);
    }

    @Override
    public void hurt(Player viewer, Npc npc) {
        status(viewer, npc, (byte) 2);
    }

    @Override
    public void death(Player viewer, Npc npc) {
        status(viewer, npc, (byte) 3);
    }

    private void status(Player viewer, Npc npc, byte status) {
        PacketContainer packet = packet(PacketType.Play.Server.ENTITY_STATUS);
        packet.getIntegers().write(0, npc.entityId());
        packet.getBytes().write(0, status);
        send(viewer, packet);
    }

    @Override
    public void move(Player viewer, Npc npc, double dx, double dy, double dz) {
        PacketContainer packet = packet(PacketType.Play.Server.REL_ENTITY_MOVE_LOOK);
        packet.getIntegers().write(0, npc.entityId());
        packet.getShorts().write(0, delta(dx)).write(1, delta(dy)).write(2, delta(dz));
        packet.getBytes().write(0, angle(npc.yaw())).write(1, angle(npc.pitch()));
        packet.getBooleans().write(0, true);
        send(viewer, packet);
    }

    @Override
    public void look(Player viewer, Npc npc) {
        PacketContainer head = packet(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        head.getIntegers().write(0, npc.entityId());
        head.getBytes().write(0, angle(npc.yaw()));
        send(viewer, head);

        PacketContainer body = packet(PacketType.Play.Server.ENTITY_LOOK);
        body.getIntegers().write(0, npc.entityId());
        body.getBytes().write(0, angle(npc.yaw())).write(1, angle(npc.pitch()));
        body.getBooleans().write(0, true);
        send(viewer, body);
    }

    @Override
    public void swing(Player viewer, Npc npc) {
        PacketContainer packet = packet(PacketType.Play.Server.ANIMATION);
        packet.getIntegers().write(0, npc.entityId()).write(1, 0);
        send(viewer, packet);
    }

    @Override
    public void equip(Player viewer, Npc npc) {
        PacketContainer packet = packet(PacketType.Play.Server.ENTITY_EQUIPMENT);
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

    private PacketContainer packet(PacketType type) {
        return ProtocolLibrary.getProtocolManager().createPacket(type);
    }

    private static ItemStack gear(Material material) {
        return new ItemStack(material);
    }
}
