package me.everyone.yuppyai.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SessionsMenu extends Menu {

    private static final int PER_PAGE = 45;
    private static final int SLOT_PREVIOUS = 45;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_NEXT = 53;

    private final int page;
    private final List<JsonObject> shown = new ArrayList<>();

    public SessionsMenu(YuppyAI plugin, org.bukkit.entity.Player viewer) {
        this(plugin, viewer, 0);
    }

    public SessionsMenu(YuppyAI plugin, org.bukkit.entity.Player viewer, int page) {
        super(plugin, viewer);
        this.page = Math.max(0, page);
    }

    @Override
    protected String title() {
        return plugin.theme().title(plugin.lang().text("sessions.title"));
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void build() {
        shown.clear();
        List<JsonObject> sessions = sessions();

        int from = page * PER_PAGE;
        for (int i = from; i < Math.min(sessions.size(), from + PER_PAGE); i++) {
            JsonObject session = sessions.get(i);
            shown.add(session);
            set(i - from, item(icon(session), name(session), lore(session)));
        }

        if (sessions.isEmpty()) {
            set(22, item(Material.BARRIER, plugin.lang().text("sessions.empty.name"), List.of(
                    plugin.lang().text("sessions.empty.line1"),
                    plugin.lang().text("sessions.empty.line2"))));
        }
        if (page > 0) {
            set(SLOT_PREVIOUS, item(Material.ARROW, plugin.lang().text("sessions.previous"), null));
        }
        if (sessions.size() > from + PER_PAGE) {
            set(SLOT_NEXT, item(Material.ARROW, plugin.lang().text("sessions.next"), null));
        }
        set(SLOT_BACK, item(Material.ARROW, plugin.lang().text("shared.back"), null));
    }

    private List<JsonObject> sessions() {
        JsonObject payload = plugin.menus().lastSessions();
        List<JsonObject> result = new ArrayList<>();
        if (payload == null || !payload.has("sessions")) {
            return result;
        }
        JsonArray array = payload.getAsJsonArray("sessions");
        for (int i = array.size() - 1; i >= 0; i--) {
            result.add(array.get(i).getAsJsonObject());
        }
        return result;
    }

    private boolean enabled(JsonObject session) {
        return !session.has("enabled") || session.get("enabled").getAsBoolean();
    }

    private String source(JsonObject session) {
        return session.has("source") ? session.get("source").getAsString() : "manual";
    }

    private Material icon(JsonObject session) {
        if (!enabled(session)) {
            return "evidence".equals(source(session)) ? Material.WRITABLE_BOOK : Material.GRAY_DYE;
        }
        return "cheater".equals(session.get("label").getAsString())
                ? Material.REDSTONE : Material.EMERALD;
    }

    private String name(JsonObject session) {
        String label = session.get("label").getAsString();
        String colour = !enabled(session) ? "<dark_gray>"
                : ("cheater".equals(label) ? "<red>" : "<green>");
        return Msg.parse(colour + session.get("player").getAsString()
                + " <dark_gray>- <gray>" + label
                + ("evidence".equals(source(session)) ? " " + plugin.lang().text("sessions.auto-tag") : ""));
    }

    private List<String> lore(JsonObject session) {
        boolean enabled = enabled(session);
        String source = source(session);
        List<String> lore = new ArrayList<>();
        lore.add(plugin.lang().text("sessions.windows", Map.of("value", Integer.toString(session.get("samples").getAsInt()))));
        lore.add(plugin.lang().text("sessions.recorded", Map.of("value", session.get("recorded_at").getAsString())));
        String sourceText = switch (source) {
            case "evidence" -> plugin.lang().text("sessions.source.evidence");
            case "recovered" -> plugin.lang().text("sessions.source.recovered");
            default -> plugin.lang().text("sessions.source.manual");
        };
        lore.add(plugin.lang().text("sessions.source-line", Map.of("value", sourceText)));
        lore.add(plugin.lang().text("sessions.file", Map.of("value", session.get("id").getAsString() + ".csv")));
        lore.add("");
        lore.add(enabled
                ? plugin.lang().text("sessions.in-training")
                : plugin.lang().text("sessions.excluded"));
        if (!enabled && "evidence".equals(source)) {
            lore.add(plugin.lang().text("sessions.warning1"));
            lore.add(plugin.lang().text("sessions.warning2"));
        }
        lore.add("");
        lore.add(plugin.lang().text(enabled ? "sessions.click.exclude" : "sessions.click.include"));
        lore.add(plugin.lang().text("sessions.click.delete"));
        return lore;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (slot == SLOT_BACK) {
            new DashboardMenu(plugin, viewer).open();
            return;
        }
        if (slot == SLOT_PREVIOUS && page > 0) {
            new SessionsMenu(plugin, viewer, page - 1).open();
            return;
        }
        if (slot == SLOT_NEXT) {
            new SessionsMenu(plugin, viewer, page + 1).open();
            return;
        }
        if (slot >= shown.size() || !viewer.hasPermission("yuppyai.data")) {
            return;
        }

        JsonObject session = shown.get(slot);
        String id = session.get("id").getAsString();

        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            plugin.api().removeSession(id)
                    .thenAccept(response -> after(response, "sessions.deleted", Map.of("value", id)));
            return;
        }
        plugin.api().toggleSession(id)
                .thenAccept(response -> after(response, "sessions.updated", Map.of("value", id)));
    }

    private void after(JsonObject response, String key, Map<String, String> placeholders) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (response == null) {
                viewer.sendMessage(plugin.config().prefix() + plugin.lang().text("sessions.failed"));
                return;
            }
            viewer.sendMessage(plugin.config().prefix() + plugin.lang().text(key, placeholders));
            plugin.menus().refreshSessions(viewer, this);
        });
    }
}
