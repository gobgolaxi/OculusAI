package me.everyone.yuppyai.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.manager.HistoryManager;
import me.everyone.yuppyai.manager.HistorySort;
import me.everyone.yuppyai.util.Msg;
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

    private static final double MIN_STEP = 0.1D;

    private enum Level {
        ZERO,
        LOW,
        MID,
        HIGH
    }

    private final UUID targetId;
    private final String targetName;
    private final int page;

    private PlayerData data;
    private HistorySort sort = HistorySort.FRESHNESS;
    private List<PlayerData.Reading> ordered = List.of();
    private Map<Long, PlayerData.Reading> previous = Map.of();
    private int pages;

    public HistoryMenu(YuppyAI plugin, Player viewer, PlayerData target) {
        this(plugin, viewer, target, 0);
    }

    public HistoryMenu(YuppyAI plugin, Player viewer, PlayerData target, int page) {
        this(plugin, viewer, target.uuid(), target.name(), page);
    }

    public HistoryMenu(YuppyAI plugin, Player viewer, UUID targetId, String targetName, int page) {
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
            set(FIRST_READING + i - from, item(paneOf(level(reading.buffer())), name(reading), lore(reading)));
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
        long seen = plugin.history().lastSeenAt(targetId);
        if (seen > 0L) {
            lore.add(plugin.lang().text("history.target.last-seen", Map.of("value", ago(seen))));
        }
        List<HistoryManager.Event> events =
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
            previous = Map.of();
            pages = 0;
            return;
        }

        Map<Long, PlayerData.Reading> before = new HashMap<>();
        for (int i = 1; i < readings.size(); i++) {
            before.put(readings.get(i).capturedAt(), readings.get(i - 1));
        }
        previous = before;

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

    private boolean flagged(PlayerData.Reading reading) {
        return reading.probability() >= plugin.config().suspicion();
    }

    private Level level(double buffer) {
        if (buffer <= 0.0D) {
            return Level.ZERO;
        }
        if (buffer < alertStep()) {
            return Level.LOW;
        }
        if (buffer < punishStep()) {
            return Level.MID;
        }
        return Level.HIGH;
    }

    private double alertStep() {
        return Math.max(MIN_STEP, plugin.config().alertAt());
    }

    private double punishStep() {
        return Math.max(alertStep() + MIN_STEP, plugin.config().punishAt());
    }

    private Material paneOf(Level level) {
        return switch (level) {
            case ZERO -> Material.LIME_STAINED_GLASS_PANE;
            case LOW -> Material.YELLOW_STAINED_GLASS_PANE;
            case MID -> Material.ORANGE_STAINED_GLASS_PANE;
            case HIGH -> Material.RED_STAINED_GLASS_PANE;
        };
    }

    private Map<String, String> levelPlaceholders(Level level) {
        return switch (level) {
            case ZERO -> Map.of();
            case LOW -> Map.of("value", Msg.round(alertStep(), 1));
            case MID -> Map.of("value", Msg.round(alertStep(), 1),
                    "next", Msg.round(punishStep(), 1));
            case HIGH -> Map.of("value", Msg.round(punishStep(), 1),
                    "max", Msg.round(plugin.config().bufferMax(), 0));
        };
    }

    private String name(PlayerData.Reading reading) {
        return Msg.parse("<dark_gray>" + clock(reading.capturedAt()));
    }

    private List<String> lore(PlayerData.Reading reading) {
        double max = plugin.config().bufferMax();
        Level level = level(reading.buffer());
        PlayerData.Reading before = previous.get(reading.capturedAt());
        List<String> lore = new ArrayList<>();
        lore.add(plugin.lang().text("history.reading.time", Map.of("value", ago(reading.capturedAt()))));
        lore.add(plugin.lang().text("history.reading.probability",
                Map.of("value", Msg.percent(reading.probability()))));
        lore.add(plugin.lang().text("history.reading.buffer", Map.of(
                "value", Msg.round(reading.buffer(), 1),
                "max", Msg.round(max, 0))));
        lore.add(Msg.parse(Msg.bar(reading.buffer() / max, 12)));
        if (before != null) {
            double delta = reading.buffer() - before.buffer();
            String key = delta > 0.0001D ? "history.reading.rising"
                    : (delta < -0.0001D ? "history.reading.falling" : "history.reading.steady");
            lore.add(plugin.lang().text(key, Map.of("value", Msg.round(Math.abs(delta), 1))));
        }
        lore.add("");
        lore.add(plugin.lang().text("history.level." + level.name().toLowerCase(Locale.ROOT),
                levelPlaceholders(level)));
        if (flagged(reading)) {
            lore.add(plugin.lang().text("history.reading.flagged",
                    Map.of("value", Msg.percent(plugin.config().suspicion()))));
        }
        return lore;
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
                Map.of("value", Msg.percent(data.probability()))));
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
