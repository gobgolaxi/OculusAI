package me.everyone.oculusai.ui;


import me.everyone.oculusai.core.Service;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.storage.HistorySort;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.ui.menu.DashboardMenu;
import me.everyone.oculusai.ui.menu.HistoryMenu;
import me.everyone.oculusai.ui.menu.JournalMenu;
import me.everyone.oculusai.ui.menu.Menu;
import me.everyone.oculusai.ui.menu.SessionsMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

public final class Menus implements Service, Listener {

    private final OculusAI plugin;
    private volatile JsonObject lastStatus;
    private volatile JsonObject lastSessions;
    private final Map<UUID, HistorySort> historySorts = new ConcurrentHashMap<>();

    public Menus(OculusAI plugin) {
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
        plugin.mainThread().run(
                () -> new DashboardMenu(plugin, player).open());
    }

    public void refreshStatus(Player player, Menu menu) {
        plugin.datasets().status().thenAccept(status -> {
            lastStatus = status;
            plugin.mainThread().run( menu::refresh);
        });
    }

    public void openSessions(Player player) {
        plugin.api().sessions().thenAccept(sessions -> {
            lastSessions = sessions;
            plugin.mainThread().run(
                    () -> new SessionsMenu(plugin, player).open());
        });
    }

    public void refreshSessions(Player player, Menu menu) {
        plugin.api().sessions().thenAccept(sessions -> {
            lastSessions = sessions;
            plugin.mainThread().run( menu::refresh);
        });
    }

    public void openJournal(Player player) {
        plugin.mainThread().run(
                () -> new JournalMenu(plugin, player).open());
    }

    public void openHistory(Player viewer, PlayerData target) {
        openHistory(viewer, target.uuid(), target.name());
    }

    public void openHistory(Player viewer, UUID targetId, String targetName) {
        plugin.mainThread().run(
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
