package me.everyone.oculusai.ui;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/** Отправка ресурспака на 1.21: паков у клиента может быть несколько, и наш
 *  добавляется к паку сервера, а не заменяет его. Ответы клиента приходят на
 *  каждый пак отдельно, поэтому свой узнаётся по id. */
public final class PackSender {

    public static void send(Player player, UUID id, String url, byte[] hash,
                            String prompt, boolean required) {
        player.addResourcePack(id, url, hash, prompt, required);
    }

    public static boolean ours(PlayerResourcePackStatusEvent event, UUID id) {
        return id.equals(event.getID());
    }

    private PackSender() {
    }
}
