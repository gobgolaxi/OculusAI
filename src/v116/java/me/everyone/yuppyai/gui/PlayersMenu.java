package me.everyone.yuppyai.gui;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class PlayersMenu extends Menu {

    private static final int SLOT_BACK = 49;

    private final List<PlayerData> shown = new ArrayList<>();

    public PlayersMenu(YuppyAI plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return plugin.theme().title(plugin.lang().text("players.title"));
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void build() {
        shown.clear();
        List<PlayerData> sorted = new ArrayList<>(plugin.data().all());
        sorted.sort(Comparator.comparingDouble(PlayerData::buffer).reversed());

        int slot = 0;
        for (PlayerData data : sorted) {
            if (slot >= 45) {
                break;
            }
            shown.add(data);
            set(slot++, head(data.player(), name(data), lore(data)));
        }

        set(SLOT_BACK, item(Material.ARROW, plugin.lang().text("shared.back"), null));
    }

    private String name(PlayerData data) {
        double fraction = data.buffer() / plugin.config().bufferMax();
        return Msg.parse("<" + Msg.heatColour(fraction) + ">" + data.name());
    }

    private List<String> lore(PlayerData data) {
        double fraction = data.buffer() / plugin.config().bufferMax();
        List<String> lore = new ArrayList<>();
        lore.add(plugin.lang().text("players.buffer", Map.of(
                "value", Msg.round(data.buffer(), 1),
                "max", Msg.round(plugin.config().bufferMax(), 0))));
        lore.add(Msg.parse(Msg.bar(fraction, 12)));
        lore.add(plugin.lang().text("players.last-reading", Map.of("value", Msg.percent(data.probability()))));
        lore.add(plugin.lang().text("players.windows", Map.of("value", Integer.toString(data.windowsAnalysed()))));
        if (plugin.punishments().pending(data.uuid())) {
            lore.add(plugin.lang().text("players.evidence", Map.of(
                    "value", Long.toString(plugin.punishments().remainingSeconds(data.uuid())))));
            lore.add(plugin.lang().text("players.kept-dropped", Map.of(
                    "kept", Integer.toString(data.recordedCount()),
                    "dropped", Integer.toString(data.discardedCount()))));
        } else if (data.recording()) {
            lore.add(plugin.lang().text("players.recording", Map.of("value", Integer.toString(data.recordedCount()))));
        }
        if (data.windowsAnalysed() == 0) {
            lore.add("");
            lore.add(plugin.lang().text("players.only-fighting"));
        }
        lore.add("");
        lore.add(plugin.lang().text("players.click"));
        return lore;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (slot == SLOT_BACK) {
            new DashboardMenu(plugin, viewer).open();
            return;
        }
        if (slot < shown.size()) {
            PlayerData target = shown.get(slot);
            plugin.displays().watch(viewer, target);
            viewer.closeInventory();
            viewer.sendMessage(plugin.config().prefix()
                    + plugin.lang().text("players.watching", Map.of("player", target.name())));
        }
    }
}
