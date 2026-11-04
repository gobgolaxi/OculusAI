package me.everyone.oculusai.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.everyone.oculusai.player.PlayerData;
import org.junit.jupiter.api.Test;

class ValuesFormatTest {

    private static String plain(String miniMessage) {
        return Msg.strip(miniMessage);
    }

    @Test
    void groupsAreLetteredScaledAndInServiceOrder() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("SprintReset", 0.0D);
        scores.put("Rotation", 0.5D);
        scores.put("Aim", 1.5D);
        assertEquals("R0.50 A1.00 S0.00", plain(Values.groups(scores)));
    }

    @Test
    void missingGroupsShowADash() {
        assertEquals("-", plain(Values.groups(Map.of())));
        assertEquals("-", plain(Values.groups(null)));
    }

    @Test
    void recentListsTheLastFiveOldestFirstAndAveragesThem() {
        List<PlayerData.Reading> readings = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            readings.add(new PlayerData.Reading(i, i / 10.0D, 0.0D));
        }
        String recent = plain(Values.recent(readings));
        assertEquals(".20 .30 .40 .50 .60", recent);
        assertEquals("0.40", plain(Values.average(readings)));
        assertTrue(plain(Values.recent(List.of())).equals("-"));
    }
}
