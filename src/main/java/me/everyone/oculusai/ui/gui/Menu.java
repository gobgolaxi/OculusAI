package me.everyone.oculusai.ui.gui;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Platform;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/** Base class for every inventory in the plugin - with an opening animation,
 *  a moving frame and sound.
 *
 * <p>A menu declares its title, size and contents in {@link #build()}. Simple
 * screens can keep using {@link #onClick(InventoryClickEvent)}; screens with
 * many controls register a button - an item plus a click action.
 *
 * <p>Как меню живёт после открытия:
 * <ol>
 *   <li><b>Раскрытие.</b> Инвентарь открывается пустым, столбцы появляются от
 *       центра к краям, на каждом шаге - нота чуть выше предыдущей, в конце -
 *       короткий звон.</li>
 *   <li><b>Рамка.</b> По пустым клеткам периметра бегут две цветные
 *       «змейки» навстречу друг другу; цвета - от темы плагина.</li>
 *   <li><b>Мерцание.</b> Кнопки, отмеченные {@link #pulse(int)}, переливаются
 *       блеском зачарования.</li>
 * </ol>
 *
 * <p>Работает и на 1.16.5, и на Folia: блеск - через зачарование со скрытой
 * строкой, а не через API 1.20.5; скрытая подсказка у стекла - через
 * отражение, если сервер её умеет; анимация идёт задачей на потоке самого
 * зрителя, как того требует Folia.
 */
public abstract class Menu implements InventoryHolder {

    /** What a click did, so a button can react differently to left and right. */
    public record Click(InventoryClickEvent event, boolean right, boolean shift) {
    }

    private record Button(ItemStack item, Consumer<Click> action) {
    }

    private static final int REVEAL_STEP_TICKS = 2;
    private static final int SNAKE_STEP_TICKS = 2;
    private static final int FLICKER_TICKS = 10;
    /** Сколько ждать, пока инвентарь откроется (на Folia - задачей позже). */
    private static final int OPEN_GRACE_TICKS = 40;
    private static final Method HIDE_TOOLTIP = hideTooltipMethod();
    private static final Enchantment GLOW = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));

    protected final OculusAI plugin;
    protected final Player viewer;
    private Inventory inventory;
    private final Map<Integer, Button> buttons = new HashMap<>();
    private final Map<Integer, ItemStack> content = new HashMap<>();
    private final Set<Integer> pulsing = new HashSet<>();
    private List<Integer> border = List.of();
    private Platform.Cancellable task;
    private boolean revealed;
    private boolean seenOpen;
    private int tick;

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
        border = perimeter(size() / 9);
        rebuild();
        revealed = false;
        seenOpen = false;
        tick = 0;
        // On Folia an inventory may only be opened from the thread owning the
        // viewer. A menu opened from a command is already there; one opened
        // from a timer is not.
        plugin.platform().onEntity(viewer, () -> {
            viewer.openInventory(inventory);
            viewer.playSound(viewer.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.5F, 1.4F);
            stop();
            task = plugin.platform().entityRepeating(viewer, 1L, 1L, this::animate);
        });
    }

    /** Пересобирает содержимое без повторной анимации раскрытия. */
    public final void refresh() {
        if (inventory == null) {
            return;
        }
        rebuild();
        if (revealed) {
            paintAll();
        }
    }

    private void rebuild() {
        content.clear();
        buttons.clear();
        pulsing.clear();
        build();
    }

    private void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    // ------------------------------------------------------------------ API

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
        if (inventory == null || slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        if (item == null) {
            content.remove(slot);
        } else {
            content.put(slot, item);
        }
        if (revealed) {
            inventory.setItem(slot, item);
        }
    }

    /** Кнопка будет мерцать блеском зачарования - для того, на что стоит
     *  обратить внимание. */
    protected final void pulse(int slot) {
        pulsing.add(slot);
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
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.4F, 1.6F);
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

    // ------------------------------------------------------------ анимация

    private void animate() {
        boolean open = viewer.isOnline() && viewer.getOpenInventory().getTopInventory() == inventory;
        if (open) {
            seenOpen = true;
        } else if (seenOpen || tick > OPEN_GRACE_TICKS || !viewer.isOnline()) {
            stop();
            return;
        }
        tick++;
        if (!open) {
            return;
        }
        if (!revealed) {
            reveal();
            return;
        }
        if (tick % SNAKE_STEP_TICKS == 0) {
            paintBorder();
        }
        if (!pulsing.isEmpty() && tick % FLICKER_TICKS == 0) {
            flicker();
        }
    }

    /** Раскрытие от центрального столбца наружу. */
    private void reveal() {
        if (tick % REVEAL_STEP_TICKS != 0) {
            return;
        }
        int step = tick / REVEAL_STEP_TICKS - 1;
        if (step > 4) {
            revealed = true;
            tick = 0;
            paintAll();
            viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7F, 1.6F);
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (Math.abs(slot % 9 - 4) == step) {
                ItemStack item = content.get(slot);
                inventory.setItem(slot, item != null ? item
                        : border.contains(slot) ? glass(Material.BLACK_STAINED_GLASS_PANE) : null);
            }
        }
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5F, 0.8F + step * 0.2F);
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.2F, 0.7F + step * 0.25F);
    }

    private void paintAll() {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = content.get(slot);
            if (item != null) {
                inventory.setItem(slot, item);
            } else if (!border.contains(slot)) {
                inventory.setItem(slot, null);
            }
        }
        paintBorder();
    }

    /** Две змейки по пустым клеткам периметра, навстречу друг другу. */
    private void paintBorder() {
        int n = border.size();
        if (n == 0) {
            return;
        }
        Material[] palette = palette();
        int head = (tick / SNAKE_STEP_TICKS) % n;
        int opposite = (head + n / 2) % n;
        for (int i = 0; i < n; i++) {
            int slot = border.get(i);
            if (content.containsKey(slot)) {
                continue;
            }
            int behind = Math.min((head - i + n) % n, (opposite - i + n) % n);
            Material glass = behind < palette.length ? palette[behind] : Material.BLACK_STAINED_GLASS_PANE;
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType() != glass) {
                inventory.setItem(slot, glass(glass));
            }
        }
    }

    /** Голова и хвост змейки - в цветах темы. */
    private Material[] palette() {
        String theme = plugin.theme() == null || plugin.theme().current() == null
                ? "aurora" : plugin.theme().current().id();
        return switch (theme) {
            case "ember" -> new Material[]{Material.RED_STAINED_GLASS_PANE,
                    Material.ORANGE_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE};
            case "ocean" -> new Material[]{Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                    Material.CYAN_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE};
            case "rose" -> new Material[]{Material.PINK_STAINED_GLASS_PANE,
                    Material.MAGENTA_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE};
            case "mint" -> new Material[]{Material.LIME_STAINED_GLASS_PANE,
                    Material.GREEN_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE};
            case "midnight" -> new Material[]{Material.BLUE_STAINED_GLASS_PANE,
                    Material.CYAN_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE};
            default -> new Material[]{Material.PURPLE_STAINED_GLASS_PANE,
                    Material.MAGENTA_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE};
        };
    }

    private void flicker() {
        if (GLOW == null) {
            return;
        }
        boolean on = (tick / FLICKER_TICKS) % 2 == 0;
        for (int slot : pulsing) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }
            if (on) {
                meta.addEnchant(GLOW, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            } else {
                meta.removeEnchant(GLOW);
            }
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }
    }

    /** Декоративное стекло без подсказки при наведении. */
    private static ItemStack glass(Material material) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (HIDE_TOOLTIP != null) {
            try {
                HIDE_TOOLTIP.invoke(meta, true);
            } catch (ReflectiveOperationException ignored) {
                meta.setDisplayName(" ");
            }
        } else {
            meta.setDisplayName(" ");
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static Method hideTooltipMethod() {
        try {
            return ItemMeta.class.getMethod("setHideTooltip", boolean.class);
        } catch (NoSuchMethodException olderServer) {
            return null;
        }
    }

    private static List<Integer> perimeter(int rows) {
        List<Integer> out = new ArrayList<>();
        if (rows < 3) {
            return out;
        }
        int last = rows - 1;
        for (int c = 0; c < 9; c++) {
            out.add(c);
        }
        for (int r = 1; r < last; r++) {
            out.add(r * 9 + 8);
        }
        for (int c = 8; c >= 0; c--) {
            out.add(last * 9 + c);
        }
        for (int r = last - 1; r >= 1; r--) {
            out.add(r * 9);
        }
        return out;
    }
}
