package me.everyone.yuppyai.manager;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiManagerTest {

    @Test
    @DisplayName("a window that was never scored becomes null, not an exception")
    void unscoredWindowsBecomeNull() {
        List<double[]> context = List.of(
                new double[]{Double.NaN, Double.NaN},
                new double[]{0.93, 7.5});

        JsonArray scores = ApiManager.column(context, 0);
        JsonArray buffers = ApiManager.column(context, 1);

        assertEquals(2, scores.size());
        assertTrue(scores.get(0).isJsonNull());
        assertEquals(0.93, scores.get(1).getAsDouble(), 1e-9);
        assertTrue(buffers.get(0).isJsonNull());
        assertEquals(7.5, buffers.get(1).getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("a capture of nothing but unscored windows serialises")
    void allUnscoredSerialises() {
        List<double[]> context = List.of(
                new double[]{Double.NaN, Double.NaN},
                new double[]{Double.NaN, Double.NaN});

        JsonArray scores = ApiManager.column(context, 0);

        assertEquals("[null,null]", scores.toString());
    }

    @Test
    @DisplayName("only captures the model actually scored carry the columns")
    void scoredCapturesAreRecognised() {
        assertFalse(ApiManager.anyScored(List.of(
                new double[]{Double.NaN, Double.NaN},
                new double[]{Double.NaN, Double.NaN})));
        assertTrue(ApiManager.anyScored(List.of(
                new double[]{Double.NaN, Double.NaN},
                new double[]{0.5, 2.0})));
        assertFalse(ApiManager.anyScored(List.of()));
    }
}
