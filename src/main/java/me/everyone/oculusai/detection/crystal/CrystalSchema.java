package me.everyone.oculusai.detection.crystal;

import java.util.ArrayList;
import java.util.List;
import me.everyone.oculusai.detection.Detection;

/** Схема 8: окна вокруг кристалльных событий.
 *
 * <p>Отдельный пул, а не ещё одна колонка в аимовом: аимовое окно режется
 * каждые полсекунды боя, а это - вокруг постановки или удара кристалла, и
 * смешивать их в одной модели значит учить её двум разным вещам сразу.
 *
 * <p>Тик начинается с тех же каналов, что у аима ({@link Detection#TICK_CHANNELS}):
 * поворот, удар, цель, движение. Траекторная модель уже умеет их читать, и
 * кристалльная может начать с того же разбора, добавив к нему свои каналы.
 */
public final class CrystalSchema {

    public static final int VERSION = 8;

    /** Тиков до события, которые попадают в окно. */
    public static final int PRE = 20;
    /** Тиков после события: удар по только что поставленному кристаллу
     *  обычно приходит в пределах этих десяти. */
    public static final int POST = 10;
    public static final int WINDOW = PRE + POST;

    /** Свои каналы схемы, после аимовых. */
    public static final List<String> CRYSTAL_CHANNELS = List.of(
            "crystal_placed",
            "crystal_broken",
            // Угол от взгляда до кристалла или до блока постановки в тике
            // события; -1, если события не было.
            "crystal_angle",
            "crystal_distance",
            "ticks_since_place",
            "ticks_since_break",
            "slot_switched",
            // Урон от взрывов кристаллов, которые этот игрок разбил: по
            // другим и по себе. Чит выбирает место выгоднее человека.
            "damage_dealt",
            "damage_taken",
            "totem_popped");

    public static final List<String> TICK_CHANNELS = channels();

    /** Сводка окна - колонки CSV. Модель по тикам их не читает; они нужны
     *  колоночным кандидатам и чтобы запись можно было прочитать глазами. */
    public static final List<String> COLUMNS = List.of(
            "crystal_places",
            "crystal_breaks",
            "place_to_break_min",
            "place_angle_max",
            "break_angle_max",
            "slot_switches",
            "damage_dealt",
            "damage_taken",
            "totem_pops");

    private static List<String> channels() {
        List<String> all = new ArrayList<>(Detection.TICK_CHANNELS);
        all.addAll(CRYSTAL_CHANNELS);
        return List.copyOf(all);
    }

    private CrystalSchema() {
    }
}
