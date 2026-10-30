package me.everyone.oculusai.detection;

import me.everyone.oculusai.player.EntityTracker;

/** Где было перекрестие относительно цели - в каждом тике, а не только в ударе.
 *
 *  <p>Самый сильный признак доводки прицела - не само вращение, а угол от
 *  перекрестия до хитбокса и то, как он менялся. У человека ошибка падает
 *  неровно и перелетает за цель; у аимбота она схлопывается в ноль за один тик
 *  и там держится. Различает их именно кривая, а не её последнее значение.
 *
 *  <p>До появления этого класса такой угол считался в двух местах - по одному
 *  в каждом мостике версии, - и только в тике атаки, потому что цель написана
 *  в пакете атаки. Здесь он один, и считается он каждый тик: по ближайшему
 *  игроко-подобному, когда никто ничего не сказал о цели.
 *
 *  <p>Ничего не кэширует и ни на что не ссылается: это арифметика над тем, что
 *  трекер уже знает. Вызывается из мостика на каждый пакет движения, то есть
 *  двадцать раз в секунду на игрока в бою, поэтому здесь нет ни аллокаций в
 *  цикле, ни обращений к Bukkit. */
public final class AimGeometry {

    /** Насколько далеко вообще искать цель.
     *
     *  <p>Дальше этого рука уже не ведётся по игроку: дальность удара в
     *  ваниле около трёх блоков, и запас здесь на то, чтобы видеть подход и
     *  доводку до удара, а не только сам удар. Слишком большой радиус вреден -
     *  он начинает подбирать того, кто просто стоит в стороне, и канал смены
     *  цели превращается в шум. */
    public static final double SEARCH_BLOCKS = 12.0D;

    /** Что в этом тике знал бы о цели тот, кто смотрит на экран. */
    public record TickAim(
            /** Угол до центра хитбокса, градусы. NaN - цели рядом нет. */
            double centreAngle,
            /** Угол до ближайшей точки хитбокса: 0, если перекрестие уже на
             *  цели. Это та величина, которую аимбот фактически и
             *  минимизирует, и у неё есть дно - в отличие от угла до центра,
             *  который у стоящего в упор велик даже при идеальном прицеле. */
            double edgeAngle,
            /** Метры до цели. Угловые величины без неё не читаются: один
             *  градус в упор и один градус с десяти блоков - это разный
             *  промах в блоках. */
            double distance,
            /** Id цели, или -1. Сам в признаки не идёт - по нему считается
             *  смена цели. */
            int targetId,
            /** Сколько игроко-подобных рядом, кроме себя. */
            int targetsNear,
            /** Горизонтальная скорость, блоков в тик. Страйф-флик легита и
             *  доводка выглядят одинаково, пока не видно, что под игроком
             *  едет земля: человек крутит камеру, потому что бежит боком. */
            double speed) {

        public static final TickAim NONE =
                new TickAim(Double.NaN, Double.NaN, Double.NaN, -1, 0, 0.0D);
    }

    private AimGeometry() {
    }

    /** Угол между взглядом и направлением на точку, градусы.
     *
     *  <p>Это та же арифметика, что считала {@code aimError} в мостиках, и
     *  она здесь затем, чтобы определение было одно: угол до центра хитбокса
     *  в тике удара и он же в остальных тиках должны быть одним числом, иначе
     *  модель учится на стыке двух определений. */
    public static double angleTo(EntityTracker.Tracked self, EntityTracker.Tracked target,
                                 double yawDegrees, double pitchDegrees) {
        if (self == null || target == null) {
            return Double.NaN;
        }
        double eyeY = self.y() + self.height() * EYE_RATIO;
        double toX = target.x() - self.x();
        double toY = target.centreY() - eyeY;
        double toZ = target.z() - self.z();
        double length = Math.sqrt(toX * toX + toY * toY + toZ * toZ);
        if (length < 0.1D) {
            return Double.NaN;
        }

        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double cosPitch = Math.cos(pitch);
        double lookX = -cosPitch * Math.sin(yaw);
        double lookY = -Math.sin(pitch);
        double lookZ = cosPitch * Math.cos(yaw);

        double dot = (toX * lookX + toY * lookY + toZ * lookZ) / length;
        return Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, dot))));
    }

    /** Весь тиковый контекст прицела по ближайшей цели. */
    public static TickAim of(EntityTracker tracker, int selfId,
                             double yawDegrees, double pitchDegrees) {
        if (tracker == null) {
            return TickAim.NONE;
        }
        EntityTracker.Tracked self = tracker.get(selfId);
        if (self == null) {
            return TickAim.NONE;
        }
        double speed = speedOf(tracker, selfId);
        EntityTracker.Near near = tracker.nearestPlayerLike(self, selfId, SEARCH_BLOCKS);
        if (near == null) {
            return new TickAim(Double.NaN, Double.NaN, Double.NaN, -1, 0, speed);
        }

        double centre = angleTo(self, near.entry(), yawDegrees, pitchDegrees);
        return new TickAim(centre,
                edgeAngle(centre, near.entry(), near.distance()),
                near.distance(),
                near.entityId(),
                tracker.countPlayerLike(self, selfId, SEARCH_BLOCKS),
                speed);
    }

    /** Горизонтальная скорость по двум последним позициям трекера.
     *
     *  <p>По истории трекера, а не по {@code player.getVelocity()}: вектор
     *  скорости у Bukkit - это то, что сервер собирается применить, а здесь
     *  нужно то, что игрок уже прошёл. Нормируется на тик, потому что между
     *  выборками трекера не ровно 50 мс, и делить надо на настоящее время. */
    private static double speedOf(EntityTracker tracker, int selfId) {
        java.util.List<EntityTracker.Tracked> recent = tracker.recent(selfId);
        if (recent.size() < 2) {
            return 0.0D;
        }
        EntityTracker.Tracked now = recent.get(recent.size() - 1);
        EntityTracker.Tracked before = recent.get(recent.size() - 2);
        long elapsed = now.at() - before.at();
        if (elapsed <= 0L) {
            return 0.0D;
        }
        double dx = now.x() - before.x();
        double dz = now.z() - before.z();
        return Math.sqrt(dx * dx + dz * dz) * 50.0D / elapsed;
    }

    /** Угол до края хитбокса: угол до центра минус его угловой радиус.
     *
     *  <p>Приближение, и сознательное. Точный ответ - угол между взглядом и
     *  ближайшей точкой параллелепипеда, и он требует проекции луча на три
     *  плоскости; разница с этим приближением есть только когда перекрестие
     *  стоит у самого угла хитбокса, то есть там, где обе величины и так
     *  близки к нулю. Угловой радиус берётся по половине ширины, а не по
     *  диагонали: ширина - это то, что надо накрыть по горизонтали, а по
     *  вертикали цель выше, чем шире, и завышенный радиус обнулял бы угол
     *  тогда, когда рука на цель ещё не наведена. */
    private static double edgeAngle(double centreAngle, EntityTracker.Tracked target,
                                    double distance) {
        if (Double.isNaN(centreAngle) || distance < 0.1D) {
            return Double.NaN;
        }
        double radius = Math.toDegrees(Math.atan2(target.width() / 2.0D, distance));
        return Math.max(0.0D, centreAngle - radius);
    }

    /** Та же доля, что в трекере: глаза относительно высоты хитбокса. */
    private static final double EYE_RATIO = 1.62D / 1.8D;
}
