package me.everyone.yuppyai.manager;

import com.google.gson.JsonObject;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.gui.DashboardMenu;
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

    public MenuManager(YuppyAI plugin) {
        this.plugin = plugin;
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
