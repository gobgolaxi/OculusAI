package me.everyone.oculusai.detection.crystal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/** Кристалльные события игроков - в их {@link CrystalTimeline}.
 *
 * <p>Через события Bukkit, а не пакеты: так одинаково на 1.16.5 и 1.21 и без
 * разбора пакета постановки, который между версиями отличается. Чего так не
 * увидеть - удара по кристаллу, которого клиент ещё не мог видеть, - то
 * будет отдельной проверкой на пакетах.
 *
 * <p>Урон от взрыва записывается тому, кто разбил кристалл: по другим - как
 * нанесённый, по себе - как полученный. Кто разбил, помнится по кристаллу
 * несколько секунд - взрыв приходит сразу после удара.
 */
public final class Crystals implements Service, Listener {

    private static final long BREAKER_MS = 5_000L;
    private static final int BREAKER_LIMIT = 4_096;

    private final OculusAI plugin;
    /** Кристалл -> (кто разбил, когда). */
    private final Map<UUID, Breaker> breakers = new ConcurrentHashMap<>();

    private record Breaker(UUID player, long at) {
    }

    public Crystals(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        breakers.clear();
    }

    private CrystalTimeline timeline(Player player) {
        PlayerData data = plugin.data().get(player);
        return data == null ? null : data.crystal();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        Block block = event.getClickedBlock();
        if (item == null || item.getType() != Material.END_CRYSTAL || block == null) {
            return;
        }
        if (block.getType() != Material.OBSIDIAN && block.getType() != Material.BEDROCK) {
            return;
        }
        CrystalTimeline timeline = timeline(event.getPlayer());
        if (timeline == null) {
            return;
        }
        Location point = block.getLocation().add(0.5D, 1.0D, 0.5D);
        timeline.placed(angle(event.getPlayer(), point), distance(event.getPlayer(), point));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (victim instanceof EnderCrystal crystal) {
            Player breaker = playerOf(event.getDamager());
            if (breaker == null) {
                return;
            }
            remember(crystal.getUniqueId(), breaker.getUniqueId());
            CrystalTimeline timeline = timeline(breaker);
            if (timeline != null) {
                // Центр кристалла - на блок выше его основания.
                Location centre = crystal.getLocation().add(0.0D, 1.0D, 0.0D);
                timeline.broken(angle(breaker, centre), distance(breaker, centre));
            }
            return;
        }
        if (event.getDamager() instanceof EnderCrystal crystal && victim instanceof Player hurt) {
            Breaker breaker = breakers.get(crystal.getUniqueId());
            if (breaker == null || System.currentTimeMillis() - breaker.at() > BREAKER_MS) {
                return;
            }
            Player owner = plugin.getServer().getPlayer(breaker.player());
            if (owner == null) {
                return;
            }
            CrystalTimeline timeline = timeline(owner);
            if (timeline == null) {
                return;
            }
            if (owner.getUniqueId().equals(hurt.getUniqueId())) {
                timeline.damageTaken(event.getFinalDamage());
            } else {
                timeline.damageDealt(event.getFinalDamage());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSlot(PlayerItemHeldEvent event) {
        CrystalTimeline timeline = timeline(event.getPlayer());
        if (timeline != null) {
            timeline.slotSwitched();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotem(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player) {
            CrystalTimeline timeline = timeline(player);
            if (timeline != null) {
                timeline.totemPopped();
            }
        }
    }

    private void remember(UUID crystal, UUID player) {
        long now = System.currentTimeMillis();
        if (breakers.size() > BREAKER_LIMIT) {
            breakers.values().removeIf(entry -> now - entry.at() > BREAKER_MS);
        }
        breakers.put(crystal, new Breaker(player, now));
    }

    private static Player playerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    /** Угол между взглядом и направлением на точку, градусы. */
    static double angle(Player player, Location point) {
        Location eye = player.getEyeLocation();
        Vector to = point.toVector().subtract(eye.toVector());
        double length = to.length();
        if (length < 0.1D) {
            return Double.NaN;
        }
        Vector look = eye.getDirection();
        double dot = to.dot(look) / (length * look.length());
        return Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, dot))));
    }

    static double distance(Player player, Location point) {
        return player.getEyeLocation().toVector().distance(point.toVector());
    }
}
