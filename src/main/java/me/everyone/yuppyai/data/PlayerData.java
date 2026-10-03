package me.everyone.yuppyai.data;

import me.everyone.yuppyai.analysis.RotationSample;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

public final class PlayerData {

    private static final int MAX_HISTORY = 40;
    private static final int MAX_READINGS = 240;
    private static final int MAX_SCALE_HISTORY = 300;
    private static final int MIN_SCALE_HISTORY = 20;

    /**
     * One analysed window: when the model answered, what it said and what the
     * buffer looked like right after. Unlike {@link #bufferHistory()} this keeps
     * clean windows too, so a history can show what was <i>not</i> flagged.
     */
    public record Reading(long capturedAt, double probability, double buffer) {
    }

    private final UUID uuid;
    private volatile String name;
    private volatile Player player;
    private volatile int entityId;

    private volatile float lastYaw;
    private volatile float lastPitch;

    private final Deque<RotationSample> window = new ArrayDeque<>();
    private final Deque<Double> bufferHistory = new ArrayDeque<>();
    private final Deque<Reading> readings = new ArrayDeque<>();

    private final Deque<Double> scaleHistory = new ArrayDeque<>();

    private volatile double buffer;
    private volatile double probability;
    private volatile long lastProbabilityMs;
    private volatile long lastAttackMs;
    private volatile int windowsAnalysed;

    private volatile boolean recording;
    private volatile String recordingLabel;
    private volatile double recordingFloor = Double.NaN;
    private final List<double[]> recorded = new ArrayList<>();
    private final List<String> recordedNames = new ArrayList<>();

    private volatile boolean attackedThisTick;
    private volatile double aimErrorThisTick = Double.NaN;

    public PlayerData(Player player) {
        this.uuid = player.getUniqueId();
        bind(player);
    }

    public void bind(Player player) {
        this.name = player.getName();
        this.player = player;
        this.entityId = player.getEntityId();
        this.lastYaw = player.getLocation().getYaw();
        this.lastPitch = player.getLocation().getPitch();
    }

    public int entityId() {
        return entityId;
    }

    public float lastYaw() {
        return lastYaw;
    }

    public float lastPitch() {
        return lastPitch;
    }

    public void addSample(float yaw, float pitch, int windowSize) {
        lastYaw = yaw;
        lastPitch = pitch;
        RotationSample sample = new RotationSample(yaw, pitch, attackedThisTick, aimErrorThisTick);
        attackedThisTick = false;
        aimErrorThisTick = Double.NaN;

        synchronized (window) {
            window.addLast(sample);
            while (window.size() > windowSize) {
                window.pollFirst();
            }
        }
    }

    public List<RotationSample> snapshotWindow(int windowSize) {
        synchronized (window) {
            if (window.size() < windowSize) {
                return null;
            }
            return List.copyOf(window);
        }
    }

    public void clearWindow() {
        synchronized (window) {
            window.clear();
        }
    }

    public void markAttack(double aimError) {
        attackedThisTick = true;
        aimErrorThisTick = aimError;
        lastAttackMs = System.currentTimeMillis();
    }

    public boolean inCombat(long combatMs) {
        return System.currentTimeMillis() - lastAttackMs <= combatMs;
    }

    public void applyProbability(double value, double suspicion, double gain, double decay, double max) {
        long now = System.currentTimeMillis();
        probability = value;
        lastProbabilityMs = now;
        windowsAnalysed++;

        double distance = value - suspicion;
        buffer = Math.max(0.0D, Math.min(max,
                buffer + (distance > 0.0D ? distance * gain : distance * decay)));

        synchronized (bufferHistory) {
            bufferHistory.addLast(buffer);
            while (bufferHistory.size() > MAX_HISTORY) {
                bufferHistory.pollFirst();
            }
        }
        // The reading log deliberately survives forget, death and logout: it is
        // a record of what the model saw, not the live suspicion state.
        synchronized (readings) {
            readings.addLast(new Reading(now, value, buffer));
            while (readings.size() > MAX_READINGS) {
                readings.pollFirst();
            }
        }
    }

    public void recordScale(double scale) {
        if (scale <= 0.0D) {
            return;
        }
        synchronized (scaleHistory) {
            scaleHistory.addLast(scale);
            while (scaleHistory.size() > MAX_SCALE_HISTORY) {
                scaleHistory.pollFirst();
            }
        }
    }

    public double baselineScale() {
        List<Double> sorted;
        synchronized (scaleHistory) {
            if (scaleHistory.size() < MIN_SCALE_HISTORY) {
                return 0.0D;
            }
            sorted = new ArrayList<>(scaleHistory);
        }
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0D;
    }

    public List<Double> bufferHistory() {
        synchronized (bufferHistory) {
            return List.copyOf(bufferHistory);
        }
    }

    public List<Reading> readings() {
        synchronized (readings) {
            return List.copyOf(readings);
        }
    }

    public void recording(boolean value, String label) {
        recording(value, label, Double.NaN);
    }

    public void recording(boolean value, String label, double floor) {
        recording = value;
        recordingLabel = value ? label : null;
        recordingFloor = value ? floor : Double.NaN;
        if (!value) {
            return;
        }
        recordingPeak = 0.0;
        synchronized (recorded) {
            recorded.clear();
            recordedContext.clear();
            recordedNames.clear();
        }
    }

    public boolean filtering() {
        return recording && !Double.isNaN(recordingFloor);
    }

    public double recordingFloor() {
        return recordingFloor;
    }

    public boolean recording() {
        return recording;
    }

    public String recordingLabel() {
        return recordingLabel;
    }

    private volatile int discarded;

    public void discard() {
        discarded++;
    }

    public int discardedCount() {
        return discarded;
    }

    private final List<double[]> recordedContext = new ArrayList<>();

    private volatile double recordingPeak;

    public void record(List<String> names, double[] values) {
        record(names, values, Double.NaN, Double.NaN);
    }

    public void record(List<String> names, double[] values, double probability, double buffer) {
        synchronized (recorded) {
            if (recordedNames.isEmpty()) {
                recordedNames.addAll(names);
            }
            recorded.add(values);
            recordedContext.add(new double[]{probability, buffer});
            if (!Double.isNaN(buffer) && buffer > recordingPeak) {
                recordingPeak = buffer;
            }
        }
    }

    public List<double[]> recordedContext() {
        synchronized (recorded) {
            return List.copyOf(recordedContext);
        }
    }

    public double recordingPeak() {
        return recordingPeak;
    }

    public List<double[]> recordedSamples() {
        synchronized (recorded) {
            return List.copyOf(recorded);
        }
    }

    public List<String> recordedFeatureNames() {
        synchronized (recorded) {
            return List.copyOf(recordedNames);
        }
    }

    public int recordedCount() {
        synchronized (recorded) {
            return recorded.size();
        }
    }

    public void clearRecorded() {
        discarded = 0;
        recordingPeak = 0.0;
        synchronized (recorded) {
            recorded.clear();
            recordedContext.clear();
            recordedNames.clear();
        }
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public Player player() {
        return player;
    }

    public double buffer() {
        return buffer;
    }

    public double probability() {
        return probability;
    }

    public long lastProbabilityMs() {
        return lastProbabilityMs;
    }

    public int windowsAnalysed() {
        return windowsAnalysed;
    }

    public boolean worthKeeping() {
        return windowsAnalysed > 0
                || buffer > 0.0D
                || probability > 0.0D
                || recordedCount() > 0;
    }

    public void resetBuffer() {
        buffer = 0.0D;
        synchronized (bufferHistory) {
            bufferHistory.clear();
        }
    }

    public void resetAfterDeath() {
        clearWindow();
        resetBuffer();
        probability = 0.0D;
        lastProbabilityMs = 0L;
        lastAttackMs = 0L;
        windowsAnalysed = 0;
        attackedThisTick = false;
        aimErrorThisTick = Double.NaN;
    }
}
