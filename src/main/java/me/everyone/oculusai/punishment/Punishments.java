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
import java.util.function.IntConsumer;
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

    /** A kick or a ban an operator asked for by hand.
     *
     *  <p>Goes through the same send-off as a detection: the show, the
     *  stripping and the ordering are the punishment, and an admin kicking
     *  somebody they just watched cheat wants exactly what the plugin would
     *  have done on its own. What it skips is everything that belongs to the
     *  model - the buffer, the cooldown and the detection report - because
     *  nothing here was detected.
     *
     *  <p>{@code punishment.enabled} is not consulted either. That switch
     *  says whether the plugin may punish by itself; a person typing the
     *  command has already decided.
     *
     *  <p>Bypass is not consulted: it decides who the model looks at, not
     *  who an admin may remove.
     *
     *  @return false if the target left before the command landed */
    public boolean manual(Player player, boolean ban, String reason, String by) {
        if (!player.isOnline()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Map<String, String> placeholders = Map.of(
                "player", name,
                "reason", reason,
                "by", by);

        plugin.history().note(uuid, name, "punish",
                (ban ? "manual ban by " : "manual kick by ") + by + ": " + reason);

        // An operator who just watched somebody cheat wants the same thing
        // the plugin keeps for itself: the seconds before the kick, playable
        // in the panel. Frozen before the send-off, as on the automatic path.
        PlayerData suspect = plugin.data().get(player);
        if (suspect != null) {
            plugin.replays().capture(suspect, ban ? "manual-ban" : "manual-kick");
        }

        boolean shown = true;
        boolean strip = plugin.config().punishmentStrip();
        if (ban) {
            String configured = Msg.fill(plugin.config().punishmentBanCommand(), placeholders).trim();
            boolean vanilla = configured.isBlank();
            String banCommand = vanilla ? "ban " + name + " " + reason : configured;
            if (vanilla) {
                show(player, strip, () -> run(banCommand));
            } else {
                // A named ban command owns the punishment, show included -
                // the same rule the automatic path follows.
                shown = false;
                run(banCommand);
            }
            plugin.banCounter().banned(uuid);
        } else {
            show(player, strip, () -> plugin.platform().onEntity(player, () -> kick(player, reason)));
        }

        plugin.getLogger().info(by + " " + (ban ? "banned " : "kicked ") + name
                + (shown && strip ? " with the send-off" : "") + ": " + reason);

        String broadcast = plugin.lang().text(ban ? "manual.broadcast-ban" : "manual.broadcast-kick",
                placeholders);
        if (!broadcast.isBlank()) {
            plugin.alerts().announce(plugin.config().prefix() + broadcast);
        }
        return true;
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
                "buffer " + Msg.round(data.buffer(), 1) + ", p " + Msg.probability(data.probability()));

        // Before anything else happens to them. The send-off strips the
        // inventory, kills the player and disconnects them, so a replay taken
        // after it would be a recording of the punishment rather than of the
        // thing that caused it.
        plugin.replays().capture(data, "punish");

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
                "probability", Msg.probability(data.probability()),
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
            String banCommand = vanilla
                    ? "ban " + data.name() + " " + reason(placeholders) : configured;
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
            show(player, strip,
                    () -> plugin.platform().onEntity(player, () -> kick(player, placeholders)));
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
    public void playShow(Player player, String chosen, Runnable onStart,
                         IntConsumer onFrame, Runnable atEnd) {
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
        Platform.Cancellable frames = plugin.platform().entityRepeating(player, 1L, 1L, () -> {
            int now = tick.incrementAndGet();
            Animations.frame(player, chosen, now, total);
            if (onFrame != null) {
                onFrame.accept(now);
            }
        });

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
        int total = Animations.ticks(chosen);
        if (total > 0) {
            held.add(uuid);
        }

        // Taken off them at the very start whatever happens next: a cheater
        // who keeps their gear through the show would also take it with them
        // if the server stopped halfway. What varies is only how fast the
        // pile is let go of.
        int beat = total > 0 ? Animations.spillEvery(chosen) : 0;
        List<ItemStack> pending = new ArrayList<>();
        int[] perBeat = {Integer.MAX_VALUE};

        playShow(player, chosen,
                strip ? () -> {
                    pending.addAll(takeEverything(player));
                    if (beat <= 0) {
                        release(player, pending, pending.size());
                        return;
                    }
                    // Spread over the beats the show actually has, so the
                    // last handful lands on the last sound rather than
                    // halfway through.
                    int beats = Math.max(1, total / beat);
                    perBeat[0] = Math.max(1, (pending.size() + beats - 1) / beats);
                } : null,
                strip && beat > 0 ? tick -> {
                    if (tick % beat == 0) {
                        release(player, pending, perBeat[0]);
                    }
                } : null,
                () -> {
                    held.remove(uuid);
                    plugin.platform().onEntity(player, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (strip) {
                            // Whatever the beats did not reach, plus anything
                            // picked up since - the show is over either way.
                            release(player, pending, pending.size());
                            release(player, takeEverything(player), Integer.MAX_VALUE);
                            player.setHealth(0.0D);
                        }
                    });
                    plugin.ticks().after(1L, disconnect);
                });
    }

    /** Drops up to {@code count} of the waiting items where the player is
     *  now, which during a show is wherever the throw has carried them. */
    private void release(Player player, List<ItemStack> waiting, int count) {
        if (waiting.isEmpty() || !player.isOnline()) {
            return;
        }
        Location where = player.getLocation();
        World world = where.getWorld();
        if (world == null) {
            return;
        }
        Location from = where.clone().add(0.0D, 1.0D, 0.0D);
        for (int i = 0; i < count && !waiting.isEmpty(); i++) {
            dropOne(world, from, waiting.remove(0));
        }
    }

    /** Disconnects a player who may already have left on their own. */
    private void kick(Player player, Map<String, String> placeholders) {
        kick(player, reason(placeholders));
    }

    /** What the punished player is shown, with the same placeholders the
     *  broadcast gets. Blank falls back rather than disconnecting somebody
     *  with an empty screen. */
    private String reason(Map<String, String> placeholders) {
        String configured = Msg.fill(plugin.config().punishmentKickMessage(), placeholders).trim();
        return configured.isBlank() ? "Cheat detected" : configured;
    }

    private void kick(Player player, String reason) {
        if (player.isOnline()) {
            player.kickPlayer(Msg.parse(reason));
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
    private List<ItemStack> takeEverything(Player player) {
        PlayerInventory inventory = player.getInventory();
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

        inventory.clear();
        inventory.setArmorContents(null);
        inventory.setItemInOffHand(null);
        Location where = player.getLocation();
        if (!spilling.isEmpty()) {
            Animations.spilled(where.getWorld(), where.clone().add(0.0D, 1.0D, 0.0D));
        }
        return spilling;
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
