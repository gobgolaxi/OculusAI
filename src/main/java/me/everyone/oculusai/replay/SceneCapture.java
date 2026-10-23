package me.everyone.oculusai.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** The cube of blocks around a suspect, taken once.
 *
 *  <p>The terrain is the expensive half of a replay and the half that barely
 *  changes, so it is read exactly once - at the moment the punishment fires -
 *  rather than per frame. A 21x21x21 cube is 9261 blocks; stored as a
 *  material name each that is around 300 KB of JSON, which is why it goes out
 *  as a palette plus run lengths instead. Combat happens in the open, so the
 *  cube is mostly air and mostly one stone: the same capture comes to a few
 *  kilobytes.
 *
 *  <p>Read on the thread that owns the player, and only from chunks that are
 *  already loaded: pulling a chunk off disk to draw the scenery would stall
 *  the server for a picture nobody has asked to see yet. A missing chunk is
 *  written as air, which is what the viewer should show - we genuinely do not
 *  know what was there. */
final class SceneCapture {

    /** Resolved once. 1.16's Bukkit has no getMinHeight: the API arrived in
     *  1.17, and {@code src/main} is compiled into both jars. Reflection here
     *  keeps the 1.21 jar reading its negative world floor while the 1.16 one
     *  carries on assuming zero, without a third source tree for one call. */
    private static final Method MIN_HEIGHT = minHeightMethod();

    private SceneCapture() {
    }

    private static Method minHeightMethod() {
        try {
            return World.class.getMethod("getMinHeight");
        } catch (NoSuchMethodException older) {
            return null;
        }
    }

    static int minHeight(World world) {
        if (MIN_HEIGHT == null) {
            return 0;
        }
        try {
            return (int) MIN_HEIGHT.invoke(world);
        } catch (ReflectiveOperationException | ClassCastException unexpected) {
            return 0;
        }
    }

    /** The cube around {@code centre}, as the replay format carries it.
     *
     *  @param radius blocks in every direction, so the cube is
     *                {@code 2 * radius + 1} on a side */
    static JsonObject terrain(Location centre, int radius) {
        World world = centre.getWorld();
        int size = radius * 2 + 1;
        int originX = centre.getBlockX() - radius;
        int originZ = centre.getBlockZ() - radius;
        int floor = minHeight(world);
        int ceiling = world.getMaxHeight();
        int originY = Math.max(floor, centre.getBlockY() - radius);

        Palette palette = new Palette();
        Runs runs = new Runs();
        // x, then y, then z: the viewer walks the runs in this order, so the
        // two sides have to agree on it and nothing else about the layout.
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                for (int dz = 0; dz < size; dz++) {
                    int x = originX + dx;
                    int y = originY + dy;
                    int z = originZ + dz;
                    runs.add(y >= ceiling ? 0 : palette.index(materialAt(world, x, y, z)));
                }
            }
        }
        runs.finish();

        JsonObject out = new JsonObject();
        JsonArray dimensions = new JsonArray();
        dimensions.add(size);
        dimensions.add(size);
        dimensions.add(size);
        out.add("size", dimensions);
        JsonObject origin = new JsonObject();
        origin.addProperty("x", originX);
        origin.addProperty("y", originY);
        origin.addProperty("z", originZ);
        out.add("origin", origin);
        out.add("palette", palette.toJson());
        out.add("runs", runs.toJson());
        return out;
    }

    private static String materialAt(World world, int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return "AIR";
        }
        Block block = world.getBlockAt(x, y, z);
        return block.getType().name();
    }

    /** Material names in first-seen order, with air pinned at zero so an
     *  all-air cube is one run of one index. */
    private static final class Palette {

        private final java.util.Map<String, Integer> indices = new java.util.LinkedHashMap<>();

        private Palette() {
            indices.put("AIR", 0);
        }

        int index(String material) {
            Integer known = indices.get(material);
            if (known != null) {
                return known;
            }
            int next = indices.size();
            indices.put(material, next);
            return next;
        }

        JsonArray toJson() {
            JsonArray array = new JsonArray();
            indices.keySet().forEach(array::add);
            return array;
        }
    }

    /** Run-length encoding as flat pairs: count, index, count, index. Flat
     *  rather than nested arrays because this is the one part of a replay
     *  with thousands of entries in it, and {@code [12,0,3,1]} is half the
     *  bytes of {@code [[12,0],[3,1]]}. */
    private static final class Runs {

        private final JsonArray array = new JsonArray();
        private int current = -1;
        private int count;

        void add(int index) {
            if (index == current) {
                count++;
                return;
            }
            finish();
            current = index;
            count = 1;
        }

        void finish() {
            if (count > 0) {
                array.add(count);
                array.add(current);
                count = 0;
            }
        }

        JsonArray toJson() {
            return array;
        }
    }
}
