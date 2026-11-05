package me.everyone.oculusai.check;

import me.everyone.oculusai.OculusAI;

/** CrystalPredict: удар по кристаллу, о котором клиент ещё не мог знать.
 *
 * <p>Кристалл появляется у клиента только с пакетом появления. Честный
 * клиент не может ударить сущность, которой у него нет, а AutoCrystal с
 * «предсказанием ID» бьёт кристалл в тот же тик, что ставит, угадав его номер
 * - раньше, чем сервер успел о нём сообщить. Плагин помнит, когда пакет
 * появления ушёл этому игроку и когда от него пришёл удар; удар раньше пакета
 * - срабатывание. Без нейросети: это не оценка, а факт о порядке пакетов.
 *
 * <p>Два подтверждения подряд по умолчанию: один такой удар может дать
 * редкий сбой учёта пакетов, серию - только предсказание.
 */
public final class CrystalPredictCheck implements Check {

    private final OculusAI plugin;

    public CrystalPredictCheck(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "crystalpredict";
    }

    @Override
    public int confirmations() {
        return Math.max(1, plugin.getConfig().getInt("checks." + id() + ".confirmations", 2));
    }

    @Override
    public boolean testOnly() {
        return plugin.getConfig().getBoolean("checks." + id() + ".test-only", false);
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean(enabledPath(), true);
    }

    CheckResult result() {
        return CheckResult.fail(plugin.lang().textOr("check.reason.crystalpredict",
                "hit a crystal before the client could see it"));
    }
}
