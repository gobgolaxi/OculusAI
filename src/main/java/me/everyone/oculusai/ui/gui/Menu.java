package me.everyone.oculusai.ui.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import me.everyone.oculusai.OculusAI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/** Base class for every inventory in the plugin.
 *
 * <p>A menu declares its title, size and contents in {@link #build()}. Simple
 * screens can keep using {@link #onClick(InventoryClickEvent)}; screens with
 * many controls can register a {@link Button}, which is just an item plus a
 * click action. That removes the long {@code switch (slot)} blocks that used
 * to make a new button a four-place change. */
public abstract class Menu implements InventoryHolder {

    /** What a click did, so a button can react differently to left and right. */
    public record Click(InventoryClickEvent event, boolean right, boolean shift) {
    }

    private record Button(ItemStack item, Consumer<Click> action) {
    }

    protected final OculusAI plugin;
    protected final Player viewer;
    private Inventory inventory;
    private final Map<Integer, Button> buttons = new HashMap<>();

    protected Menu(OculusAI plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    protected abstract String title();

    protected abstract int size();

    /** Fills the inventory. Called on open and on every refresh. */
    protected abstract void build();

    /** Fallback for menus that still switch on slots. */
    protected void onClick(InventoryClickEvent event) {
    }

    public final void open() {
        inventory = Bukkit.createInventory(this, size(), title());
        buttons.clear();
        build();
        viewer.openInventory(inventory);
    }

    public final void refresh() {
        if (inventory != null) {
            inventory.clear();
            buttons.clear();
            build();
        }
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    /** Places an item and gives it a left-click action. */
    protected final void button(int slot, ItemStack item, Runnable action) {
        button(slot, item, click -> action.run());
    }

    /** Places an item and gives it a left-click action; right-click can do
     *  something else (the dashboard's power switch uses this). */
    protected final void button(int slot, ItemStack item, Consumer<Click> action) {
        set(slot, item);
        if (slot >= 0 && slot < 54) {
            buttons.put(slot, new Button(item, action));
        }
    }

    /** Places an item that reacts only to a right click. */
    protected final void rightClick(int slot, ItemStack item, Runnable action) {
        button(slot, item, click -> {
            if (click.right()) {
                action.run();
            }
        });
    }

    /** A two-state button: left click toggles, right click opens settings if
     *  a handler is supplied. */
    protected final void toggle(int slot, ItemStack item, Runnable toggle, Runnable settings) {
        button(slot, item, click -> {
            if (click.right() && settings != null) {
                settings.run();
            } else if (!click.right()) {
                toggle.run();
            }
        });
    }

    protected final void set(int slot, ItemStack item) {
        if (inventory != null && slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    protected final void toggleButton(int slot, ItemStack item, String path, boolean fallback) {
        button(slot, item, () -> {
            plugin.getConfig().set(path, !plugin.getConfig().getBoolean(path, fallback));
            plugin.saveConfig();
            plugin.reloadEverything();
            refresh();
        });
    }

    public final void handleClick(InventoryClickEvent event) {
        Button button = buttons.get(event.getSlot());
        if (button != null) {
            button.action().accept(new Click(event,
                    event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT,
                    event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT));
            return;
        }
        onClick(event);
    }

    protected static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(new ArrayList<>(lore));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    protected static ItemStack head(OfflinePlayer owner, String name, List<String> lore) {
        ItemStack stack = item(Material.PLAYER_HEAD, name, lore);
        if (stack.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(owner);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
