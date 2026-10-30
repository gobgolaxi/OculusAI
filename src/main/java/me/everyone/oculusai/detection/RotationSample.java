package me.everyone.oculusai.detection;

/** Один тик игрока: куда смотрел, что сделал и где была цель.
 *
 *  <p>Полей стало больше, чем нужно любой текущей модели, и это нарочно.
 *  Производный канал - скорость, ускорение, квант сенсы - считается на
 *  сервисе из сырых чисел, и переопределить его можно когда угодно; дописать
 *  канал в уже записанные бои нельзя. Поэтому пишется всё, что в тике вообще
 *  видно, а что из этого читать, решает тот, кто учит.
 *
 *  @param targetAngle   угол до центра хитбокса ближайшей цели, NaN - цели нет
 *  @param targetEdgeAngle угол до края хитбокса: 0, если перекрестие на цели
 *  @param targetDistance метры до цели
 *  @param targetId      id цели, -1 - нет. В признаки идёт не он, а его смена
 *  @param targetsNear   сколько игроко-подобных рядом
 *  @param swungEmpty    махнул рукой, никого не ударив
 *  @param onGround      на земле
 *  @param sprinting     в спринте
 *  @param speed         горизонтальная скорость, блоков в тик
 *  @param sinceLastMs   миллисекунды от предыдущего тика по настоящим часам
 */
public record RotationSample(float yaw, float pitch, boolean attacked, double aimError,
                             boolean teleport, int ticksSinceAttack, AttackMoment moment,
                             double targetAngle, double targetEdgeAngle,
                             double targetDistance, int targetId, int targetsNear,
                             boolean swungEmpty, boolean onGround, boolean sprinting,
                             double speed, long sinceLastMs) {

    /** Тик без контекста цели: цели нет, флаги сняты.
     *
     *  <p>Это и есть то, чем был каждый тик до появления геометрии цели.
     *  Остаётся для тестов и для кода, которому контекст не нужен. */
    public RotationSample(float yaw, float pitch, boolean attacked, double aimError) {
        this(yaw, pitch, attacked, aimError, false, 255, null);
    }

    public RotationSample(float yaw, float pitch, boolean attacked, double aimError,
                          AttackMoment moment) {
        this(yaw, pitch, attacked, aimError, false, 255, moment);
    }

    public RotationSample(float yaw, float pitch, boolean attacked, double aimError,
                          boolean teleport, int ticksSinceAttack, AttackMoment moment) {
        this(yaw, pitch, attacked, aimError, teleport, ticksSinceAttack, moment,
                Double.NaN, Double.NaN, Double.NaN, -1, 0,
                false, true, false, 0.0D, 50L);
    }

    public boolean crit() {
        return moment != null && moment.crit();
    }
}
