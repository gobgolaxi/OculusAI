package me.everyone.oculusai.reputation;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.util.Msg;
import org.bukkit.entity.Player;

/** What the service knows about a player, and the notice when somebody with a
 *  record walks in.
 *
 *  <p>One service answers for many servers, which is the one thing a
 *  per-server anticheat cannot do: an account banned on four other
 *  installations this week is a different proposition from one that has never
 *  been kicked anywhere, and only the service sees both. This reports who
 *  joined and from where, and acts on what comes back.
 *
 *  <p>The identity is the account id, not the name - a name is a label two
 *  people can share and one person can change. The join report carries both,
 *  and the service decides which it is dealing with: on an offline-mode
 *  server the UUID is computed from the name, so the record there is about a
 *  name, and a notice says so.
 *
 *  <p>The address is sent once per join and stored by neither side as given:
 *  the service keeps an HMAC of it under a secret only it has, plus the first
 *  half for display. The plugin keeps nothing at all - it reads the address
 *  at the moment of the join and lets it go.
 *
 *  <p>What comes back is a signal for a human, never a punishment. Addresses
 *  are shared - carrier NAT, a phone network, two brothers on one router -
 *  and a reputation is a reason for a moderator to watch somebody, which is
 *  why this writes a line in chat and does nothing else.
 */
public final class Reputation implements Service {

    private static final long REFUSAL_LOG_INTERVAL_MS = 10L * 60L * 1000L;

    private final OculusAI plugin;
    /** When each player was last announced, so a player reconnecting six
     *  times does not produce six notices. Keyed on the player rather than on
     *  the identity: the plugin does not need to know which of those it is. */
    private final Map<UUID, Long> announced = new ConcurrentHashMap<>();
    /** Why the service is not recording joins, if it is not.
     *
     *  <p>This used to be invisible. The join report is fire and forget, so a
     *  service answering "recorded: false - no account behind this key" was
     *  dropped on the floor: reputation silently recorded nothing, for weeks,
     *  on a server whose kicks were working perfectly. The refusal is kept
     *  here so the log can say it and so {@code /oai reputation} can explain
     *  an empty answer instead of just giving one. */
    private volatile String refusal = "";
    private final AtomicLong refusalLoggedAt = new AtomicLong(0L);

    public Reputation(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        if (!plugin.config().reputationEnabled()) {
            return;
        }
        // Everybody already here.
        //
        // The report rides on the join event, which means a player who was
        // online when the plugin started - every player, after a restart or
        // an update - was never reported at all. They would stay unknown to
        // the service until they happened to reconnect, and in the meantime
        // every kick they collected was dropped for want of an identity to
        // attach it to. This is also the only moment that gap can be closed
        // from.
        int reported = 0;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            seen(player);
            reported++;
        }
        if (reported > 0) {
            plugin.getLogger().info("Reported " + reported
                    + " player(s) already online to the reputation index");
        }
    }

    @Override
    public void disable() {
        announced.clear();
        refusal = "";
    }

    /** Why joins are not being recorded, or empty when they are. */
    public String refusal() {
        return refusal;
    }

    @Override
    public void reload() {
        // The thresholds changed; a player held back by the old cooldown
        // should be judged again by the new settings.
        announced.clear();
    }

    public void forget(UUID uuid) {
        announced.remove(uuid);
    }

    /** Tells the service that this name is online from this address, and acts
     *  on the verdict it answers with.
     *
     *  <p>The verdict rides on the reply to the report rather than on a
     *  second request: the server has one decision to make here, and a join
     *  should cost one round trip. Nothing waits on the network - the report
     *  is handed to the HTTP worker, and if it never lands the cost is one
     *  observation out of however many that player has. */
    public void seen(Player player) {
        if (!plugin.config().reputationEnabled() || player == null) {
            return;
        }
        String address = addressOf(player);
        if (address.isEmpty()) {
            return;
        }
        String name = player.getName();
        UUID uuid = player.getUniqueId();
        plugin.api().reputationSeen(name, uuid.toString(), address)
                .thenAccept(answer -> {
                    noteRefusal(answer);
                    consider(uuid, name, answer);
                });
    }

    /** Everything the service has on a player: a UUID, or a name that may
     *  belong to several accounts. */
    public CompletableFuture<JsonObject> lookup(String query) {
        if (!plugin.config().reputationEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        return plugin.api().reputation(query);
    }

    /** Says out loud when the service is not recording anything.
     *
     *  <p>A key with no account behind it is the usual reason: it predicts
     *  and it kicks, because neither needs an account, but a reputation row
     *  has to belong to a server and an unattached key gives it nothing to
     *  belong to. Bind the server with {@code /oai connect} and it starts
     *  working. Logged at most once every ten minutes - every join would
     *  otherwise print it. */
    private void noteRefusal(JsonObject answer) {
        if (answer == null) {
            return;
        }
        boolean recorded = answer.has("recorded") && answer.get("recorded").getAsBoolean();
        if (recorded) {
            refusal = "";
            return;
        }
        String note = answer.has("note") ? answer.get("note").getAsString() : "";
        refusal = note.isBlank() ? "the service did not record this join" : note;

        long now = System.currentTimeMillis();
        long last = refusalLoggedAt.get();
        if (now - last < REFUSAL_LOG_INTERVAL_MS || !refusalLoggedAt.compareAndSet(last, now)) {
            return;
        }
        plugin.getLogger().warning("Reputation is recording nothing: " + refusal
                + ". Predictions and punishments are unaffected; cross-server reputation"
                + " needs the key bound to an account - /oai connect does that.");
    }

    /** Decides whether this arrival is worth interrupting a moderator over.
     *
     *  <p>Two gates, and the second is the one that keeps the notice usable.
     *  By default only what happened on *other* servers counts: this server's
     *  own kicks are already in its own journal, so announcing a player to the
     *  staff who banned them yesterday is noise - and a notice that cries
     *  wolf is a notice somebody switches off. */
    private void consider(UUID uuid, String name, JsonObject answer) {
        if (answer == null || !plugin.config().reputationJoinAlert()) {
            return;
        }
        JsonObject elsewhere = answer.has("elsewhere") && answer.get("elsewhere").isJsonObject()
                ? answer.getAsJsonObject("elsewhere") : new JsonObject();
        boolean othersOnly = plugin.config().reputationJoinAlertOtherServersOnly();
        double score = othersOnly ? number(elsewhere, "score") : number(answer, "score");
        if (score < plugin.config().reputationJoinAlertScore()) {
            return;
        }
        if (othersOnly && number(elsewhere, "servers") < 1) {
            return;
        }

        long cooldown = plugin.config().reputationJoinAlertCooldownMs();
        if (cooldown > 0L) {
            Long previous = announced.get(uuid);
            long now = System.currentTimeMillis();
            if (previous != null && now - previous < cooldown) {
                return;
            }
            announced.put(uuid, now);
        }

        JsonObject counts = othersOnly ? elsewhere : answer;
        String line = plugin.config().prefix() + plugin.lang().text("reputation.join-alert",
                Map.of("player", name,
                        "score", Msg.round(score, 2),
                        "kicks", Integer.toString((int) number(counts, "kicks")),
                        "bans", Integer.toString((int) number(counts, "bans")),
                        "alerts", Integer.toString((int) number(counts, "alerts")),
                        "servers", Integer.toString((int) number(
                                othersOnly ? elsewhere : answer, "servers"))));

        // Back onto the main thread: the staff channel is a walk over the
        // online players, which is a live view of a collection the server
        // mutates on its own thread.
        plugin.mainThread().run(() -> {
            plugin.alerts().announce(line,
                    plugin.config().reputationJoinAlertSound(),
                    plugin.config().reputationJoinAlertVolume(),
                    plugin.config().reputationJoinAlertPitch());
            boolean weak = answer.has("weak") && answer.get("weak").getAsBoolean();
            if (weak) {
                // Worth saying on the notice itself: on an offline-mode
                // server the record is attached to a name, and the next
                // person to take that name inherits it.
                plugin.alerts().announce(plugin.lang().text("reputation.join-alert-weak"));
            }
        });

        plugin.getLogger().info("Reputation notice for " + name + ": score "
                + Msg.round(score, 2) + " from " + (int) number(elsewhere, "servers")
                + " other server(s)");
        plugin.history().note(uuid, name, "reputation",
                "joined with score " + Msg.round(score, 2)
                        + " (" + (int) number(counts, "kicks") + " kicks, "
                        + (int) number(counts, "bans") + " bans elsewhere)");
    }

    private static double number(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsDouble() : 0.0D;
        } catch (RuntimeException notANumber) {
            return 0.0D;
        }
    }

    /** The address a player connected from, or empty if the server will not
     *  say - which happens on a proxy that has not forwarded one, and after
     *  the player has already left. */
    private String addressOf(Player player) {
        java.net.InetSocketAddress socket = player.getAddress();
        if (socket == null || socket.getAddress() == null) {
            return "";
        }
        return socket.getAddress().getHostAddress();
    }
}
