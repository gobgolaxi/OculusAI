package me.everyone.oculusai.punishment;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Vector;

/** The send-off the plugin plays when it punishes somebody itself.
 *
 * <p>A show runs over a span of ticks rather than in one: the cheater is
 * lifted off the ground and held there while it plays, and only when it ends
 * are they disconnected. Kicking first and showing afterwards meant nobody
 * ever saw the thing - the punished player least of all, since they were
 * already at the server list.
 *
 * <p>Holding somebody still is not this class's job. It only draws; the freeze
 * and the disconnect belong to {@link Punishments}, which owns the sequence.
 *
 * <p>Nothing here touches the world. The explosion is particles and a sound
 * rather than {@code createExplosion}, and the lightning is
 * {@code strikeLightningEffect} rather than a real bolt: a punishment that
 * also blew a hole in somebody's base or set it alight would be a far more
 * expensive mistake than the false positive that caused it.
 *
 * <p>Particle and sound constants are looked up by name instead of being
 * written as enum constants. The plugin ships one jar per Minecraft
 * generation from the same shared source, and these two enums were renamed
 * between them - {@code SMOKE_LARGE} became {@code LARGE_SMOKE},
 * {@code EXPLOSION_LARGE} became {@code EXPLOSION}. A constant that exists in
 * only one of the two would not compile for the other. */
public final class Animations {

    public static final String NONE = "none";
    public static final String ASCEND = "ascend";
    public static final String EXPLODE = "explode";
    public static final String LIGHTNING = "lightning";
    public static final String FIREWORK = "firework";
    public static final String WITHER = "wither";
    public static final String RANDOM = "random";

    /** The default, and the one the rest are shaped after: the cheater is
     *  thrown upwards and everything they carried comes out of them. */
    public static final String DEFAULT = ASCEND;

    /** The order the dashboard cycles through. {@code random} is last so a
     *  click past the end of the real ones lands on it deliberately. */
    public static final List<String> ALL =
            List.of(NONE, ASCEND, EXPLODE, LIGHTNING, FIREWORK, WITHER, RANDOM);

    /** Everything {@code random} may pick, which is the real effects only. */
    private static final List<String> PICKABLE =
            List.of(ASCEND, EXPLODE, LIGHTNING, FIREWORK, WITHER);

    /** How long each show runs, in ticks. Long enough that somebody across
     *  the square turns to look, short enough that a cheater is not left
     *  hanging in the air while the server waits on them. */
    private static final int SHORT = 40;
    private static final int LONG = 50;

    /** The throw that starts a show, and the lift that keeps them up.
     *
     *  <p>The per-tick lift is deliberately small and purely vertical. A
     *  player who kept the velocity of the throw would sail off across the
     *  map and finish the show somewhere nobody is watching. */
    private static final double LAUNCH = 0.9D;
    private static final double LIFT = 0.12D;

    private Animations() {
    }

    public static String normalise(String id) {
        String lower = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return ALL.contains(lower) ? lower : DEFAULT;
    }

    /** The id after this one, for a button that cycles. */
    public static String next(String id) {
        int index = ALL.indexOf(normalise(id));
        return ALL.get((index + 1) % ALL.size());
    }

    /** Resolves {@code random} to the effect this particular punishment gets,
     *  so the whole sequence draws one show rather than a new one per tick. */
    public static String pick(String id) {
        String chosen = normalise(id);
        return chosen.equals(RANDOM)
                ? PICKABLE.get(ThreadLocalRandom.current().nextInt(PICKABLE.size()))
                : chosen;
    }

    /** How many ticks {@link #pick} 's result runs for. */
    public static int ticks(String chosen) {
        return switch (chosen) {
            case NONE -> 0;
            case EXPLODE, LIGHTNING -> SHORT;
            default -> LONG;
        };
    }

    /** Throws the player up and opens the show. Runs on the owning thread. */
    public static void start(Player player, String chosen) {
        if (chosen.equals(NONE)) {
            return;
        }
        player.setVelocity(new Vector(0.0D, LAUNCH, 0.0D));
        Location where = player.getLocation();
        World world = where.getWorld();
        if (world == null) {
            return;
        }
        switch (chosen) {
            case ASCEND -> play(world, where, 1.0F, 1.4F, "ENTITY_ENDER_DRAGON_FLAP");
            case EXPLODE -> play(world, where, 0.8F, 0.6F, "ENTITY_TNT_PRIMED", "ENTITY_GENERIC_EXPLODE");
            case LIGHTNING -> world.strikeLightningEffect(where);
            case WITHER -> play(world, where, 0.8F, 1.2F, "ENTITY_WITHER_SPAWN");
            default -> {
            }
        }
    }

    /** One frame. {@code tick} counts from 1 to {@code total}. */
    public static void frame(Player player, String chosen, int tick, int total) {
        if (chosen.equals(NONE) || !player.isOnline()) {
            return;
        }
        // Held up for the whole show: without this they are back on the
        // ground a second in, and the rest plays over a corpse on the floor.
        player.setVelocity(new Vector(0.0D, LIFT, 0.0D));

        Location where = player.getLocation();
        World world = where.getWorld();
        if (world == null) {
            return;
        }
        Location centre = where.clone().add(0.0D, 1.0D, 0.0D);
        switch (chosen) {
            case ASCEND -> {
                spawn(world, centre, 6, 0.35D, particle("CRIT", "CRIT"));
                spawn(world, centre, 4, 0.3D, particle("CLOUD", "CLOUD"));
            }
            case EXPLODE -> {
                // Builds towards the burst: the last ticks are visibly busier
                // than the first, so the end reads as an end.
                int count = 2 + (10 * tick) / total;
                spawn(world, centre, count, 0.4D, particle("SMOKE_NORMAL", "SMOKE"));
                spawn(world, centre, count / 2, 0.3D, particle("FLAME", "FLAME"));
            }
            case LIGHTNING -> {
                if (tick % 8 == 0) {
                    world.strikeLightningEffect(around(where, 2.5D));
                }
                spawn(world, centre, 3, 0.3D, particle("ELECTRIC_SPARK", "CRIT"));
            }
            case FIREWORK -> {
                if (tick % 12 == 0) {
                    firework(world, around(where, 1.5D).add(0.0D, 1.0D, 0.0D), false);
                }
            }
            case WITHER -> {
                spawn(world, centre, 8, 0.45D, particle("SMOKE_LARGE", "LARGE_SMOKE"));
                spawn(world, centre, 4, 0.4D, particle("SOUL", "SOUL_FIRE_FLAME"));
            }
            default -> {
            }
        }
    }

    /** The last beat, played where the player stood. Takes a location rather
     *  than the player because by now they may be dead. */
    public static void finish(World world, Location where, String chosen) {
        if (world == null || chosen.equals(NONE)) {
            return;
        }
        Location centre = where.clone().add(0.0D, 1.0D, 0.0D);
        switch (chosen) {
            case ASCEND -> {
                spawn(world, centre, 40, 0.6D, particle("CLOUD", "CLOUD"));
                play(world, where, 1.0F, 1.8F, "ENTITY_PLAYER_LEVELUP");
            }
            case EXPLODE -> {
                spawn(world, centre, 1, 0.0D, particle("EXPLOSION_HUGE", "EXPLOSION_EMITTER"));
                spawn(world, centre, 24, 0.6D, particle("EXPLOSION_LARGE", "EXPLOSION"));
                play(world, where, 1.0F, 1.0F, "ENTITY_GENERIC_EXPLODE");
            }
            case LIGHTNING -> world.strikeLightningEffect(where);
            case FIREWORK -> firework(world, centre, true);
            case WITHER -> {
                spawn(world, centre, 50, 0.6D, particle("SMOKE_LARGE", "LARGE_SMOKE"));
                play(world, where, 1.0F, 0.6F, "ENTITY_WITHER_DEATH", "ENTITY_WITHER_SPAWN");
            }
            default -> {
            }
        }
    }

    /** The push given to an item coming out of a punished player: up and
     *  away, so the inventory visibly leaves them rather than appearing in a
     *  heap under their feet. */
    public static Vector spill() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new Vector(
                random.nextDouble(-0.35D, 0.35D),
                random.nextDouble(0.35D, 0.75D),
                random.nextDouble(-0.35D, 0.35D));
    }

    private static Location around(Location where, double radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return where.clone().add(
                random.nextDouble(-radius, radius), 0.0D, random.nextDouble(-radius, radius));
    }

    private static void firework(World world, Location where, boolean big) {
        Firework rocket = world.spawn(where, Firework.class);
        FireworkMeta meta = rocket.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .withColor(Color.RED, Color.ORANGE)
                .withFade(Color.WHITE)
                .with(big ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.BALL)
                .withFlicker()
                .withTrail()
                .build());
        meta.setPower(0);
        rocket.setFireworkMeta(meta);
        // Straight away, not after a flight: the rocket would otherwise drift
        // off the spot everybody is looking at, and outlive the point.
        rocket.detonate();
    }

    private static void spawn(World world, Location where, int count, double spread,
                              Particle particle) {
        if (particle != null && count > 0) {
            world.spawnParticle(particle, where, count, spread, spread, spread, 0.0D);
        }
    }

    private static void play(World world, Location where, float volume, float pitch,
                             String... names) {
        Sound sound = sound(names);
        if (sound != null) {
            world.playSound(where, sound, volume, pitch);
        }
    }

    /** The first of these constants this server's API actually has. */
    private static Particle particle(String... names) {
        for (String name : names) {
            try {
                return Particle.valueOf(name);
            } catch (IllegalArgumentException absent) {
                // Try the name the other generation uses.
            }
        }
        return null;
    }

    private static Sound sound(String... names) {
        for (String name : names) {
            try {
                return Sound.valueOf(name);
            } catch (IllegalArgumentException absent) {
                // Try the name the other generation uses.
            }
        }
        return null;
    }
}
