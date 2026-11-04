package me.everyone.oculusai.ui.menu;

import me.everyone.oculusai.ui.gui.Menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.storage.HistoryStore;
import me.everyone.oculusai.storage.HistorySort;
import me.everyone.oculusai.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class HistoryMenu extends Menu {

    private static final int SLOT_TARGET = 4;
    private static final int SLOT_BACK = 0;
    private static final int FIRST_READING = 9;
    private static final int PER_PAGE = 36;

    private static final int SLOT_PREVIOUS = 45;
    private static final int SLOT_SORT = 48;
    private static final int SLOT_PAGES = 49;
    private static final int SLOT_NEXT = 53;

    private final UUID targetId;
    private final String targetName;
    private final int page;

    private PlayerData data;
    private HistorySort sort = HistorySort.FRESHNESS;
    private List<PlayerData.Reading> ordered = List.of();
    private int pages;

    public HistoryMenu(OculusAI plugin, Player viewer, PlayerData target) {
        this(plugin, viewer, target, 0);
    }

    public HistoryMenu(OculusAI plugin, Player viewer, PlayerData target, int page) {
        this(plugin, viewer, target.uuid(), target.name(), page);
    }

    public HistoryMenu(OculusAI plugin, Player viewer, UUID targetId, String targetName, int page) {
        super(plugin, viewer);
        this.targetId = targetId;
        this.targetName = targetName;
        this.page = Math.max(0, page);
    }

    @Override
    protected String title() {
        return plugin.theme().title(plugin.lang().text("history.title", Map.of("player", targetName)));
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void build() {
        data = plugin.data().get(targetId);
        sort = plugin.menus().historySort(viewer.getUniqueId());
        layout();

        if (data == null) {
            set(SLOT_TARGET, head(Bukkit.getOfflinePlayer(targetId), targetName, storedLore()));
            if (ordered.isEmpty()) {
                empty();
                controls(false);
                return;
            }
            page(true);
            return;
        }

        set(SLOT_TARGET, head(data.player(), targetName, targetLore()));

        if (ordered.isEmpty()) {
            empty();
            controls(false);
            return;
        }

        page(true);
    }

    private void page(boolean paginated) {
        int from = from();
        for (int i = from; i < Math.min(ordered.size(), from + PER_PAGE); i++) {
            PlayerData.Reading reading = ordered.get(i);
            set(FIRST_READING + i - from, item(paneOf(reading), name(reading), lore(reading)));
        }

        controls(paginated);
    }

    private List<String> storedLore() {
        List<String> lore = new ArrayList<>();
        lore.add(plugin.lang().text("history.status.offline"));
        lore.add(plugin.lang().text("history.target.peak",
                Map.of("value", Msg.round(plugin.history().peakBuffer(targetId), 1))));
        lore.add(plugin.lang().text("history.target.windows",
                Map.of("value", Integer.toString(plugin.history().windowsAnalysed(targetId)))));
        lore.add(plugin.lang().text("history.target.readings",
                Map.of("value", Integer.toString(ordered.size()))));
        // Счётчики за всё время. Они переживают перезапуск и не обрезаются
        // вместе с лентой событий, поэтому «алертов 240» здесь значит ровно
        // то, что написано, а не «столько влезло в ленту».
        HistoryStore.Tally tally = plugin.history().tally(targetId);
        lore.add(plugin.lang().text("history.target.peak-prob",
                Map.of("value", Msg.probability(tally.peakProbability()))));
        lore.add(plugin.lang().text("history.target.alerts",
                Map.of("value", Integer.toString(tally.alerts()))));
        if (tally.punishments() > 0) {
            lore.add(plugin.lang().text("history.target.punishments",
                    Map.of("value", Integer.toString(tally.punishments()))));
        }
        long first = plugin.history().firstSeenAt(targetId);
        if (first > 0L) {
            lore.add(plugin.lang().text("history.target.first-seen", Map.of("value", ago(first))));
        }
        long seen = plugin.history().lastSeenAt(targetId);
        if (seen > 0L) {
            lore.add(plugin.lang().text("history.target.last-seen", Map.of("value", ago(seen))));
        }
        List<HistoryStore.Event> events =
                plugin.history().events(targetId);
        if (!events.isEmpty()) {
            lore.add("");
            lore.add(plugin.lang().text("history.events"));
            int shown = 0;
            for (int i = events.size() - 1; i >= 0 && shown < 4; i--, shown++) {
                var event = events.get(i);
                lore.add(plugin.lang().text("history.event-line", Map.of(
                        "age", ago(event.at()),
                        "type", plugin.lang().text("history.event." + event.type()),
                        "detail", event.detail())));
            }
        }
        if (ordered.isEmpty()) {
            lore.add("");
            lore.add(plugin.lang().text("history.gone"));
        }
        lore.add("");
        lore.add(plugin.lang().text("shared.refresh"));
        return lore;
    }

    private void empty() {
        set(FIRST_READING + PER_PAGE / 2, item(Material.BARRIER, plugin.lang().text("history.empty.name"),
                List.of(
                        plugin.lang().text("history.empty.line1"),
                        plugin.lang().text("history.empty.line2"))));
    }

    private void controls(boolean paginated) {
        if (paginated && page > 0) {
            set(SLOT_PREVIOUS, item(Material.ARROW, plugin.lang().text("sessions.previous"), null));
        }
        if (paginated && page < pages - 1) {
            set(SLOT_NEXT, item(Material.ARROW, plugin.lang().text("sessions.next"), null));
        }

        set(SLOT_SORT, item(Material.COMPARATOR, plugin.lang().text("history.sort.name"), List.of(
                plugin.lang().text("history.sort.current", Map.of("value", plugin.lang().text(sort.langKey()))),
                "",
                plugin.lang().text("history.sort.freshness-hint"),
                plugin.lang().text("history.sort.suspicion-hint"),
                "",
                plugin.lang().text("history.sort.click"))));

        int shown = Math.min(page, Math.max(0, pages - 1)) + 1;
        int total = Math.max(1, pages);
        set(SLOT_PAGES, item(Material.PAPER, plugin.lang().text("history.page.name", Map.of(
                "page", Integer.toString(shown),
                "pages", Integer.toString(total))), List.of(
                plugin.lang().text("history.page.range", Map.of(
                        "from", Integer.toString(range()[0]),
                        "to", Integer.toString(range()[1]),
                        "total", Integer.toString(ordered.size()))),
                "",
                plugin.lang().text("shared.refresh"))));

        set(SLOT_BACK, item(Material.ARROW, plugin.lang().text("shared.back"), null));
    }

    private void layout() {
        List<PlayerData.Reading> readings = plugin.history().readings(targetId);
        if (readings.isEmpty()) {
            ordered = List.of();
            pages = 0;
            return;
        }

        Comparator<PlayerData.Reading> order = sort == HistorySort.FRESHNESS
                ? Comparator.comparingLong(PlayerData.Reading::capturedAt).reversed()
                : Comparator.comparingDouble(PlayerData.Reading::buffer).reversed()
                        .thenComparing(Comparator.comparingDouble(PlayerData.Reading::probability).reversed())
                        .thenComparing(Comparator.comparingLong(PlayerData.Reading::capturedAt).reversed());

        List<PlayerData.Reading> sorted = new ArrayList<>(readings);
        sorted.sort(order);
        ordered = List.copyOf(sorted);
        pages = (int) Math.ceil(ordered.size() / (double) PER_PAGE);
    }

    private int from() {
        return Math.min(page, Math.max(0, pages - 1)) * PER_PAGE;
    }

    private int[] range() {
        if (ordered.isEmpty()) {
            return new int[]{0, 0};
        }
        return new int[]{from() + 1, Math.min(ordered.size(), from() + PER_PAGE)};
    }

    private String name(PlayerData.Reading reading) {
        return Msg.parse("<" + detectColour(reading.probability()) + ">" + clock(reading.capturedAt()));
    }

    /** Две строки: показание с буфером и время. Цвет числа - по детекту. */
    private List<String> lore(PlayerData.Reading reading) {
        List<String> lore = new ArrayList<>();
        lore.add(plugin.lang().textOr("history.reading.summary",
                        "<white>Prob %probability% <dark_gray>| <white>Buffer %buffer%")
                .replace("%probability%", Msg.parse("<" + detectColour(reading.probability()) + ">"
                        + Msg.probability(reading.probability())))
                .replace("%buffer%", Msg.parse("<" + detectColour(reading.probability()) + ">"
                        + Msg.round(reading.buffer(), 1))));
        lore.add(plugin.lang().text("history.reading.time", Map.of("value", ago(reading.capturedAt()))));
        return lore;
    }

    /** Ступени цвета те же, что у стекла: ниже порога сомнения - зелёный,
     *  дальше жёлтый, оранжевый и красный от 0.9. */
    private String detectColour(double probability) {
        if (probability < plugin.config().suspicion()) {
            return "green";
        }
        if (probability < 0.7D) {
            return "yellow";
        }
        return probability < 0.9D ? "gold" : "red";
    }

    private Material paneOf(PlayerData.Reading reading) {
        double probability = reading.probability();
        if (probability < plugin.config().suspicion()) {
            return Material.LIME_STAINED_GLASS_PANE;
        }
        if (probability < 0.7D) {
            return Material.YELLOW_STAINED_GLASS_PANE;
        }
        return probability < 0.9D ? Material.ORANGE_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
    }

    private List<String> targetLore() {
        double max = plugin.config().bufferMax();
        List<PlayerData.Reading> readings = ordered;
        double peak = plugin.history().peakBuffer(data.uuid());
        for (PlayerData.Reading reading : readings) {
            if (reading.buffer() > peak) {
                peak = reading.buffer();
            }
        }

        List<String> lore = new ArrayList<>();
        lore.add(plugin.lang().text(data.player() != null && data.player().isOnline()
                ? "history.status.online" : "history.status.offline"));
        lore.add(plugin.lang().text("history.reading.buffer", Map.of(
                "value", Msg.round(data.buffer(), 1),
                "max", Msg.round(max, 0))));
        lore.add(Msg.parse(Msg.bar(data.buffer() / max, 12)));
        lore.add(plugin.lang().text("history.reading.probability",
                Map.of("value", Msg.probability(data.probability()))));
        lore.add(plugin.lang().text("history.target.peak", Map.of("value", Msg.round(peak, 1))));
        lore.add(plugin.lang().text("history.target.windows",
                Map.of("value", Integer.toString(data.windowsAnalysed()))));
        lore.add(plugin.lang().text("history.target.readings",
                Map.of("value", Integer.toString(readings.size()))));
        lore.add("");
        lore.add(plugin.lang().text("shared.refresh"));
        return lore;
    }

    private String clock(long timestamp) {
        return java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private String ago(long timestamp) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        if (seconds < 60) {
            return plugin.lang().text("history.time.seconds", Map.of("value", Long.toString(seconds)));
        }
        long minutes = seconds / 60L;
        if (minutes < 60) {
            return plugin.lang().text("history.time.minutes", Map.of("value", Long.toString(minutes)));
        }
        long hours = minutes / 60L;
        if (hours < 24) {
            return plugin.lang().text("history.time.hours", Map.of("value", Long.toString(hours)));
        }
        return plugin.lang().text("history.time.days", Map.of("value", Long.toString(hours / 24L)));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        switch (event.getSlot()) {
            case SLOT_PREVIOUS -> {
                if (page > 0) {
                    new HistoryMenu(plugin, viewer, targetId, targetName, page - 1).open();
                }
            }
            case SLOT_NEXT -> {
                if (page < pages - 1) {
                    new HistoryMenu(plugin, viewer, targetId, targetName, page + 1).open();
                }
            }
            case SLOT_SORT -> {
                plugin.menus().setHistorySort(viewer.getUniqueId(),
                        plugin.menus().historySort(viewer.getUniqueId()).next());
                new HistoryMenu(plugin, viewer, targetId, targetName, 0).open();
            }
            case SLOT_PAGES, SLOT_TARGET -> refresh();
            case SLOT_BACK -> new JournalMenu(plugin, viewer).open();
            default -> {
            }
        }
    }
}
