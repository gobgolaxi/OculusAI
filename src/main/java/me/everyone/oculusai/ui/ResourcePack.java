package me.everyone.oculusai.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.util.Msg;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/** Ресурспак с логотипом OculusAI.
 *
 *  <p>В паке один глиф: логотип, привязанный к символу из частной области
 *  Юникода. Сам по себе символ ничего не значит - у игрока без пака он
 *  рисуется пустым квадратом, - поэтому {@link #logo(Player)} отдаёт его
 *  только тем, чей клиент ответил, что пак загружен.
 *
 *  <p>Пак лежит внутри jar, и по нему же считается SHA-1. Клиент качает его
 *  с сервиса OculusAI: туда выкладывается тот же самый zip, сборка у него
 *  повторяемая, так что хэш сходится. */
public final class ResourcePack implements Service, Listener {

    /** Символ, которому в паке назначен логотип. */
    public static final String LOGO = "";

    private static final String BUNDLED = "pack/OculusAI-pack.zip";
    /** Эндпоинт пака на сервисе; адрес сервиса - api.url. */
    private static final String PACK_PATH = "/api/v1/resource-pack.zip";
    /** Свой id у пака, чтобы на 1.20.3+ он лёг рядом с паком сервера, а не
     *  вместо него. Постоянный: клиент кэширует пак по нему. */
    private static final UUID ID = UUID.nameUUIDFromBytes(
            "oculusai:resource-pack".getBytes(StandardCharsets.UTF_8));

    private final OculusAI plugin;
    private final Set<UUID> sent = ConcurrentHashMap.newKeySet();
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private volatile String url;
    private volatile byte[] hash;

    public ResourcePack(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        resolve();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        sent.clear();
        loaded.clear();
    }

    @Override
    public void reload() {
        resolve();
    }

    /** Логотип с пробелом для начала строки, или пусто, если пака у игрока нет. */
    public String logo(Player player) {
        // Белым: глиф из пака умножается на цвет текста, и в цвете темы
        // серебряный логотип стал бы фиолетовым или золотым.
        return loaded.contains(player.getUniqueId()) ? "§f" + LOGO + " " : "";
    }

    private void resolve() {
        String configured = plugin.config().packUrl();
        url = configured.isEmpty() ? plugin.config().apiUrl() + PACK_PATH : configured;
        String sha1 = plugin.config().packSha1();
        if (!"auto".equalsIgnoreCase(sha1)) {
            hash = hex(sha1);
            return;
        }
        hash = bundledHash();
        if (hash == null) {
            // Без хэша клиент пак примет, но не сможет закэшировать, а
            // главное - сломанная сборка без пака внутри будет незаметна.
            plugin.getLogger().warning("Resource pack is missing from the plugin jar;"
                    + " the scoreboard logo is off.");
            url = null;
        }
    }

    private byte[] bundledHash() {
        try (InputStream in = plugin.getResource(BUNDLED)) {
            if (in == null) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (IOException | NoSuchAlgorithmException failure) {
            return null;
        }
    }

    private static byte[] hex(String value) {
        if (value == null || value.length() != 40) {
            return null;
        }
        byte[] bytes = new byte[20];
        try {
            for (int i = 0; i < 20; i++) {
                bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException invalid) {
            return null;
        }
        return bytes;
    }

    private boolean inArena(Player player) {
        return plugin.config().testServerEnabled()
                && player.getWorld().getName().equalsIgnoreCase(plugin.config().testServerWorld());
    }

    private void offer(Player player) {
        if (!plugin.config().packEnabled() || url == null) {
            return;
        }
        if (plugin.config().packArenaOnly() && !inArena(player)) {
            return;
        }
        if (!sent.add(player.getUniqueId())) {
            return;
        }
        String prompt = Msg.parse(plugin.config().packPrompt());
        boolean required = plugin.config().packRequired();
        // Секунда после входа: клиент, которому пак прилетел прямо в момент
        // загрузки мира, на части версий молча его теряет.
        plugin.ticks().after(20L, () -> plugin.platform().onEntity(player, () -> {
            if (player.isOnline()) {
                PackSender.send(player, ID, url, hash, prompt, required);
            }
        }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        offer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        offer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!sent.contains(event.getPlayer().getUniqueId()) || !PackSender.ours(event, ID)) {
            return;
        }
        // По имени, а не по константе: ACCEPTED, DOWNLOADED и прочие
        // промежуточные ответы появились не во всех версиях API.
        String status = event.getStatus().name();
        UUID uuid = event.getPlayer().getUniqueId();
        if ("SUCCESSFULLY_LOADED".equals(status)) {
            loaded.add(uuid);
        } else if (!"ACCEPTED".equals(status) && !"DOWNLOADED".equals(status)) {
            loaded.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sent.remove(event.getPlayer().getUniqueId());
        loaded.remove(event.getPlayer().getUniqueId());
    }
}
