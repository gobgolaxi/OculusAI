package me.everyone.yuppyai.manager;

import com.google.gson.JsonObject;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.gui.DummyMenu;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TestServerManager implements Manager, Listener, CommandExecutor {

    private static final int MAX_DUMMIES = 5;

    private final YuppyAI plugin;
    private final Map<UUID, DummyOptions> dummies = new ConcurrentHashMap<>();

    private World world;
    private BukkitTask scoreboardTask;
    private BukkitTask modelTask;
    private BukkitTask dummyTask;
    private volatile String modelLine = "loading...";

    public TestServerManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        if (!plugin.config().testServerEnabled()) {
            return;
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        world = flatWorld();
        purgeMobs();
        refreshModelLine();

        PluginCommand spawnCommand = plugin.getCommand("spawn");
        if (spawnCommand != null) {
            spawnCommand.setExecutor(this);
        }

        int ticks = plugin.config().testServerScoreboardTicks();
        scoreboardTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::refreshAll, ticks, ticks);

        long modelTicks = plugin.config().testServerModelRefreshSeconds() * 20L;
        modelTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::refreshModelLine, modelTicks, modelTicks);

        dummyTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, () -> {
                    purgeMobs();
                    retargetDummies();
                }, 20L, 20L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (world == null) {
            player.sendMessage(plugin.config().prefix() + plugin.lang().text("spawn.disabled"));
            return true;
        }
        player.teleport(spawnLocation());
        player.sendMessage(plugin.config().prefix() + plugin.lang().text("spawn.teleported"));
        return true;
    }

    @Override
    public void disable() {
        if (scoreboardTask != null) {
            scoreboardTask.cancel();
            scoreboardTask = null;
        }
        if (modelTask != null) {
            modelTask.cancel();
            modelTask = null;
        }
        if (dummyTask != null) {
            dummyTask.cancel();
            dummyTask = null;
        }
        clearDummies();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.config().testServerEnabled() || world == null) {
            return;
        }
        resetPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.config().testServerEnabled() || world == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!plugin.config().testServerEnabled() || world == null) {
            return;
        }
        event.setRespawnLocation(spawnLocation());
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> resetPlayer(player));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDummyInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking() || !dummies.containsKey(event.getRightClicked().getUniqueId())) {
            return;
        }
        if (!player.hasPermission("yuppyai.npc")) {
            return;
        }
        event.setCancelled(true);
        UUID target = event.getRightClicked().getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin,
                () -> new DummyMenu(plugin, player, target).open());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!plugin.config().testServerEnabled() || world == null
                || !event.getEntity().getWorld().equals(world)) {
            return;
        }
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
            event.setCancelled(true);
        }
    }

    private static final int REGEN_AMPLIFIER = 254;

    private void resetPlayer(Player player) {
        player.teleport(spawnLocation());
        player.setGameMode(GameMode.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setFireTicks(0);
        player.getInventory().clear();
        giveKit(player.getInventory());
        applyEternalRegen(player);
        applyScoreboard(player);
    }

    private void applyEternalRegen(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                PotionEffect.INFINITE_DURATION, REGEN_AMPLIFIER, true, false, false));
    }

    private Location spawnLocation() {
        return world.getSpawnLocation().clone().add(0.5D, 0.0D, 0.5D);
    }

    private World flatWorld() {
        String name = plugin.config().testServerWorld();
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            existing.setSpawnFlags(false, false);
            return existing;
        }

        World created = new WorldCreator(name)
                .type(WorldType.FLAT)
                .environment(World.Environment.NORMAL)
                .generateStructures(false)
                .createWorld();
        if (created != null) {
            created.setStorm(false);
            created.setThundering(false);
            created.setTime(6000L);
            created.setSpawnFlags(false, false);
        }
        return created;
    }

    private void purgeMobs() {
        if (world == null) {
            return;
        }
        for (LivingEntity entity : world.getLivingEntities()) {
            if (!(entity instanceof Player) && !dummies.containsKey(entity.getUniqueId())) {
                entity.remove();
            }
        }
    }

    private void giveKit(PlayerInventory inventory) {
        inventory.setHelmet(enchant(new ItemStack(Material.NETHERITE_HELMET)));
        inventory.setChestplate(enchant(new ItemStack(Material.NETHERITE_CHESTPLATE)));
        inventory.setLeggings(enchant(new ItemStack(Material.NETHERITE_LEGGINGS)));
        inventory.setBoots(enchant(new ItemStack(Material.NETHERITE_BOOTS)));

        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta swordMeta = sword.getItemMeta();
        swordMeta.addEnchant(enchantment("sharpness"), 5, true);
        swordMeta.addEnchant(enchantment("unbreaking"), 3, true);
        sword.setItemMeta(swordMeta);

        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta bowMeta = bow.getItemMeta();
        bowMeta.addEnchant(enchantment("power"), 5, true);
        bowMeta.addEnchant(enchantment("infinity"), 1, true);
        bowMeta.addEnchant(enchantment("unbreaking"), 3, true);
        bow.setItemMeta(bowMeta);

        inventory.addItem(
                sword,
                bow,
                new ItemStack(Material.ARROW, 64),
                new ItemStack(Material.OBSIDIAN, 64),
                new ItemStack(Material.ENDER_PEARL, 16),
                new ItemStack(Material.GOLDEN_APPLE, 8),
                new ItemStack(Material.GOLDEN_CARROT, 64));
    }

    private ItemStack enchant(ItemStack armor) {
        ItemMeta meta = armor.getItemMeta();
        meta.addEnchant(enchantment("protection"), 4, true);
        meta.addEnchant(enchantment("unbreaking"), 3, true);
        meta.setUnbreakable(true);
        armor.setItemMeta(meta);
        return armor;
    }

    private Enchantment enchantment(String key) {
        return Enchantment.getByKey(NamespacedKey.minecraft(key));
    }


    public record DummyOptions(double health, boolean stationary, boolean invulnerable) {
        public static final DummyOptions DEFAULT = new DummyOptions(-1.0D, false, true);
    }

    public int spawnDummies(Player player, int count) {
        return spawnDummies(player, count, DummyOptions.DEFAULT);
    }

    public int spawnDummies(Player player, int count, DummyOptions options) {
        if (world == null) {
            return 0;
        }
        int allowed = Math.min(Math.max(1, count), MAX_DUMMIES - dummies.size());
        for (int i = 0; i < allowed; i++) {
            Location spot = player.getLocation().clone()
                    .add(player.getLocation().getDirection().normalize().multiply(2.0D));
            spot.setWorld(world);
            Zombie zombie = world.spawn(spot, Zombie.class, dummy -> {
                dummy.setBaby(false);
                dummy.setRemoveWhenFarAway(false);
                dummy.setCanPickupItems(false);
                dummy.setInvulnerable(options.invulnerable());
                dummy.setAI(!options.stationary());
                if (options.health() > 0) {
                    dummy.setMaxHealth(options.health());
                    dummy.setHealth(options.health());
                }
                dummy.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                dummy.getEquipment().setHelmetDropChance(0.0F);
                if (!options.stationary()) {
                    dummy.setTarget(player);
                }
                dummy.setCustomName(Msg.parse("<red>Sparring Dummy"));
                dummy.setCustomNameVisible(true);
            });
            dummies.put(zombie.getUniqueId(), options);
        }
        return allowed;
    }

    public void clearDummies() {
        for (UUID id : dummies.keySet()) {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        dummies.clear();
    }

    public void removeDummy(UUID id) {
        org.bukkit.entity.Entity entity = Bukkit.getEntity(id);
        if (entity != null) {
            entity.remove();
        }
        dummies.remove(id);
    }

    public int dummyCount() {
        return dummies.size();
    }

    public boolean isDummy(UUID id) {
        return dummies.containsKey(id);
    }

    public DummyOptions optionsOf(UUID id) {
        return dummies.get(id);
    }

    public void updateOptions(UUID id, DummyOptions options) {
        if (!dummies.containsKey(id)) {
            return;
        }
        dummies.put(id, options);
        org.bukkit.entity.Entity entity = Bukkit.getEntity(id);
        if (!(entity instanceof Zombie dummy)) {
            return;
        }
        dummy.setInvulnerable(options.invulnerable());
        dummy.setAI(!options.stationary());
        if (options.health() > 0) {
            dummy.setMaxHealth(options.health());
            dummy.setHealth(Math.min(dummy.getHealth(), options.health()));
        }
        if (options.stationary()) {
            dummy.setTarget(null);
        }
    }

    private void retargetDummies() {
        if (world == null || dummies.isEmpty()) {
            return;
        }
        List<Player> players = world.getPlayers();
        dummies.keySet().removeIf(id -> Bukkit.getEntity(id) == null || Bukkit.getEntity(id).isDead());
        if (players.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, DummyOptions> entry : dummies.entrySet()) {
            if (entry.getValue().stationary()) {
                continue;
            }
            org.bukkit.entity.Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof Mob mob && mob.getTarget() == null) {
                mob.setTarget(nearest(mob.getLocation(), players));
            }
        }
    }

    private Player nearest(Location from, List<Player> players) {
        Player closest = players.get(0);
        double best = Double.MAX_VALUE;
        for (Player candidate : players) {
            double distance = candidate.getLocation().distanceSquared(from);
            if (distance < best) {
                best = distance;
                closest = candidate;
            }
        }
        return closest;
    }


    private void refreshAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.hasPotionEffect(PotionEffectType.REGENERATION)) {
                applyEternalRegen(player);
            }
            applyScoreboard(player);
        }
    }

    private void applyScoreboard(Player player) {
        boolean recording = plugin.datasets().collecting();
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective("yai_test", "dummy",
                plugin.theme().title(recording ? "Recording" : "Test Server"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        if (recording) {
            fillRecordingScoreboard(objective, player);
        } else {
            fillIdleScoreboard(objective);
        }

        player.setScoreboard(board);
    }

    private void fillIdleScoreboard(Objective objective) {
        int line = 5;
        set(objective, line--, " ");
        set(objective, line--, Msg.parse("<gray>Version <white>" + plugin.getDescription().getVersion()));
        set(objective, line--, Msg.parse("<gray>Model <white>" + modelLine));
        set(objective, line--, "  ");
        set(objective, line--, Msg.parse("<gray>Online <white>"
                + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers()));
    }

    private void fillRecordingScoreboard(Objective objective, Player viewer) {
        DatasetManager datasets = plugin.datasets();
        long elapsedSeconds = Math.max(0L,
                (System.currentTimeMillis() - datasets.collectingSinceMs()) / 1000L);

        DatasetManager.RosterEntry own = datasets.entry(viewer.getUniqueId());
        PlayerData ownData = own == null ? null : plugin.data().get(viewer.getUniqueId());
        String youLine = own == null
                ? "<gray>You <dark_gray>not signed up"
                : "<gray>You <white>" + (ownData == null ? 0 : ownData.recordedCount()) + " windows";

        int line = 6;
        set(objective, line--, " ");
        set(objective, line--, Msg.parse("<gray>Time <white>" + formatDuration(elapsedSeconds)));
        set(objective, line--, Msg.parse("<gray>Players <white>" + datasets.roster().size()));
        set(objective, line--, Msg.parse(youLine));
        set(objective, line--, Msg.parse("<gray>Total <white>" + datasets.totalRecorded() + " windows"));
        set(objective, line--, "  ");
    }

    private static String formatDuration(long totalSeconds) {
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return (minutes < 10 ? "0" : "") + minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    private void set(Objective objective, int score, String text) {
        Score entry = objective.getScore(text);
        entry.setScore(score);
    }

    private void refreshModelLine() {
        plugin.api().status().thenAccept(response -> {
            String display = abbreviate(response);
            plugin.getServer().getScheduler().runTask(plugin, () -> modelLine = display);
        });
    }

    private String abbreviate(JsonObject status) {
        if (status == null || !status.has("model")) {
            return "unavailable";
        }
        JsonObject model = status.getAsJsonObject("model");
        String name = text(model, "name");
        if (!name.isBlank()) {
            return name.trim();
        }
        String kind = text(model, "model_kind");
        if (!kind.isBlank()) {
            return kind.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        }
        return "none";
    }

    private String text(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsString()
                : "";
    }
}
