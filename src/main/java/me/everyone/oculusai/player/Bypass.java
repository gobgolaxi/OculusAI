package me.everyone.oculusai.player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Players the anticheat must not analyse, managed by /oai bypass.
 *
 * <p>The existing {@code oculusai.bypass} permission is a permanent grant and
 * cannot be removed without a permissions plugin. This service stores the
 * runtime exemption separately, so an admin can toggle one player without
 * touching the group permissions. UUIDs are stored because a later name change
 * must not make the exemption point at somebody else - or disappear. */
public final class Bypass implements Service {

    private static final String PERMISSION = "oculusai.bypass";

    /** What a toggle did, and whether it reached disk. */
    public record Toggle(boolean enabled, boolean saved) {
    }

    private final OculusAI plugin;
    private final Set<UUID> bypassed = ConcurrentHashMap.newKeySet();

    public Bypass(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        reload();
    }

    @Override
    public void disable() {
        bypassed.clear();
    }

    @Override
    public void reload() {
        bypassed.clear();
        File file = file();
        if (!file.isFile()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String value : config.getStringList("players")) {
            try {
                bypassed.add(UUID.fromString(value));
            } catch (IllegalArgumentException malformed) {
                plugin.getLogger().warning("Ignoring a malformed UUID in bypass.yml: " + value);
            }
        }
    }

    /** True when either the permanent permission or the command-managed list
     *  exempts this player. Detection and checks use only this method. */
    public boolean isBypassed(Player player) {
        return player != null
                && (player.hasPermission(PERMISSION) || bypassed.contains(player.getUniqueId()));
    }

    /** True only for the command-managed exemption, so /oai diag can tell the
     *  two reasons apart. */
    public boolean managed(UUID uuid) {
        return uuid != null && bypassed.contains(uuid);
    }

    /** Adds or removes the exemption and saves the new list before returning.
     *  The in-memory state is applied even when saving fails, because the
     *  admin asked for the player to be exempt now; the result says so and the
     *  command can warn that the change will not survive a restart. */
    public Toggle toggle(UUID uuid) {
        boolean enabled = !bypassed.contains(uuid);
        Set<UUID> updated = new HashSet<>(bypassed);
        if (enabled) {
            updated.add(uuid);
        } else {
            updated.remove(uuid);
        }

        boolean saved = save(updated);
        bypassed.clear();
        bypassed.addAll(updated);
        clearState(uuid);
        return new Toggle(enabled, saved);
    }

    /** Drops evidence gathered before the switch. Without this, a player who
     *  was already near the alert line could be alerted immediately after the
     *  admin turns their anticheat back on. */
    private void clearState(UUID uuid) {
        PlayerData data = plugin.data().get(uuid);
        if (data != null) {
            // Same clean slate as a death: no stale probability, buffer or
            // attack history can leak across the switch.
            data.resetAfterDeath();
        }
        plugin.alerts().forget(uuid);
        plugin.punishments().forget(uuid);
    }

    private boolean save(Set<UUID> values) {
        List<String> ids = new ArrayList<>(values.size());
        for (UUID uuid : values) {
            ids.add(uuid.toString());
        }
        Collections.sort(ids);

        StringBuilder content = new StringBuilder();
        content.append("# Players exempted from analysis by /oai bypass.\n");
        content.append("# UUIDs are used so a name change does not lose the exemption.\n");
        if (ids.isEmpty()) {
            content.append("players: []\n");
        } else {
            content.append("players:\n");
            for (String id : ids) {
                content.append("- ").append(id).append('\n');
            }
        }

        try {
            Files.writeString(file().toPath(), content.toString(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not save bypass.yml", exception);
            return false;
        }
    }

    private File file() {
        return new File(plugin.getDataFolder(), "bypass.yml");
    }
}
