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
            "totem_popped",
            // Наводка между событиями: угол и расстояние до ближайшего
            // кристалла (из пакетов появления, которые получил игрок) или
            // заряженного якоря - в каждом тике, а не только в тике удара.
            // Нет источника рядом - -1.
            "blast_angle",
            "blast_distance",
            // Якоря возрождения: поставил, зарядил светокамнем, подорвал.
            "anchor_placed",
            "anchor_charged",
            "anchor_exploded",
            // Удар по кристаллу, пакет появления которого ещё не ушёл
            // клиенту: клиент не мог его видеть (ID predict).
            "unseen_hit");

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
            "totem_pops",
            "anchor_charges",
            "anchor_explosions",
            // Средний угол до ближайшего источника взрыва по тикам, где он
            // был рядом: чит держит его на нуле, рука - нет.
            "blast_angle_mean",
            "unseen_hits");

    private static List<String> channels() {
        List<String> all = new ArrayList<>(Detection.TICK_CHANNELS);
        all.addAll(CRYSTAL_CHANNELS);
        return List.copyOf(all);
    }

    private CrystalSchema() {
    }
}
