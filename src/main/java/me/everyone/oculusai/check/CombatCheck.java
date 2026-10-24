package me.everyone.oculusai.check;

import me.everyone.oculusai.player.PlayerData;
import org.bukkit.entity.Player;

public interface CombatCheck extends Check {

    CheckResult test(Player attacker, PlayerData data, int targetEntityId);

    /** Whether this check reads the world's blocks.
     *
     *  <p>Asked because of Folia. An attack arrives on a network thread, and a
     *  check that only compares numbers can answer there and cancel the hit.
     *  A check that reads blocks cannot: on Folia the chunks belong to the
     *  attacker's region thread, and reading them anywhere else throws. Those
     *  checks are handed to that thread a moment later instead, which costs
     *  the ability to cancel the hit but keeps the finding - and the finding
     *  is the point. False by default: most checks read nothing but what the
     *  packet brought. */
    default boolean readsBlocks() {
        return false;
    }
}
