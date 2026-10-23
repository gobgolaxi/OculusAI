package me.everyone.oculusai.replay;

import me.everyone.oculusai.OculusAI;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/** Feeds blows into the replay buffers.
 *
 *  <p>Positions come off the tick sampler, but a hit is an event and a tick
 *  is not: a blow landing between two frames would be missing from both.
 *  This records it against the tick it is drained on, which is the next one.
 *
 *  <p>Both ends of a blow are recorded. A replay is about a suspect, and
 *  whether they were the one hitting or the one being hit is exactly what
 *  somebody watching needs to see - a reach kick on a player who was being
 *  beaten by three people looks very different from one on a player who was
 *  chasing someone down.
 *
 *  <p>MONITOR and {@code ignoreCancelled}: a hit another plugin cancelled
 *  never happened as far as the server is concerned, and a replay showing
 *  blows that did not land would be a replay that disagrees with the damage
 *  the server applied. The anticheat's own checks cancel hits, so this is not
 *  hypothetical.
 */
final class ReplayListener implements Listener {

    private final OculusAI plugin;

    ReplayListener(OculusAI plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Entity victim = event.getEntity();
        ReplayFrame.Hit hit = new ReplayFrame.Hit(damager.getEntityId(), victim.getEntityId(),
                crit(damager), event.getFinalDamage());

        if (damager instanceof Player attacker) {
            plugin.replays().note(attacker.getUniqueId(), hit);
        }
        if (victim instanceof Player hurt) {
            plugin.replays().note(hurt.getUniqueId(), hit);
        }
    }

    /** The same rule {@code AttackMoment} applies, so a replay and a feature
     *  row never disagree about whether a blow was critical. */
    private boolean crit(Entity damager) {
        return damager instanceof Player player
                && player.getFallDistance() > 0.0F
                && !player.isOnGround()
                && !player.isSprinting();
    }
}
