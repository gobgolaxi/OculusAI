package me.everyone.yuppyai.gui;

import me.everyone.yuppyai.YuppyAI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public abstract class Menu implements InventoryHolder {

    protected final YuppyAI plugin;
    protected final Player viewer;
    private Inventory inventory;

    protected Menu(YuppyAI plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    protected abstract String title();

    protected abstract int size();

    protected abstract void build();

    public abstract void onClick(InventoryClickEvent event);

    public void open() {
        inventory = Bukkit.createInventory(this, size(), title());
        build();
        viewer.openInventory(inventory);
    }

    public void refresh() {
        if (inventory != null) {
            inventory.clear();
            build();
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    protected void set(int slot, ItemStack item) {
        if (inventory != null && slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    protected void fillBorder(ItemStack item) {
        int size = inventory.getSize();
        for (int slot = 0; slot < size; slot++) {
            boolean edge = slot < 9 || slot >= size - 9 || slot % 9 == 0 || slot % 9 == 8;
            if (edge) {
                set(slot, item);
            }
        }
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
