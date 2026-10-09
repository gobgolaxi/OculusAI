package me.everyone.yuppyai.npc;

import org.bukkit.entity.Player;

public interface NpcProtocol {

    void spawn(Player viewer, Npc npc);

    void despawn(Player viewer, Npc npc);

    void move(Player viewer, Npc npc, double dx, double dy, double dz);

    void look(Player viewer, Npc npc);

    void swing(Player viewer, Npc npc);

    void hurt(Player viewer, Npc npc);

    void death(Player viewer, Npc npc);

    void equip(Player viewer, Npc npc);
}
