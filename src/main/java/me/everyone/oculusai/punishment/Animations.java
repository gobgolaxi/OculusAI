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

/** The send-off the plugin plays when it punishes somebody itself.
 *
 * <p>This runs only where no command was configured. An operator who named a
 * command owns the whole punishment, including whatever it wants to show.
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
    public static final String EXPLODE = "explode";
    public static final String LIGHTNING = "lightning";
    public static final String FIREWORK = "firework";
    public static final String WITHER = "wither";
    public static final String RANDOM = "random";

    /** The order the dashboard cycles through. {@code random} is last so a
     *  click past the end of the real ones lands on it deliberately. */
    public static final List<String> ALL =
            List.of(NONE, EXPLODE, LIGHTNING, FIREWORK, WITHER, RANDOM);

    /** Everything {@code random} may pick, which is the real effects only. */
    private static final List<String> PICKABLE =
            List.of(EXPLODE, LIGHTNING, FIREWORK, WITHER);

    private Animations() {
    }

    public static String normalise(String id) {
        String lower = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return ALL.contains(lower) ? lower : NONE;
    }

    /** The id after this one, for a button that cycles. */
    public static String next(String id) {
        int index = ALL.indexOf(normalise(id));
        return ALL.get((index + 1) % ALL.size());
    }

    /** Plays the effect where the player is standing.
     *
     *  <p>Must run on the thread that owns the player. The effects outlive the
     *  player entity, which is the point: by the time anybody watching has
     *  seen this, the cheater is already being disconnected. */
    public static void play(Player player, String id) {
        String chosen = normalise(id);
        if (chosen.equals(RANDOM)) {
            chosen = PICKABLE.get(ThreadLocalRandom.current().nextInt(PICKABLE.size()));
        }
        Location where = player.getLocation();
        World world = where.getWorld();
        if (world == null || chosen.equals(NONE)) {
            return;
        }
        switch (chosen) {
            case EXPLODE -> explode(world, where);
            case LIGHTNING -> lightning(world, where);
            case FIREWORK -> firework(world, where);
            case WITHER -> wither(world, where);
            default -> {
            }
        }
    }

    private static void explode(World world, Location where) {
        Location centre = where.clone().add(0.0D, 1.0D, 0.0D);
        spawn(world, centre, 1, 0.0D, particle("EXPLOSION_HUGE", "EXPLOSION_EMITTER"));
        spawn(world, centre, 24, 0.6D, particle("EXPLOSION_LARGE", "EXPLOSION"));
        play(world, where, 1.0F, 1.0F, "ENTITY_GENERIC_EXPLODE");
    }

    private static void lightning(World world, Location where) {
        // The effect-only bolt: a flash and the thunder, no fire and no damage.
        world.strikeLightningEffect(where);
    }

    private static void firework(World world, Location where) {
        Firework rocket = world.spawn(where.clone().add(0.0D, 1.0D, 0.0D), Firework.class);
        FireworkMeta meta = rocket.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .withColor(Color.RED, Color.ORANGE)
                .withFade(Color.WHITE)
                .with(FireworkEffect.Type.BALL_LARGE)
                .withFlicker()
                .withTrail()
                .build());
        meta.setPower(0);
        rocket.setFireworkMeta(meta);
        // Straight away, not after a flight: the rocket would otherwise drift
        // off the spot everybody is looking at, and outlive the point.
        rocket.detonate();
    }

    private static void wither(World world, Location where) {
        Location centre = where.clone().add(0.0D, 1.0D, 0.0D);
        spawn(world, centre, 40, 0.5D, particle("SMOKE_LARGE", "LARGE_SMOKE"));
        spawn(world, centre, 30, 0.5D, particle("SOUL", "SOUL_FIRE_FLAME"));
        play(world, where, 0.8F, 1.2F, "ENTITY_WITHER_SPAWN");
    }

    private static void spawn(World world, Location where, int count, double spread,
                              Particle particle) {
        if (particle != null) {
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
