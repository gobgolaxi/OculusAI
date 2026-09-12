package me.everyone.yuppyai.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FeatureExtractor {

    private static final double EPSILON = 1.0E-6D;
    private static final double QUANTUM_RESOLUTION = 1.0E-4D;

    private FeatureExtractor() {
    }

    public static FeatureVector extract(List<RotationSample> window, double baselineScale) {
        if (window.size() < 3) {
            throw new IllegalArgumentException("a window needs at least three samples");
        }

        List<Double> yawDeltas = new ArrayList<>(window.size());
        List<Double> pitchDeltas = new ArrayList<>(window.size());
        for (int i = 1; i < window.size(); i++) {
            double signedYaw = wrapDegrees(window.get(i).yaw() - window.get(i - 1).yaw());
            yawDeltas.add(Math.abs(signedYaw));
            pitchDeltas.add((double) Math.abs(window.get(i).pitch() - window.get(i - 1).pitch()));
        }

        double scale = Math.max(median(yawDeltas), EPSILON);

        List<Double> yawAccel = differences(yawDeltas);
        List<Double> pitchAccel = differences(pitchDeltas);

        double yawMean = mean(yawDeltas);
        double pitchMean = mean(pitchDeltas);
        double yawAccelMean = mean(yawAccel);

        int zeroTicks = 0;
        for (double delta : yawDeltas) {
            if (delta < EPSILON) {
                zeroTicks++;
            }
        }

        double minPitch = Double.MAX_VALUE;
        double maxPitch = -Double.MAX_VALUE;
        int attacks = 0;
        List<Double> aimErrors = new ArrayList<>();
        for (RotationSample sample : window) {
            minPitch = Math.min(minPitch, sample.pitch());
            maxPitch = Math.max(maxPitch, sample.pitch());
            if (sample.attacked()) {
                attacks++;
                if (!Double.isNaN(sample.aimError())) {
                    aimErrors.add(sample.aimError());
                }
            }
        }

        double quantum = quantum(yawDeltas);
        double aimErrorMean = mean(aimErrors);

        return new FeatureVector(
                baselineScale > EPSILON ? scale / baselineScale : 1.0D,
                standardDeviation(yawDeltas, yawMean) / scale,
                pitchMean / scale,
                standardDeviation(pitchDeltas, pitchMean) / scale,
                yawAccelMean / scale,
                mean(pitchAccel) / scale,
                (maxPitch - minPitch) / scale,
                yawAccelMean / (yawMean + EPSILON),
                max(yawDeltas) / (yawMean + EPSILON),
                zeroTicks / (double) yawDeltas.size(),
                correlation(yawDeltas, pitchDeltas),
                quantum > QUANTUM_RESOLUTION ? scale / quantum : 0.0D,
                quantumError(yawDeltas, quantum),
                attacks * 20.0D / window.size(),
                aimErrorMean,
                standardDeviation(aimErrors, aimErrorMean),
                aimErrors.isEmpty() ? 0.0D : Collections.min(aimErrors));
    }

    public static double scaleOf(List<RotationSample> window) {
        if (window.size() < 2) {
            return 0.0D;
        }
        List<Double> deltas = new ArrayList<>(window.size() - 1);
        for (int i = 1; i < window.size(); i++) {
            deltas.add(Math.abs(wrapDegrees(window.get(i).yaw() - window.get(i - 1).yaw())));
        }
        return median(deltas);
    }

    static double quantum(List<Double> deltas) {
        List<Double> moving = new ArrayList<>();
        for (double delta : deltas) {
            if (delta > QUANTUM_RESOLUTION) {
                moving.add(delta);
            }
        }
        if (moving.size() < 3) {
            return 0.0D;
        }

        double smallest = Double.MAX_VALUE;
        for (double delta : moving) {
            smallest = Math.min(smallest, delta);
        }

        double best = smallest;
        double bestError = Double.MAX_VALUE;
        for (int divisor = 1; divisor <= 8; divisor++) {
            double candidate = smallest / divisor;
            if (candidate < QUANTUM_RESOLUTION) {
                break;
            }
            double error = remainderError(moving, candidate);
            if (error < bestError) {
                bestError = error;
                best = candidate;
            }
        }
        return best;
    }

    static double quantumError(List<Double> deltas, double step) {
        if (step <= QUANTUM_RESOLUTION) {
            return 1.0D;
        }
        List<Double> moving = new ArrayList<>();
        for (double delta : deltas) {
            if (delta > QUANTUM_RESOLUTION) {
                moving.add(delta);
            }
        }
        return moving.isEmpty() ? 1.0D : remainderError(moving, step);
    }

    private static double remainderError(List<Double> values, double step) {
        double total = 0.0D;
        for (double value : values) {
            double remainder = value % step;
            total += Math.min(remainder, step - remainder) / step;
        }
        return total / values.size();
    }

    private static List<Double> differences(List<Double> values) {
        List<Double> result = new ArrayList<>(Math.max(0, values.size() - 1));
        for (int i = 1; i < values.size(); i++) {
            result.add(Math.abs(values.get(i) - values.get(i - 1)));
        }
        return result;
    }

    static double correlation(List<Double> first, List<Double> second) {
        int size = Math.min(first.size(), second.size());
        if (size < 2) {
            return 0.0D;
        }
        double meanFirst = mean(first.subList(0, size));
        double meanSecond = mean(second.subList(0, size));
        double covariance = 0.0D;
        double varianceFirst = 0.0D;
        double varianceSecond = 0.0D;
        for (int i = 0; i < size; i++) {
            double a = first.get(i) - meanFirst;
            double b = second.get(i) - meanSecond;
            covariance += a * b;
            varianceFirst += a * a;
            varianceSecond += b * b;
        }
        double denominator = Math.sqrt(varianceFirst * varianceSecond);
        return denominator < EPSILON ? 0.0D : covariance / denominator;
    }

    static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0D;
    }

    static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double total = 0.0D;
        for (double value : values) {
            total += value;
        }
        return total / values.size();
    }

    static double standardDeviation(List<Double> values, double mean) {
        if (values.size() < 2) {
            return 0.0D;
        }
        double total = 0.0D;
        for (double value : values) {
            double difference = value - mean;
            total += difference * difference;
        }
        return Math.sqrt(total / values.size());
    }

    private static double max(List<Double> values) {
        double result = 0.0D;
        for (double value : values) {
            result = Math.max(result, value);
        }
        return result;
    }

    public static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        if (wrapped >= 180.0D) {
            wrapped -= 360.0D;
        }
        if (wrapped < -180.0D) {
            wrapped += 360.0D;
        }
        return wrapped;
    }
}
