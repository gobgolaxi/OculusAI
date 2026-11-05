package me.everyone.oculusai.detection.crystal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import me.everyone.oculusai.detection.Detection;
import me.everyone.oculusai.detection.RotationSample;

/** Кристалльная история одного игрока: последние тики и окна вокруг событий.
 *
 * <p>События (постановка, удар, смена слота, урон, тотем) приходят из
 * обработчиков Bukkit, а тик - из пакета движения, то есть из разных
 * потоков. Всё состояние под одним замком: события копятся до ближайшего
 * тика и записываются в него.
 *
 * <p>Окно - {@link CrystalSchema#PRE} тиков до события и {@link CrystalSchema#POST}
 * после. Событие, случившееся, пока окно ещё дописывается, попадает в это же
 * окно, а не открывает новое: иначе серия «поставил - ударил - поставил»
 * давала бы десяток почти одинаковых окон.
 */
public final class CrystalTimeline {

    private static final double NONE = Detection.NO_VALUE;
    private static final int CHANNELS = CrystalSchema.TICK_CHANNELS.size();
    private static final int AIM = Detection.TICK_CHANNELS.size();

    private final Deque<double[]> frames = new ArrayDeque<>();
    private final List<double[]> rows = new ArrayList<>();
    private final List<double[]> ticks = new ArrayList<>();

    private boolean placed;
    private boolean broken;
    private double angle = Double.NaN;
    private double distance = Double.NaN;
    private boolean slot;
    private double dealt;
    private double taken;
    private boolean totem;
    private int sincePlace = 255;
    private int sinceBreak = 255;
    /** Сколько тиков осталось дописать в открытое окно; -1 - окна нет. */
    private int remaining = -1;
    private boolean recording;

    public synchronized void placed(double angleDegrees, double distanceBlocks) {
        placed = true;
        angle = angleDegrees;
        distance = distanceBlocks;
        open();
    }

    public synchronized void broken(double angleDegrees, double distanceBlocks) {
        broken = true;
        angle = angleDegrees;
        distance = distanceBlocks;
        open();
    }

    public synchronized void slotSwitched() {
        slot = true;
    }

    public synchronized void damageDealt(double amount) {
        dealt += Math.max(0.0D, amount);
    }

    public synchronized void damageTaken(double amount) {
        taken += Math.max(0.0D, amount);
    }

    public synchronized void totemPopped() {
        totem = true;
    }

    private void open() {
        if (recording && remaining < 0) {
            remaining = CrystalSchema.POST;
        }
    }

    /** Тик игрока: аимовые каналы из сэмпла плюс всё, что накопилось. */
    public synchronized void onTick(RotationSample sample) {
        double[] frame = new double[CHANNELS];
        Detection.tickRow(sample, frame, 0);
        sincePlace = placed ? 0 : Math.min(255, sincePlace + 1);
        sinceBreak = broken ? 0 : Math.min(255, sinceBreak + 1);
        int at = AIM;
        frame[at++] = placed ? 1.0D : 0.0D;
        frame[at++] = broken ? 1.0D : 0.0D;
        frame[at++] = Double.isFinite(angle) ? angle : NONE;
        frame[at++] = Double.isFinite(distance) ? distance : NONE;
        frame[at++] = sincePlace;
        frame[at++] = sinceBreak;
        frame[at++] = slot ? 1.0D : 0.0D;
        frame[at++] = dealt;
        frame[at++] = taken;
        frame[at] = totem ? 1.0D : 0.0D;

        placed = false;
        broken = false;
        angle = Double.NaN;
        distance = Double.NaN;
        slot = false;
        dealt = 0.0D;
        taken = 0.0D;
        totem = false;

        frames.addLast(frame);
        while (frames.size() > CrystalSchema.WINDOW) {
            frames.pollFirst();
        }
        if (remaining < 0) {
            return;
        }
        if (remaining > 0) {
            remaining--;
            return;
        }
        remaining = -1;
        // Окно короче полного - это событие в первые секунды после входа или
        // после сброса; такая строка учила бы модель на обрезке.
        if (recording && frames.size() == CrystalSchema.WINDOW) {
            emit();
        }
    }

    private void emit() {
        double[] flat = new double[CrystalSchema.WINDOW * CHANNELS];
        int at = 0;
        for (double[] frame : frames) {
            System.arraycopy(frame, 0, flat, at, CHANNELS);
            at += CHANNELS;
        }
        ticks.add(flat);
        rows.add(summary(frames));
    }

    /** Колонки {@link CrystalSchema#COLUMNS} по кадрам окна. */
    static double[] summary(Iterable<double[]> window) {
        double places = 0;
        double breaks = 0;
        double placeToBreak = Double.MAX_VALUE;
        double placeAngle = NONE;
        double breakAngle = NONE;
        double switches = 0;
        double dealtSum = 0;
        double takenSum = 0;
        double totems = 0;
        int index = 0;
        int lastPlace = -1;
        for (double[] frame : window) {
            boolean place = frame[AIM] > 0.5D;
            boolean hit = frame[AIM + 1] > 0.5D;
            double angleHere = frame[AIM + 2];
            if (place) {
                places++;
                lastPlace = index;
                placeAngle = Math.max(placeAngle, angleHere);
            }
            if (hit) {
                breaks++;
                breakAngle = Math.max(breakAngle, angleHere);
                if (lastPlace >= 0) {
                    placeToBreak = Math.min(placeToBreak, index - lastPlace);
                }
            }
            switches += frame[AIM + 6];
            dealtSum += frame[AIM + 7];
            takenSum += frame[AIM + 8];
            totems += frame[AIM + 9];
            index++;
        }
        return new double[]{
                places, breaks,
                placeToBreak == Double.MAX_VALUE ? NONE : placeToBreak,
                placeAngle, breakAngle, switches, dealtSum, takenSum, totems};
    }

    public synchronized void recording(boolean value) {
        recording = value;
        remaining = -1;
        if (value) {
            rows.clear();
            ticks.clear();
        }
    }

    public synchronized boolean recording() {
        return recording;
    }

    public synchronized int recorded() {
        return rows.size();
    }

    /** Записанные окна: [колонки, тики] - и очистка. */
    public synchronized List<List<double[]>> drain() {
        List<List<double[]>> out = List.of(new ArrayList<>(rows), new ArrayList<>(ticks));
        rows.clear();
        ticks.clear();
        return out;
    }

    public synchronized void reset() {
        frames.clear();
        remaining = -1;
        sincePlace = 255;
        sinceBreak = 255;
    }
}
