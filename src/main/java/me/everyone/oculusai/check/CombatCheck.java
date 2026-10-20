package me.everyone.oculusai.check;

import me.everyone.oculusai.player.PlayerData;
import org.bukkit.entity.Player;

public interface CombatCheck {

    String id();

    default boolean testOnly() {
        return false;
    }

    default boolean immediate() {
        return false;
    }

    CheckResult test(Player attacker, PlayerData data, int targetEntityId);
}
