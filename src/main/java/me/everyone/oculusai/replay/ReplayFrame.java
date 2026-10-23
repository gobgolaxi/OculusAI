package me.everyone.oculusai.replay;

import java.util.List;

/** One tick of a replay: where the suspect was, what stood around them, and
 *  what landed.
 *
 *  <p>Frames are taken on the tick loop and kept in a ring buffer, so they
 *  have to be cheap to make and cheap to hold: a few hundred of them exist
 *  per player at any moment. Everything here is therefore primitives and
 *  immutable lists - no Bukkit handles. A frame holding a {@code Player}
 *  would pin a disconnected player's whole entity for as long as the buffer
 *  remembered them, and a frame holding a {@code Location} would be read at
 *  upload time rather than at capture time, which is the one thing a replay
 *  must never do: the point is what the world looked like *then*. */
public record ReplayFrame(int tick,
                          double x, double y, double z,
                          float yaw, float pitch,
                          int flags,
                          double probability, double buffer,
                          List<Actor> actors,
                          List<Hit> hits) {

    /** Sprinting, sneaking, on the ground, in water - packed because a frame
     *  is written 20 times a second and four booleans is four more fields in
     *  the JSON for every one of them. */
    public static final int FLAG_SPRINTING = 1;
    public static final int FLAG_SNEAKING = 1 << 1;
    public static final int FLAG_ON_GROUND = 1 << 2;
    public static final int FLAG_IN_WATER = 1 << 3;

    /** Anything near the suspect that moves: other players, crystals,
     *  projectiles, mobs.
     *
     *  @param id    the server's entity id, which is also how a {@link Hit}
     *               names its attacker and its victim
     *  @param type  index into the replay's type table, not a string: the
     *               same twenty words would otherwise be repeated in every
     *               frame of every actor
     *  @param health 0 for anything that has none */
    public record Actor(int id, int type, double x, double y, double z,
                        float yaw, float pitch, double health) {
    }

    /** One blow inside this tick. {@code crit} is the server's own judgement,
     *  not a guess from the fall distance: a replay that disagreed with the
     *  damage the server applied would be worse than no replay. */
    public record Hit(int attacker, int victim, boolean crit, double damage) {
    }
}
