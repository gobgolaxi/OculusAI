package me.everyone.oculusai.network;

import com.google.gson.JsonArray;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiClientTest {

    @Test
    @DisplayName("a window that was never scored becomes null, not an exception")
    void unscoredWindowsBecomeNull() {
        List<double[]> context = List.of(
                new double[]{Double.NaN, Double.NaN},
                new double[]{0.93, 7.5});

        JsonArray scores = ApiClient.column(context, 0);
        JsonArray buffers = ApiClient.column(context, 1);

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

        JsonArray scores = ApiClient.column(context, 0);

        assertEquals("[null,null]", scores.toString());
    }

    @Test
    @DisplayName("only captures the model actually scored carry the columns")
    void scoredCapturesAreRecognised() {
        assertFalse(ApiClient.anyScored(List.of(
                new double[]{Double.NaN, Double.NaN},
                new double[]{Double.NaN, Double.NaN})));
        assertTrue(ApiClient.anyScored(List.of(
                new double[]{Double.NaN, Double.NaN},
                new double[]{0.5, 2.0})));
        assertFalse(ApiClient.anyScored(List.of()));
    }
}
