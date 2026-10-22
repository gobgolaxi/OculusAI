package me.everyone.oculusai.punishment;


import me.everyone.oculusai.core.Service;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.util.Msg;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Executes the configured punishment after a confirmed detection.
 *
 * <p>There is deliberately no recording window here. A separate capture made
 * the plugin keep recording a suspect after the decision had already been
 * made, delayed the punishment, and produced a second data path whose label
 * could disagree with the alert. The dataset tools in {@code /oai data} are
 * the only way to record training windows now. */
public final class Punishments implements Service {

    private final OculusAI plugin;
    private final Map<UUID, Long> lastPunished = new ConcurrentHashMap<>();

    public Punishments(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void disable() {
        lastPunished.clear();
    }

    public void handle(PlayerData data) {
        if (!plugin.config().analysisEnabled()
                || !plugin.config().punishmentEnabled()
                || !data.player().isOnline()
                || plugin.bypass().isBypassed(data.player())) {
            return;
        }
        // A capture that ends in a kick is a capture cut short: the windows
        // after the threshold are the ones worth having, and the recorded
        // player is on the roster on purpose - they are meant to be cheating.
        // Analysis, readings and alerts carry on; only the punishment waits
        // until the recording is committed.
        if (data.recording() && plugin.config().punishmentSkipWhileRecording()) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = plugin.config().punishmentCooldownMs();
        Long previous = lastPunished.get(data.uuid());
        if (previous != null && now - previous < cooldown) {
            return;
        }
        lastPunished.put(data.uuid(), now);
        execute(data);
    }

    public void forget(UUID uuid) {
        lastPunished.remove(uuid);
    }

    private void execute(PlayerData data) {
        Player player = data.player();
        if (!player.isOnline()) {
            return;
        }
        plugin.history().note(data.uuid(), data.name(), "punish",
                "buffer " + Msg.round(data.buffer(), 1) + ", p " + Msg.percent(data.probability()));

        // The note above is already counted, so this includes the punishment
        // being handed out now: ban-after 3 bans on the third one, not the
        // fourth. The tally is the history's own, which means the count
        // survives a restart - and also that it lives no longer than
        // history.retention-days, which is the right lifetime for it anyway.
        int punishments = plugin.history().tally(data.uuid()).punishments();
        int banAfter = plugin.config().punishmentBanAfter();
        boolean ban = banAfter > 0 && punishments >= banAfter;

        Map<String, String> placeholders = Map.of(
                "player", data.name(),
                "probability", Msg.percent(data.probability()),
                "buffer", Msg.round(data.buffer(), 1),
                "punishments", Integer.toString(punishments));

        String command = Msg.fill(plugin.config().punishmentCommand(), placeholders).trim();
        if (ban) {
            String configured = Msg.fill(plugin.config().punishmentBanCommand(), placeholders).trim();
            boolean vanilla = configured.isBlank();
            // Minecraft's own ban, which also kicks. Dispatched rather than
            // written through BanList because the two jars compile against
            // APIs whose ban types differ, and because a server running a ban
            // plugin has it on /ban - taking that over is what an operator who
            // installed one expects.
            String banCommand = vanilla ? "ban " + data.name() + " Cheat detected" : configured;
            boolean strip = vanilla && plugin.config().punishmentStrip();
            if (strip) {
                // The ban is the kick escalated, so it strips for the same
                // reason the kick does.
                stripThenDisconnect(player, () -> run(banCommand));
            } else {
                if (vanilla) {
                    plugin.platform().onEntity(player,
                            () -> Animations.play(player, plugin.config().punishmentAnimation()));
                }
                run(banCommand);
            }
            plugin.getLogger().info((strip ? "Stripped, killed and banned " : "Banned ")
                    + data.name() + " after " + punishments + " punishment(s): " + banCommand);
        } else if (command.isBlank()) {
            // On Folia a player may only be touched from the thread that owns
            // them, and detection runs on the global one.
            boolean strip = plugin.config().punishmentStrip();
            if (strip) {
                stripThenDisconnect(player,
                        () -> plugin.platform().onEntity(player, () -> kick(player)));
            } else {
                plugin.platform().onEntity(player, () -> {
                    Animations.play(player, plugin.config().punishmentAnimation());
                    kick(player);
                });
            }
            plugin.getLogger().info((strip ? "Stripped, killed and kicked " : "Kicked ")
                    + data.name());
        } else {
            run(command);
            plugin.getLogger().info("Executed punishment command for " + data.name()
                    + ": " + command);
        }

        String broadcast = plugin.config().punishmentBroadcast();
        if (!broadcast.isBlank()) {
            plugin.alerts().announce(Msg.parse(plugin.config().prefix() + broadcast, placeholders));
        }

        plugin.api().reportKick(data.name(), data.uuid().toString(),
                data.probability(), data.buffer(),
                ban ? "ban" : command.isBlank() ? "kick" : "command");
    }

    /** Runs a configured command from the console, with or without the slash
     *  the operator may have typed. */
    private void run(String command) {
        String withoutSlash = command.startsWith("/") ? command.substring(1) : command;
        if (!withoutSlash.isBlank()) {
            plugin.getServer().dispatchCommand(
                    plugin.getServer().getConsoleSender(), withoutSlash);
        }
    }

    /** Empties the player onto the ground, kills them, and only then lets
     *  them be disconnected.
     *
     *  <p>The disconnect waits a tick on purpose. Killing and kicking in the
     *  same tick means the player leaves while the server is still working
     *  through the death: the death message, the statistic and the respawn
     *  state are all decided after {@code setHealth} returns, and a session
     *  that ends inside that window can save the player as they were before
     *  it. A tick is cheap and makes the death a death.
     *
     *  <p>The strip runs on the thread owning the player; the disconnect is
     *  scheduled on the tick loop, which is the global thread on Folia, so
     *  the order holds on both servers. A disconnect that touches the player
     *  rather than dispatching a command has to find its way back onto the
     *  owning thread itself - the kick does, the ban command does not. */
    private void stripThenDisconnect(Player player, Runnable disconnect) {
        plugin.platform().onEntity(player, () -> {
            // Before the drop, so the effect is centred on a player who is
            // still standing there rather than on a pile of items.
            Animations.play(player, plugin.config().punishmentAnimation());
            dropEverything(player);
            player.setHealth(0.0D);
        });
        plugin.ticks().after(1L, disconnect);
    }

    /** Disconnects a player who may already have left on their own. */
    private void kick(Player player) {
        if (player.isOnline()) {
            player.kickPlayer(Msg.parse("Cheat detected"));
        }
    }

    /** Drops the whole inventory on the ground and empties it.
     *
     *  <p>The death alone is not enough: a server with keepInventory on would
     *  kill the cheater and hand them everything back on respawn, which is the
     *  opposite of the point. Dropping first and clearing after means the
     *  gamerule no longer decides what this punishment does.
     *
     *  <p>Must run on the thread owning the player. */
    private void dropEverything(Player player) {
        PlayerInventory inventory = player.getInventory();
        Location where = player.getLocation();
        World world = where.getWorld();
        if (world == null) {
            return;
        }
        for (ItemStack stack : inventory.getContents()) {
            dropOne(world, where, stack);
        }
        for (ItemStack stack : inventory.getArmorContents()) {
            dropOne(world, where, stack);
        }
        dropOne(world, where, inventory.getItemInOffHand());
        inventory.clear();
        inventory.setArmorContents(null);
        inventory.setItemInOffHand(null);
    }

    private void dropOne(World world, Location where, ItemStack stack) {
        if (stack != null && !stack.getType().isAir()) {
            world.dropItemNaturally(where, stack.clone());
        }
    }
}
