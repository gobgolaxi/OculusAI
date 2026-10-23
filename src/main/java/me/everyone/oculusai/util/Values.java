package me.everyone.oculusai.util;

import me.everyone.oculusai.config.Config;

/** One colour language for probability and buffer everywhere they are shown.
 *
 * <p>Chat alerts and the monitor used to print both values in the same grey as
 * the surrounding text, so the number that mattered was the hardest thing to
 * find. The thresholds are the same ones the checks use: grey below suspicion,
 * yellow at the alert line, red at the punishment line. */
public final class Values {

    private Values() {
    }

    public static String probability(Config config, double probability) {
        if (Double.isNaN(probability)) {
            return "<dark_gray><bold>-";
        }
        String colour;
        if (probability < config.suspicion()) {
            colour = "gray";
        } else if (probability < 0.7D) {
            colour = "yellow";
        } else if (probability < 0.9D) {
            colour = "gold";
        } else {
            colour = "red";
        }
        // Bold: in a chat full of grey the number is the thing being read.
        // The tag that follows it in inline() clears the bold again.
        return "<" + colour + "><bold>" + Msg.probability(probability);
    }

    public static String buffer(Config config, double buffer) {
        String colour;
        if (buffer >= config.punishAt()) {
            colour = "red";
        } else if (buffer >= config.alertAt()) {
            colour = "gold";
        } else if (buffer > 0.0D) {
            colour = "green";
        } else {
            colour = "dark_gray";
        }
        return "<" + colour + "><bold>" + Msg.round(buffer, 1);
    }

    /** The compact "p 82% | buf 4.1/10.0" block used by alerts. */
    public static String inline(Config config, double probability, double buffer) {
        return "<gray>p " + probability(config, probability)
                + " <dark_gray>| <gray>buf " + buffer(config, buffer)
                + "<dark_gray>/<gray>" + Msg.round(config.bufferMax(), 1);
    }
}
