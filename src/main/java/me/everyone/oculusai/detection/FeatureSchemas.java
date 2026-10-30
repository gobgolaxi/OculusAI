package me.everyone.oculusai.detection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Две схемы записи, и больше никаких.
 *
 *  <p>Схем было пять - v2, v3, v4, v5, v6, - и четыре из них были шагами
 *  одной и той же дороги: v3 добавила колонки крита, v4 - бегущие средние,
 *  v5 убрала колонку, которая мерила окно, а не руку, v6 взяла колонки v5 и
 *  расширила пул. Каждая следующая умела всё, что предыдущая, и выбор между
 *  ними был выбором между "как сейчас" и "как было в июле". Теперь их две, и
 *  они различаются не набором колонок - он один и тот же, - а тем, что лежит
 *  под строкой:
 *
 *  <ul>
 *    <li>{@link #V1} - только колонки. Сводка окна: семнадцать чисел о том,
 *        как шло вращение, плюс колонки удара и бегущие статистики сессии.
 *        Это вход колоночных моделей, и в этот пул попадает всякая запись,
 *        какая когда-либо делалась;</li>
 *    <li>{@link #V2} - те же колонки плюс сырые тики окна, обязательно.
 *        Это вход траекторной модели, которая читает сам ход руки, а не его
 *        среднее. Запись без тиков в этот пул не попадает: строки без тиков
 *        у такой модели нет входа вовсе.</li>
 *  </ul>
 *
 *  <p>Колонки у схем совпадают намеренно. Одна запись уходит сразу в обе
 *  (см. {@code DatasetStore.SCHEMA_BOTH}), и копия в V1 - это не производная
 *  арифметика, а те же числа того же окна: пул колоночных моделей растёт от
 *  новых записей так же, как пул траекторной. */
public final class FeatureSchemas {

    /** Колонки окна. Вход колоночных моделей. */
    public static final int V1 = 1;
    /** Те же колонки и сырые тики под каждой строкой. Вход траекторной. */
    public static final int V2 = 2;

    /** Всё, что читается из вращения.
     *
     *  <p>Не схема, а база бегущих статистик: {@code RunningStats} ведёт
     *  среднее и разброс по этим именам, и {@code Detection} по ним же
     *  собирает вектор. */
    public static final String[] AIM = {
            "scale_vs_baseline",
            "yaw_std_rel",
            "pitch_mean_rel",
            "pitch_std_rel",
            "yaw_accel_rel",
            "pitch_accel_rel",
            "pitch_span_rel",
            "smoothness",
            "snap_ratio",
            "zero_ratio",
            "axis_correlation",
            "quanta_per_tick",
            "quantum_error",
            "attacks_per_second",
            "aim_error_mean",
            "aim_error_std",
            "aim_error_min",
    };

    /** Что видно в самом ударе: криты, падение, ритм свингов, спринт. */
    public static final String[] HIT = {
            "crit_rate",
            "crit_share",
            "crit_streak_share",
            "crit_fall_mean",
            "crit_fall_min",
            "crit_air_ticks_mean",
            "crit_air_ticks_std",
            "crit_air_ticks_mode_share",
            "crit_fall_quantum_error",
            "crit_impossible_ratio",
            "crit_aim_error_delta",
            "hit_interval_mean",
            "hit_interval_std",
            "sprint_release_share",
    };

    /** Колонки вращения без {@code attacks_per_second}.
     *
     *  <p>Эта колонка считала свинги внутри двадцатитикового окна, у которого
     *  медиана равна единице: она мерила окно и порог min-attacks, а не руку.
     *  Выброшена, и возвращать её как колонку, отсутствующую у всех свежих
     *  записей, значило бы купить шум. */
    public static final String[] AIM_COLUMNS = aimWithout("attacks_per_second");

    private static String[] aimWithout(String dropped) {
        List<String> kept = new ArrayList<>();
        for (String name : AIM) {
            if (!name.equals(dropped)) {
                kept.add(name);
            }
        }
        return kept.toArray(new String[0]);
    }

    private FeatureSchemas() {
    }

    /** Колонки схемы, в порядке записи.
     *
     *  <p>У обеих схем он один и тот же: вращение, удар, затем бегущие
     *  среднее и разброс по колонкам вращения. */
    public static List<String> names(int version) {
        List<String> names = new ArrayList<>(List.of(AIM_COLUMNS));
        names.addAll(List.of(HIT));
        for (String name : AIM_COLUMNS) {
            names.add(name + "_run_mean");
        }
        for (String name : AIM_COLUMNS) {
            names.add(name + "_run_std");
        }
        return names;
    }

    public static boolean known(int version) {
        return version == V1 || version == V2;
    }

    /** Требует ли схема сырых тиков под каждой строкой. */
    public static boolean needsTicks(int version) {
        return version == V2;
    }

    public static Map<String, Double> slice(Map<String, Double> features, int version) {
        Map<String, Double> cut = new LinkedHashMap<>();
        for (String name : names(version)) {
            Double value = features.get(name);
            cut.put(name, value == null ? 0.0D : value);
        }
        return cut;
    }
}
