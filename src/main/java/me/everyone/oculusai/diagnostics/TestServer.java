package me.everyone.oculusai.diagnostics;


import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.core.TickLoop;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.UUID;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.storage.DatasetStore;
import me.everyone.oculusai.npc.NpcOptions;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

public final class TestServer implements Service, Listener {

    private final OculusAI plugin;

    private World world;
    private TickLoop.Handle scoreboardTask;
    private TickLoop.Handle modelTask;
    private me.everyone.oculusai.core.Platform.Cancellable mobSweep;
    /** So the "no scoreboards here" line is logged once rather than per
     *  player per refresh. */
    private final java.util.concurrent.atomic.AtomicBoolean scoreboardWarned =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** How far around the arena's spawn the mob sweep reaches on Folia. One
     *  region's worth; the arena is a flat square and nothing lives outside
     *  it. */
    private static final double PURGE_RADIUS = 96.0D;
    private volatile String modelLine = "loading...";

    public TestServer(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        if (!plugin.config().testServerEnabled()) {
            return;
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        world = flatWorld();
        if (world == null) {
            // Folia with no arena world present. Said once, plainly: this is a
            // thing the admin can fix in a minute, and silence here reads as
            // the arena being broken.
            plugin.getLogger().warning("The test arena is on but world \""
                    + plugin.config().testServerWorld() + "\" is not loaded, and Folia"
                    + " cannot create a world while running. Folia loads the world named"
                    + " by level-name in server.properties and its nether and end, and"
                    + " nothing else - a world folder sitting in the server directory is"
                    + " never picked up. So either make the arena that world"
                    + " (level-name=" + plugin.config().testServerWorld() + ", which is"
                    + " what a dedicated test box wants anyway), or set"
                    + " test-server.enabled: false.");
            return;
        }
        refreshModelLine();

        int ticks = plugin.config().testServerScoreboardTicks();
        scoreboardTask = plugin.ticks().every(ticks, this::refreshAll);

        long modelTicks = plugin.config().testServerModelRefreshSeconds() * 20L;
        modelTask = plugin.ticks().every(modelTicks, this::refreshModelLine);

        // The mobs are swept where they stand. On Paper that is the one thread
        // that owns the world; on Folia it is the arena's own region, and the
        // sweep is bounded to the ring around spawn that a region may legally
        // read. Spawning is cancelled outright anyway - this is for whatever
        // was already standing there.
        Location at = spawnLocation();
        plugin.platform().onRegion(at, () -> purge(at));
        mobSweep = plugin.platform().regionRepeating(at, 20L, 20L, () -> purge(at));
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
        if (mobSweep != null) {
            mobSweep.cancel();
            mobSweep = null;
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
        plugin.mainThread().run(() -> resetPlayer(player));
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
        // Asynchronous teleport on both servers: the arena is another world
        // than the one a player joins into, and on Folia a plain teleport
        // between them is refused outright.
        plugin.platform().teleport(player, spawnLocation(), () -> kitOut(player));
    }

    private void kitOut(Player player) {
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
                Integer.MAX_VALUE, REGEN_AMPLIFIER, true, false, false));
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
        if (plugin.platform().folia()) {
            // Folia refuses to make a world after startup: every region thread
            // would have to be told about it mid-tick. And it loads only
            // level-name plus that world's nether and end, so there is no
            // "put the folder there and it appears" either - the arena has to
            // *be* the server's world. The caller says so and leaves it off.
            return null;
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

    /** Clears mobs out of the arena. Runs on the thread that owns
     *  {@code around}, and reads no further than it may from there. */
    private void purge(Location around) {
        if (world == null || around == null) {
            return;
        }
        if (!plugin.platform().folia()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (!(entity instanceof Player)) {
                    entity.remove();
                }
            }
            return;
        }
        // One region's worth around spawn. Asking the world for every living
        // entity is a read across every region there is, which is exactly what
        // Folia refuses - and an arena is one small flat place anyway.
        for (org.bukkit.entity.Entity entity
                : world.getNearbyEntities(around, PURGE_RADIUS, PURGE_RADIUS, PURGE_RADIUS)) {
            if (entity instanceof LivingEntity && !(entity instanceof Player)) {
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


    public int spawnDummies(Player player, int count) {
        return spawnDummies(player, count, NpcOptions.DEFAULT);
    }

    public int spawnDummies(Player player, int count, NpcOptions options) {
        return plugin.npcs().spawn(player, count, options);
    }

    public void clearDummies() {
        plugin.npcs().clearAll();
    }

    public void removeDummy(UUID id) {
        plugin.npcs().remove(id);
    }

    public int dummyCount() {
        return plugin.npcs().count();
    }

    public boolean isDummy(UUID id) {
        return plugin.npcs().isNpc(id);
    }

    public NpcOptions optionsOf(UUID id) {
        return plugin.npcs().optionsOf(id);
    }

    public void updateOptions(UUID id, NpcOptions options) {
        plugin.npcs().updateOptions(id, options);
    }

    private void refreshAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            // Each player's effects and scoreboard are that player's own, so
            // each goes to the thread that owns them. On Paper that is this
            // thread and the work runs inline, exactly as before.
            plugin.platform().onEntity(player, () -> {
                if (!player.hasPotionEffect(PotionEffectType.REGENERATION)) {
                    applyEternalRegen(player);
                }
                applyScoreboard(player);
            });
        }
    }

    /** The arena's sidebar.
     *
     *  <p>Not on Folia: the whole scoreboard API is unsupported there, and
     *  {@code getScoreboardManager().getNewScoreboard()} throws rather than
     *  degrading - which it did, on every player, every five seconds, from
     *  inside the per-player refresh. Nothing is drawn instead: the action bar
     *  is where {@code /oai monitor} and the hologram readout already live,
     *  and the arena's sidebar is a convenience, not a finding. Said once in
     *  the log so an operator who misses it knows why. */
    private void applyScoreboard(Player player) {
        if (plugin.platform().folia()) {
            if (scoreboardWarned.compareAndSet(false, true)) {
                plugin.getLogger().info("Folia has no scoreboards, so the test arena runs"
                        + " without its sidebar. Everything else in the arena works;"
                        + " /oai prob and /oai monitor say the same things.");
            }
            return;
        }
        boolean recording = plugin.datasets().collecting();
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective("oai_test", "dummy",
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
        DatasetStore datasets = plugin.datasets();
        long elapsedSeconds = Math.max(0L,
                (System.currentTimeMillis() - datasets.collectingSinceMs()) / 1000L);

        DatasetStore.RosterEntry own = datasets.entry(viewer.getUniqueId());
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
            plugin.mainThread().run(() -> modelLine = display);
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
