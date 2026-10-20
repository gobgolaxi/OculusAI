package me.everyone.oculusai.check;

import me.everyone.oculusai.player.PlayerData;
import org.bukkit.entity.Player;

public interface CombatCheck extends Check {

    CheckResult test(Player attacker, PlayerData data, int targetEntityId);
}
