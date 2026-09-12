package me.everyone.yuppyai.manager;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.listener.BukkitListener;
import me.everyone.yuppyai.listener.ChatModerationListener;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DataManager implements Manager {

    private final YuppyAI plugin;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

    public DataManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(new BukkitListener(plugin), plugin);
        plugin.getServer().getPluginManager().registerEvents(new ChatModerationListener(plugin), plugin);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            add(player);
        }
    }

    @Override
    public void disable() {
        players.clear();
    }

    public PlayerData add(Player player) {
        return players.compute(player.getUniqueId(), (uuid, existing) -> {
            if (existing == null) {
                return new PlayerData(player);
            }
            existing.bind(player);
            return existing;
        });
    }

    public void remove(UUID uuid) {
        players.remove(uuid);
    }

    public PlayerData get(UUID uuid) {
        return players.get(uuid);
    }

    public PlayerData get(Player player) {
        return player == null ? null : players.get(player.getUniqueId());
    }

    public PlayerData byName(String name) {
        Player player = plugin.getServer().getPlayerExact(name);
        return player == null ? null : get(player);
    }

    public Collection<PlayerData> all() {
        return Collections.unmodifiableCollection(players.values());
    }

    public int size() {
        return players.size();
    }
}
