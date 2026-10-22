package me.everyone.oculusai.punishment;


import me.everyone.oculusai.core.Platform;
import me.everyone.oculusai.core.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
    /** Players whose send-off is playing right now.
     *
     *  <p>{@code PacketBridge} drops everything these send while they are in
     *  here, which is what "they cannot do anything" means in practice: a
     *  cancelled attack never lands, and a cancelled look or position never
     *  reaches the server, so the cheater is frozen where the show put them.
     *  The client will still swing and turn on its own screen - no server can
     *  prevent that - but nothing it does from here counts. */
    private final Set<UUID> held = ConcurrentHashMap.newKeySet();

    public Punishments(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void disable() {
        lastPunished.clear();
        held.clear();
    }

    /** True while this player's send-off is playing. */
    public boolean isHeld(UUID uuid) {
        return held.contains(uuid);
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
        // A player who left in the middle of their own send-off must not stay
        // held: the id would be reused by their next session.
        held.remove(uuid);
    }

    private void execute(PlayerData data) {
        Player player = data.player();
        if (!player.isOnline()) {
            return;
        }
        plugin.history().note(data.uuid(), data.name(), "punish",
                "buffer " + Msg.round(data.buffer(), 1) + ", p " + Msg.percent(data.probability()));

        // Counted only while banning is switched on, and counted by
        // BanCounter rather than by the history: turning ban-after on must
        // not reach backwards over punishments collected before the operator
        // asked for any of this.
        int banAfter = plugin.config().punishmentBanAfter();
        int punishments = banAfter > 0
                ? plugin.banCounter().record(data.uuid())
                : plugin.banCounter().count(data.uuid());
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
            // The ban is the kick escalated, so it gets the same send-off -
            // but only when the plugin is the one banning. A named command
            // owns the punishment, show included.
            boolean strip = vanilla && plugin.config().punishmentStrip();
            if (vanilla) {
                show(player, strip, () -> run(banCommand));
            } else {
                run(banCommand);
            }
            plugin.banCounter().banned(data.uuid());
            plugin.getLogger().info((strip ? "Stripped, killed and banned " : "Banned ")
                    + data.name() + " after " + punishments + " punishment(s): " + banCommand);
        } else if (command.isBlank()) {
            boolean strip = plugin.config().punishmentStrip();
            show(player, strip, () -> plugin.platform().onEntity(player, () -> kick(player)));
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

    /** Runs a show on a player: the throw, the frames, the closing burst.
     *
     *  <p>Holds nobody, strips nobody, disconnects nobody - those belong to
     *  the caller, which is why {@code /oai animations} can borrow this to
     *  preview an effect on an admin without any of the rest happening to
     *  them.
     *
     *  @param onStart runs on the owning thread right after the throw
     *  @param atEnd   runs on the tick loop once the show is over */
    public void playShow(Player player, String chosen, Runnable onStart, Runnable atEnd) {
        int total = Animations.ticks(chosen);
        if (total <= 0) {
            if (onStart != null) {
                plugin.platform().onEntity(player, onStart);
            }
            plugin.ticks().after(1L, atEnd);
            return;
        }

        plugin.platform().onEntity(player, () -> {
            Animations.start(player, chosen);
            if (onStart != null) {
                onStart.run();
            }
        });

        AtomicInteger tick = new AtomicInteger();
        Platform.Cancellable frames = plugin.platform().entityRepeating(player, 1L, 1L,
                () -> Animations.frame(player, chosen, tick.incrementAndGet(), total));

        plugin.ticks().after(total, () -> {
            frames.cancel();
            plugin.platform().onEntity(player, () -> {
                if (player.isOnline()) {
                    Location where = player.getLocation();
                    Animations.finish(where.getWorld(), where, chosen);
                }
            });
            atEnd.run();
        });
    }

    /** Plays the send-off, then disconnects.
     *
     *  <p>The order is the whole point. The cheater is thrown up and frozen,
     *  their inventory comes out of them on the way, the show runs its span,
     *  and only at the end are they killed and disconnected. Before this the
     *  kick went first and the effect second, which meant the effect played
     *  over an empty spot and the punished player never saw any of it - they
     *  were already back at the server list.
     *
     *  <p>The disconnect still waits a tick after the kill. The death
     *  message, the statistic and the respawn state are all decided after
     *  {@code setHealth} returns, and a session that ends inside that window
     *  can save the player as they were before it.
     *
     *  <p>Frames run on the thread owning the player; the hand-offs go
     *  through the tick loop, which is the global thread on Folia, so the
     *  order holds on both servers. A disconnect that touches the player
     *  rather than dispatching a command has to find its way back onto the
     *  owning thread itself - the kick does, the ban command does not. */
    private void show(Player player, boolean strip, Runnable disconnect) {
        String chosen = Animations.pick(plugin.config().punishmentAnimation());
        UUID uuid = player.getUniqueId();
        if (Animations.ticks(chosen) > 0) {
            held.add(uuid);
        }
        playShow(player, chosen,
                // On the way up, so the inventory is seen leaving them rather
                // than found in a heap afterwards.
                strip ? () -> dropEverything(player) : null,
                () -> {
                    held.remove(uuid);
                    plugin.platform().onEntity(player, () -> {
                        if (strip && player.isOnline()) {
                            // Harmless when the throw already emptied them,
                            // and the whole punishment when there was no show.
                            dropEverything(player);
                            player.setHealth(0.0D);
                        }
                    });
                    plugin.ticks().after(1L, disconnect);
                });
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
        // Gathered before anything is thrown, and deduplicated by identity.
        // A player inventory's getContents() is 41 slots - the main grid, the
        // armour and the off hand - so the armour and off hand also come back
        // from their own getters. Dropping both lists would have turned one
        // helmet on a cheater into two on the ground, which is a duplication
        // bug handed to the one person with the most reason to exploit it.
        // The getters are still read: it costs nothing and does not assume
        // that every server agrees on what getContents() covers.
        List<ItemStack> spilling = new ArrayList<>();
        collect(spilling, inventory.getContents());
        collect(spilling, inventory.getArmorContents());
        collect(spilling, new ItemStack[] {inventory.getItemInOffHand()});

        Location from = where.clone().add(0.0D, 1.0D, 0.0D);
        for (ItemStack stack : spilling) {
            dropOne(world, from, stack);
        }
        inventory.clear();
        inventory.setArmorContents(null);
        inventory.setItemInOffHand(null);
        Animations.spilled(world, from);
    }

    /** Adds the real items of an array, skipping anything already gathered. */
    private void collect(List<ItemStack> into, ItemStack[] stacks) {
        if (stacks == null) {
            return;
        }
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            boolean seen = false;
            for (ItemStack already : into) {
                if (already == stack) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                into.add(stack);
            }
        }
    }

    /** Throws one item out of the player: up and away, so the inventory is
     *  visibly leaving them instead of appearing under their feet. */
    private void dropOne(World world, Location where, ItemStack stack) {
        if (stack != null && !stack.getType().isAir()) {
            world.dropItem(where, stack.clone()).setVelocity(Animations.spill());
        }
    }
}
