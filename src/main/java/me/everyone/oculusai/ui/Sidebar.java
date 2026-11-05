package me.everyone.oculusai.ui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/** Сайдбар одного игрока, который обновляется на месте.
 *
 * <p>Раньше каждое обновление собирало новый скорборд и подменяло им старый
 * ({@code getNewScoreboard} и {@code setScoreboard}): на время подмены
 * сайдбар у клиента исчезал, и при частом обновлении он мерцал. Здесь доска
 * одна на всё время, а строки не удаляются и не создаются заново:
 *
 * <ul>
 *   <li>у каждой строки постоянная запись - невидимый уникальный цветовой
 *       код - и своя команда; текст строки - префикс команды;</li>
 *   <li>префикс и заголовок меняются, только если текст правда другой, так
 *       что неизменившиеся строки не шлют клиенту ни одного пакета;</li>
 *   <li>лишние строки при укорачивании списка снимаются по одной.</li>
 * </ul>
 *
 * <p>Только для Paper: на Folia скорбордов нет, и вызывающий это проверяет.
 */
public final class Sidebar {

    /** Больше строк сайдбар клиента не показывает. */
    public static final int MAX_LINES = 15;
    /** Длина префикса команды, которую принимает сервер на всех версиях. */
    private static final int PREFIX_LIMIT = 64;

    private final Scoreboard board;
    private final Objective objective;
    private final List<String> shown = new ArrayList<>();
    private String title;

    public Sidebar(String title) {
        this.board = Bukkit.getScoreboardManager().getNewScoreboard();
        this.objective = board.registerNewObjective("oai_side", "dummy", title);
        this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        this.title = title;
    }

    /** Ставит доску игроку, если у него сейчас другая. */
    public void show(Player player) {
        if (player.getScoreboard() != board) {
            player.setScoreboard(board);
        }
    }

    public void title(String value) {
        if (!value.equals(title)) {
            objective.setDisplayName(value);
            title = value;
        }
    }

    /** Строки сверху вниз. */
    public void lines(List<String> lines) {
        int count = Math.min(lines.size(), MAX_LINES);
        List<String> next = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String text = lines.get(i);
            if (text.length() > PREFIX_LIMIT) {
                text = text.substring(0, PREFIX_LIMIT);
            }
            next.add(text);
            String entry = entry(i);
            Team team = team(i, entry);
            if (i >= shown.size() || !text.equals(shown.get(i))) {
                team.setPrefix(text);
            }
            // Счёт задаёт порядок: верхняя строка - наибольший. Пишется на
            // каждом вызове, но клиенту уходит только при изменении.
            int score = count - i;
            if (!objective.getScore(entry).isScoreSet() || objective.getScore(entry).getScore() != score) {
                objective.getScore(entry).setScore(score);
            }
        }
        for (int i = count; i < shown.size(); i++) {
            board.resetScores(entry(i));
        }
        shown.clear();
        shown.addAll(next);
    }

    private Team team(int index, String entry) {
        String name = "oai_line_" + index;
        Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
            team.addEntry(entry);
        }
        return team;
    }

    /** Невидимая уникальная запись строки: цветовой код и сброс. */
    private static String entry(int index) {
        return ChatColor.values()[index].toString() + ChatColor.RESET;
    }
}
