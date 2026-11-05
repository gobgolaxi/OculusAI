package me.everyone.oculusai.check;

import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.PlayerData;

/** Neuro (Crystal): кристалльная модель, CrystalAura и AutoCrystal.
 *
 * <p>Повторяет аимовую проверку, только смотрит на свой буфер: его наполняют
 * показания модели кристалльного слота по окнам вокруг постановок и ударов
 * по кристаллам. Зовётся только на кристалльное предсказание - см.
 * {@link Checks#onCrystalPrediction} - чтобы окна кристаллов не считались
 * подтверждениями аимового алерта и наоборот.
 *
 * <p>Пороги буфера - те же, что у аима: кристалльная модель калибруется тем
 * же способом и к той же доле читеров, так что её 0.9 значит то же.
 */
public final class CrystalCheck implements PlayerCheck {

    private final OculusAI plugin;

    public CrystalCheck(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "crystal";
    }

    @Override
    public CheckResult test(PlayerData data) {
        if (!plugin.getConfig().getBoolean(enabledPath(), true)
                || data.crystal().buffer() < plugin.config().alertAt()) {
            return CheckResult.pass();
        }
        return CheckResult.fail(plugin.lang().textOr("check.reason.crystal", "crystal model above the line"));
    }
}
