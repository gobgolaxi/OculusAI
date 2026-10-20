package me.everyone.oculusai.check;

import me.everyone.oculusai.player.PlayerData;

/** A check that runs on its own readings rather than on a packet.
 *
 * <p>The aim check is the first of these: the model produces a probability
 * every few ticks, and the check decides whether that reading is worth an
 * alert. */
public interface PlayerCheck extends Check {

    CheckResult test(PlayerData data);
}
