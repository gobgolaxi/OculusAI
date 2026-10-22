package me.everyone.oculusai.punishment;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** How many punishments each player has collected towards a ban.
 *
 * <p>Deliberately not the history's own tally, which counts every punishment
 * the player ever had. Two things make that the wrong number here.
 *
 * <p>The first is that switching the feature on must not reach backwards. An
 * operator who sets {@code ban-after: 3} on a server that has been running for
 * months is saying "three more", not "ban everybody who already has three" -
 * and the history-backed version would have banned a long list of people on
 * the next punishment each of them collected.
 *
 * <p>The second is the unban. A player the anticheat banned and an admin then
 * let back in has been forgiven, and an admin who forgives somebody does not
 * mean "until your next punishment". Their count starts again from zero. The
 * unban itself is never seen by the plugin, so it is inferred from the only
 * thing that proves it: they logged in, which a banned player cannot do. */
public final class BanCounter implements Service {

    private final OculusAI plugin;
    private final Map<UUID, Integer> counts = new ConcurrentHashMap<>();
    private final Set<UUID> bannedByUs = ConcurrentHashMap.newKeySet();

    public BanCounter(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        reload();
    }

    @Override
    public void disable() {
        counts.clear();
        bannedByUs.clear();
    }

    @Override
    public void reload() {
        counts.clear();
        bannedByUs.clear();
        File file = file();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration stored = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = stored.getConfigurationSection("counts");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                UUID uuid = parse(key);
                if (uuid != null) {
                    counts.put(uuid, Math.max(0, section.getInt(key)));
                }
            }
        }
        for (String key : stored.getStringList("banned")) {
            UUID uuid = parse(key);
            if (uuid != null) {
                bannedByUs.add(uuid);
            }
        }
    }

    /** Counts one punishment and returns the new total. */
    public int record(UUID uuid) {
        int next = counts.merge(uuid, 1, Integer::sum);
        save();
        return next;
    }

    /** What this player stands at without counting anything. */
    public int count(UUID uuid) {
        return counts.getOrDefault(uuid, 0);
    }

    /** Remembers that the anticheat banned this player, and clears the count
     *  that got them there: whatever happens next is a fresh start, whether
     *  that is an unban today or in a year. */
    public void banned(UUID uuid) {
        counts.remove(uuid);
        bannedByUs.add(uuid);
        save();
    }

    /** Called when a player logs in.
     *
     *  <p>A banned player cannot reach this, so arriving here while still
     *  flagged means somebody lifted the ban. The flag is dropped and the
     *  player starts clean - the count was already cleared when they were
     *  banned, so this only has to stop them being treated as a returning
     *  convict. */
    public void seen(UUID uuid) {
        if (bannedByUs.remove(uuid)) {
            counts.remove(uuid);
            save();
            plugin.getLogger().info("Ban lifted for " + uuid
                    + " - their punishment count starts again.");
        }
    }

    /** True while the anticheat's own ban on this player stands. */
    public boolean bannedByUs(UUID uuid) {
        return bannedByUs.contains(uuid);
    }

    /** Wipes one player, for /oai forget. */
    public void forget(UUID uuid) {
        boolean changed = counts.remove(uuid) != null;
        changed |= bannedByUs.remove(uuid);
        if (changed) {
            save();
        }
    }

    private UUID parse(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            plugin.getLogger().warning("Ignoring a malformed UUID in bans.yml: " + value);
            return null;
        }
    }

    private void save() {
        YamlConfiguration out = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> entry : counts.entrySet()) {
            out.set("counts." + entry.getKey(), entry.getValue());
        }
        out.set("banned", bannedByUs.stream().map(UUID::toString).toList());
        try {
            out.save(file());
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not save bans.yml: the ban counts will reset after a restart.",
                    failure);
        }
    }

    private File file() {
        return new File(plugin.getDataFolder(), "bans.yml");
    }
}
