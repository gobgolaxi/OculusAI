package me.everyone.oculusai.core;

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

    /** How many of the newest readings the hologram lists after AVG. */
    public static final int RECENT = 5;

    /** Средняя из последних {@link #RECENT} показаний, в цвете детекта. */
    public static String average(java.util.List<me.everyone.oculusai.player.PlayerData.Reading> readings) {
        if (readings.isEmpty()) {
            return "<gray>-";
        }
        int from = Math.max(0, readings.size() - RECENT);
        double total = 0.0D;
        for (int i = from; i < readings.size(); i++) {
            total += readings.get(i).probability();
        }
        double mean = total / (readings.size() - from);
        return "<" + Msg.heatColour(mean) + ">"
                + String.format(java.util.Locale.ROOT, "%.2f", Math.max(0.0D, Math.min(1.0D, mean)));
    }

    /** Последние показания слева направо, от старого к новому, каждое в цвете
     *  детекта. Коротко - ".28", а не "0.283": пять показаний по пять знаков
     *  над головой не влезали в строку голограммы и переносились. */
    public static String recent(java.util.List<me.everyone.oculusai.player.PlayerData.Reading> readings) {
        if (readings.isEmpty()) {
            return "<dark_gray>-";
        }
        StringBuilder line = new StringBuilder();
        for (int i = Math.max(0, readings.size() - RECENT); i < readings.size(); i++) {
            double value = readings.get(i).probability();
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append('<').append(Msg.heatColour(value)).append('>').append(shortValue(value));
        }
        return line.toString();
    }

    /** ".28", "1.0" и "-" для пропуска. */
    public static String shortValue(double value) {
        if (!Double.isFinite(value)) {
            return "-";
        }
        double clamped = Math.max(0.0D, Math.min(1.0D, value));
        if (clamped >= 0.995D) {
            return "1.0";
        }
        return String.format(java.util.Locale.ROOT, "%.2f", clamped).substring(1);
    }

    /** Группы признаков в том порядке, в каком их называет сервис, и буква,
     *  которой каждая подписана в action bar. */
    private static final String[][] GROUPS = {
            {"Rotation", "R"}, {"Aim", "A"}, {"Hits", "H"}, {"Crits", "C"}, {"SprintReset", "S"}};
    /** "R0.42 A0.10 H0.33 C0.05 S0.00": насколько каждая группа редкая для
     *  честных окон, 0..1 (шкалу строит сервис, см. ApiClient.Prediction), и
     *  цвет по тому же числу. Группы, которых сервис не прислал, пропускаются. */
    /** Те же группы короче - для строки ленты окон: "R.42 A.00 H.10". */
    public static String groupsShort(java.util.Map<String, Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return "<dark_gray>-";
        }
        StringBuilder line = new StringBuilder();
        for (String[] group : GROUPS) {
            Double score = scores.get(group[0]);
            if (score == null || !Double.isFinite(score)) {
                continue;
            }
            double value = Math.max(0.0D, Math.min(1.0D, score));
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append("<gray>").append(group[1])
                    .append('<').append(Msg.heatColour(value)).append('>').append(shortValue(value));
        }
        return line.length() == 0 ? "<dark_gray>-" : line.toString();
    }

    public static String groups(java.util.Map<String, Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return "<dark_gray>-";
        }
        StringBuilder line = new StringBuilder();
        for (String[] group : GROUPS) {
            Double score = scores.get(group[0]);
            if (score == null || !Double.isFinite(score)) {
                continue;
            }
            double value = Math.max(0.0D, Math.min(1.0D, score));
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append("<gray>").append(group[1])
                    .append('<').append(Msg.heatColour(value)).append('>')
                    .append(String.format(java.util.Locale.ROOT, "%.2f", value));
        }
        return line.length() == 0 ? "<dark_gray>-" : line.toString();
    }

    /** The compact "p 82% | buf 4.1/10.0" block used by alerts. */
    public static String inline(Config config, double probability, double buffer) {
        return "<gray>p " + probability(config, probability)
                + " <dark_gray>| <gray>buf " + buffer(config, buffer)
                + "<dark_gray>/<gray>" + Msg.round(config.bufferMax(), 1);
    }
}
