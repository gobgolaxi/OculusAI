package me.everyone.oculusai.detection.crystal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.oculusai.detection.Detection;
import me.everyone.oculusai.detection.RotationSample;
import me.everyone.oculusai.detection.SuspicionBuffer;

/** Кристалльная история одного игрока: последние тики и окна вокруг событий.
 *
 * <p>События (кристаллы, якоря, смена слота, урон, тотем) приходят из
 * обработчиков Bukkit, тик - из пакета движения, появление кристаллов у
 * клиента - из исходящих пакетов, то есть из разных потоков. Всё состояние
 * под одним замком: события копятся до ближайшего тика и записываются в него.
 *
 * <p>Окно - {@link CrystalSchema#PRE} тиков до события и {@link CrystalSchema#POST}
 * после. Событие, случившееся, пока окно ещё дописывается, попадает в это же
 * окно, а не открывает новое: иначе серия «поставил - ударил - поставил»
 * давала бы десяток почти одинаковых окон.
 */
public final class CrystalTimeline {

    /** Куда уходит готовое окно, кроме записи: на предсказание. Зовётся под
     *  замком таймлайна из потока пакетов - получатель не должен ждать. */
    public interface Sink {
        void window(double[] columns, double[] ticks);
    }

    /** Заряженные якоря всех миров: «мир:x:y:z» -> центр блока. Общие на всех
     *  игроков - якорь блок, а не сущность, и пакета появления у него нет. */
    private static final Map<String, Anchor> ANCHORS = new ConcurrentHashMap<>();

    private record Anchor(String world, double x, double y, double z) {
    }

    private static final double NONE = Detection.NO_VALUE;
    private static final int CHANNELS = CrystalSchema.TICK_CHANNELS.size();
    private static final int AIM = Detection.TICK_CHANNELS.size();
    /** Дальше этого источник взрыва для наводки не считается: так далеко
     *  кристаллы не бьют и не ставят. */
    private static final double BLAST_RANGE = 12.0D;
    /** Сколько помнится, когда кристалл появился у клиента и когда пришёл
     *  удар по нему. Удар приходит в пределах секунды. */
    private static final long MEMORY_MS = 10_000L;
    private static final int MEMORY_LIMIT = 512;

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
    private boolean anchorPlaced;
    private boolean anchorCharged;
    private boolean anchorExploded;
    private boolean unseenHit;
    private int sincePlace = 255;
    private int sinceBreak = 255;
    /** Сколько тиков осталось дописать в открытое окно; -1 - окна нет. */
    private int remaining = -1;
    private boolean recording;
    private Sink sink;
    /** Последнее показание кристалльной модели и буфер подозрения по нему -
     *  свои, отдельно от аимовых. */
    private double probability = Double.NaN;
    private double buffer;

    /** Кристаллы, о которых клиенту сказали: id -> x, y, z. */
    private final Map<Integer, double[]> crystals = new HashMap<>();
    /** Когда сущность с этим id появилась у клиента (пакет ушёл), мс. */
    private final Map<Integer, Long> seenAt = new HashMap<>();
    /** Когда от клиента пришёл удар по этому id, мс. */
    private final Map<Integer, Long> attackAt = new HashMap<>();
    /** Сколько пакетов появления вообще видел слушатель: ноль - он не
     *  работает, и судить по отсутствию пакета нельзя. */
    private long spawnsSeen;

    private boolean hasLook;
    private String world = "";
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private double yaw;
    private double pitch;

    // -- события ------------------------------------------------------------

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

    public synchronized void anchorPlaced(double angleDegrees, double distanceBlocks) {
        anchorPlaced = true;
        angle = angleDegrees;
        distance = distanceBlocks;
        open();
    }

    public synchronized void anchorCharged(double angleDegrees, double distanceBlocks) {
        anchorCharged = true;
        angle = angleDegrees;
        distance = distanceBlocks;
        open();
    }

    public synchronized void anchorExploded(double angleDegrees, double distanceBlocks) {
        anchorExploded = true;
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
        if ((recording || sink != null) && remaining < 0) {
            remaining = CrystalSchema.POST;
        }
    }

    // -- что видел клиент -----------------------------------------------------

    /** Исходящий пакет появления сущности этому игроку. Позиция - только у
     *  кристаллов, остальное нужно лишь для «видел ли клиент этот id». */
    public synchronized void spawned(int entityId, boolean crystal, double x, double y, double z) {
        long now = System.currentTimeMillis();
        spawnsSeen++;
        seenAt.put(entityId, now);
        if (crystal) {
            crystals.put(entityId, new double[]{x, y, z});
        }
        prune(now);
    }

    public synchronized void destroyed(int entityId) {
        crystals.remove(entityId);
    }

    /** Пришёл удар по сущности - любой, пока не ясно, кристалл ли это. */
    public synchronized void attackArrived(int entityId) {
        long now = System.currentTimeMillis();
        attackAt.putIfAbsent(entityId, now);
        prune(now);
    }

    /** Ударил ли клиент этот кристалл раньше, чем мог о нём узнать. Не
     *  судит, пока слушатель пакетов не видел ни одного появления. */
    public synchronized boolean hitBeforeSeen(int entityId) {
        Long attack = attackAt.get(entityId);
        if (attack == null || spawnsSeen == 0) {
            return false;
        }
        Long seen = seenAt.get(entityId);
        boolean unseen = seen == null || seen > attack;
        if (unseen) {
            unseenHit = true;
        }
        return unseen;
    }

    private void prune(long now) {
        if (seenAt.size() > MEMORY_LIMIT) {
            seenAt.values().removeIf(at -> now - at > MEMORY_MS);
        }
        if (attackAt.size() > MEMORY_LIMIT) {
            attackAt.values().removeIf(at -> now - at > MEMORY_MS);
        }
    }

    /** Где глаза и куда смотрит - из пакета движения, до тика. */
    public synchronized void look(String worldName, double x, double y, double z,
                                  double yawDegrees, double pitchDegrees) {
        hasLook = true;
        world = worldName;
        eyeX = x;
        eyeY = y;
        eyeZ = z;
        yaw = yawDegrees;
        pitch = pitchDegrees;
    }

    public static void anchorArmed(String world, int blockX, int blockY, int blockZ) {
        ANCHORS.put(key(world, blockX, blockY, blockZ),
                new Anchor(world, blockX + 0.5D, blockY + 0.5D, blockZ + 0.5D));
    }

    public static void anchorGone(String world, int blockX, int blockY, int blockZ) {
        ANCHORS.remove(key(world, blockX, blockY, blockZ));
    }

    public static void clearAnchors() {
        ANCHORS.clear();
    }

    private static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    /** Угол и расстояние до ближайшего источника взрыва; [-1, -1] - нет. */
    private double[] nearestBlast() {
        if (!hasLook) {
            return new double[]{NONE, NONE};
        }
        double bestAngle = NONE;
        double bestDistance = Double.MAX_VALUE;
        for (double[] at : crystals.values()) {
            // Кристалл стоит на блоке; целятся в его середину - на блок выше.
            double[] candidate = angleTo(at[0], at[1] + 1.0D, at[2]);
            if (candidate[1] < bestDistance) {
                bestDistance = candidate[1];
                bestAngle = candidate[0];
            }
        }
        for (Iterator<Anchor> it = ANCHORS.values().iterator(); it.hasNext(); ) {
            Anchor anchor = it.next();
            if (!anchor.world().equals(world)) {
                continue;
            }
            double[] candidate = angleTo(anchor.x(), anchor.y(), anchor.z());
            if (candidate[1] < bestDistance) {
                bestDistance = candidate[1];
                bestAngle = candidate[0];
            }
        }
        if (bestDistance > BLAST_RANGE) {
            return new double[]{NONE, NONE};
        }
        return new double[]{bestAngle, bestDistance};
    }

    private double[] angleTo(double x, double y, double z) {
        double toX = x - eyeX;
        double toY = y - eyeY;
        double toZ = z - eyeZ;
        double length = Math.sqrt(toX * toX + toY * toY + toZ * toZ);
        if (length < 0.1D) {
            return new double[]{0.0D, length};
        }
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        double dot = (toX * -cosPitch * Math.sin(yawRad) + toY * -Math.sin(pitchRad)
                + toZ * cosPitch * Math.cos(yawRad)) / length;
        return new double[]{Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, dot)))), length};
    }

    // -- тик --------------------------------------------------------------

    /** Тик игрока: аимовые каналы из сэмпла плюс всё, что накопилось. */
    public synchronized void onTick(RotationSample sample) {
        double[] frame = new double[CHANNELS];
        Detection.tickRow(sample, frame, 0);
        sincePlace = placed ? 0 : Math.min(255, sincePlace + 1);
        sinceBreak = broken ? 0 : Math.min(255, sinceBreak + 1);
        double[] blast = nearestBlast();
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
        frame[at++] = totem ? 1.0D : 0.0D;
        frame[at++] = blast[0];
        frame[at++] = blast[1];
        frame[at++] = anchorPlaced ? 1.0D : 0.0D;
        frame[at++] = anchorCharged ? 1.0D : 0.0D;
        frame[at++] = anchorExploded ? 1.0D : 0.0D;
        frame[at] = unseenHit ? 1.0D : 0.0D;

        placed = false;
        broken = false;
        angle = Double.NaN;
        distance = Double.NaN;
        slot = false;
        dealt = 0.0D;
        taken = 0.0D;
        totem = false;
        anchorPlaced = false;
        anchorCharged = false;
        anchorExploded = false;
        unseenHit = false;

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
        if ((recording || sink != null) && frames.size() == CrystalSchema.WINDOW) {
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
        double[] row = summary(frames);
        if (recording) {
            ticks.add(flat);
            rows.add(row);
        }
        if (sink != null) {
            sink.window(row, flat);
        }
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
        double charges = 0;
        double explosions = 0;
        double blastSum = 0;
        int blastTicks = 0;
        double unseen = 0;
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
            if (frame[AIM + 10] >= 0.0D) {
                blastSum += frame[AIM + 10];
                blastTicks++;
            }
            charges += frame[AIM + 13];
            explosions += frame[AIM + 14];
            unseen += frame[AIM + 15];
            index++;
        }
        return new double[]{
                places, breaks,
                placeToBreak == Double.MAX_VALUE ? NONE : placeToBreak,
                placeAngle, breakAngle, switches, dealtSum, takenSum, totems,
                charges, explosions,
                blastTicks == 0 ? NONE : blastSum / blastTicks,
                unseen};
    }

    // -- запись и предсказание ------------------------------------------------

    /** Подключает предсказание; null - отключает. */
    public synchronized void sink(Sink value) {
        sink = value;
    }

    public synchronized boolean predicting() {
        return sink != null;
    }

    /** Показание модели в буфер - та же арифметика, что у аима. */
    public synchronized void applyProbability(double value, double suspicion, double gain,
                                              double decay, double leak, double max,
                                              double maxStep) {
        probability = value;
        buffer = SuspicionBuffer.add(buffer, value, suspicion, gain, decay, leak, max, maxStep);
    }

    public synchronized double probability() {
        return probability;
    }

    public synchronized double buffer() {
        return buffer;
    }

    public synchronized void resetBuffer() {
        buffer = 0.0D;
        probability = Double.NaN;
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
