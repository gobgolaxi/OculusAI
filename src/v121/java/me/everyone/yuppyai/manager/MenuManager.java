package me.everyone.yuppyai.manager;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.gui.DashboardMenu;
import me.everyone.yuppyai.gui.HistoryMenu;
import me.everyone.yuppyai.gui.JournalMenu;
import me.everyone.yuppyai.gui.Menu;
import me.everyone.yuppyai.gui.SessionsMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

public final class MenuManager implements Manager, Listener {

    private final YuppyAI plugin;
    private volatile JsonObject lastStatus;
    private volatile JsonObject lastSessions;
    private final Map<UUID, HistorySort> historySorts = new ConcurrentHashMap<>();

    public MenuManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void disable() {
        historySorts.clear();
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void openDashboard(Player player) {
        plugin.getServer().getScheduler().runTask(plugin,
                () -> new DashboardMenu(plugin, player).open());
    }

    public void refreshStatus(Player player, Menu menu) {
        plugin.datasets().status().thenAccept(status -> {
            lastStatus = status;
            plugin.getServer().getScheduler().runTask(plugin, menu::refresh);
        });
    }

    public void openSessions(Player player) {
        plugin.api().sessions().thenAccept(sessions -> {
            lastSessions = sessions;
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> new SessionsMenu(plugin, player).open());
        });
    }

    public void refreshSessions(Player player, Menu menu) {
        plugin.api().sessions().thenAccept(sessions -> {
            lastSessions = sessions;
            plugin.getServer().getScheduler().runTask(plugin, menu::refresh);
        });
    }

    public void openJournal(Player player) {
        plugin.getServer().getScheduler().runTask(plugin,
                () -> new JournalMenu(plugin, player).open());
    }

    public void openHistory(Player viewer, PlayerData target) {
        openHistory(viewer, target.uuid(), target.name());
    }

    public void openHistory(Player viewer, UUID targetId, String targetName) {
        plugin.getServer().getScheduler().runTask(plugin,
                () -> new HistoryMenu(plugin, viewer, targetId, targetName, 0).open());
    }

    public HistorySort historySort(UUID viewer) {
        return historySorts.getOrDefault(viewer, HistorySort.FRESHNESS);
    }

    public void setHistorySort(UUID viewer, HistorySort sort) {
        historySorts.put(viewer, sort);
    }

    public JsonObject lastStatus() {
        return lastStatus;
    }

    public JsonObject lastSessions() {
        return lastSessions;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        menu.onClick(event);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
