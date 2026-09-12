package me.everyone.yuppyai.gui;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.manager.JournalManager;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class JournalMenu extends Menu {

    private static final int SLOT_REFRESH = 49;

    private final List<JournalManager.Entry> shown = new ArrayList<>();

    public JournalMenu(YuppyAI plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return plugin.theme().title(plugin.lang().text("journal.title"));
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void build() {
        shown.clear();
        List<JournalManager.Entry> entries = plugin.journal().entries();

        int slot = 0;
        for (JournalManager.Entry entry : entries) {
            if (slot >= 45) {
                break;
            }
            shown.add(entry);
            OfflinePlayer player = Bukkit.getOfflinePlayer(entry.uuid());
            set(slot++, head(player, name(entry), lore(entry)));
        }

        set(SLOT_REFRESH, item(Material.CLOCK, plugin.lang().text("journal.refresh.name"),
                List.of(plugin.lang().text("journal.refresh.lore"))));
    }

    private String name(JournalManager.Entry entry) {
        double fraction = entry.peakBuffer() / plugin.config().bufferMax();
        return Msg.parse("<" + Msg.heatColour(fraction) + ">" + entry.name());
    }

    private List<String> lore(JournalManager.Entry entry) {
        List<String> lore = new ArrayList<>();
        Player online = Bukkit.getPlayer(entry.uuid());
        lore.add(plugin.lang().text(online != null && online.isOnline()
                ? "journal.status.online"
                : "journal.status.offline"));
        lore.add(plugin.lang().text("journal.entry.last-buffer",
                Map.of("value", Msg.round(entry.lastBuffer(), 1))));
        lore.add(plugin.lang().text("journal.entry.peak-buffer",
                Map.of("value", Msg.round(entry.peakBuffer(), 1))));
        lore.add(plugin.lang().text("journal.entry.last-prob",
                Map.of("value", Msg.percent(entry.lastProbability()))));
        lore.add(plugin.lang().text("journal.entry.windows",
                Map.of("value", Integer.toString(entry.windowsAnalysed()))));
        lore.add(plugin.lang().text("journal.entry.first-seen",
                Map.of("value", ago(entry.firstSeenAt()))));
        lore.add(plugin.lang().text("journal.entry.last-seen",
                Map.of("value", ago(entry.lastSeenAt()))));
        lore.add("");
        lore.add(plugin.lang().text("journal.entry.history"));
        for (JournalManager.Snapshot snapshot : entry.history()) {
            lore.add(plugin.lang().text("journal.entry.history-line", Map.of(
                    "age", ago(snapshot.capturedAt()),
                    "probability", Msg.percent(snapshot.probability()),
                    "buffer", Msg.round(snapshot.buffer(), 1))));
        }
        lore.add("");
        if (online != null && online.isOnline()) {
            lore.add(plugin.lang().text("journal.entry.observe"));
            lore.add(plugin.lang().text("journal.entry.observe-hint"));
        } else {
            lore.add(plugin.lang().text("journal.entry.offline"));
        }
        return lore;
    }

    private String ago(long timestamp) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        if (seconds < 60) {
            return plugin.lang().text("journal.time.seconds", Map.of("value", Long.toString(seconds)));
        }
        long minutes = seconds / 60L;
        if (minutes < 60) {
            return plugin.lang().text("journal.time.minutes", Map.of("value", Long.toString(minutes)));
        }
        long hours = minutes / 60L;
        return plugin.lang().text("journal.time.hours", Map.of("value", Long.toString(hours)));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (slot == SLOT_REFRESH) {
            refresh();
            return;
        }
        if (slot >= shown.size()) {
            return;
        }

        JournalManager.Entry entry = shown.get(slot);
        Player target = Bukkit.getPlayer(entry.uuid());
        if (target == null || !target.isOnline()) {
            viewer.sendMessage(plugin.config().prefix() + plugin.lang().text("journal.player-offline"));
            refresh();
            return;
        }

        plugin.journal().observe(viewer, entry.uuid());
        viewer.closeInventory();
        viewer.sendMessage(plugin.config().prefix() + plugin.lang().text("journal.observe-started",
                Map.of("player", target.getName())));
    }
}
