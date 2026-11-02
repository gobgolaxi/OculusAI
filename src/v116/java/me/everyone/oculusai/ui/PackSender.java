package me.everyone.oculusai.ui;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/** Отправка ресурспака на 1.16.5: у клиента один серверный пак, подсказки и
 *  обязательности API этой версии ещё не знает. Пак сервера, если он есть,
 *  этим заменяется - поэтому по умолчанию пак шлётся только на арене. */
public final class PackSender {

    public static void send(Player player, UUID id, String url, byte[] hash,
                            String prompt, boolean required) {
        if (hash == null) {
            player.setResourcePack(url);
        } else {
            player.setResourcePack(url, hash);
        }
    }

    /** Пак один, так что любой ответ - про наш. */
    public static boolean ours(PlayerResourcePackStatusEvent event, UUID id) {
        return true;
    }

    private PackSender() {
    }
}
