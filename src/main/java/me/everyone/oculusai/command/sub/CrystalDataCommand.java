package me.everyone.oculusai.command.sub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.storage.CrystalDataset;
import org.bukkit.command.CommandSender;

/** /oai datacrystal - запись кристалльного датасета (схема 8).
 *
 * <p>Повторяет /oai data, но со своим списком игроков и своим пулом: окна
 * режутся вокруг постановок и ударов по кристаллам, а не каждые полсекунды
 * боя. */
public final class CrystalDataCommand extends SubCommand {

    private static final List<String> ACTIONS = List.of("add", "rem", "train", "stop", "list");

    public CrystalDataCommand(OculusAI plugin) {
        super(plugin, "datacrystal", List.of("crystaldata"), "oculusai.data",
                "/oai datacrystal add|rem|train|stop|list",
                "Record the crystal PvP dataset (CrystalAura, AutoCrystal).", false);
    }

    private CrystalDataset dataset() {
        return plugin.crystalData();
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add" -> add(sender, args);
            case "rem", "remove" -> remove(sender, args);
            case "train", "start" -> start(sender);
            case "stop" -> stop(sender);
            case "list" -> list(sender);
            default -> replyKey(sender, "crystal-data.usage");
        }
    }

    private void add(CommandSender sender, String[] args) {
        if (args.length < 3 || !(args[1].equalsIgnoreCase("cheater") || args[1].equalsIgnoreCase("legit"))) {
            replyKey(sender, "crystal-data.usage");
            return;
        }
        PlayerData data = plugin.data().byName(args[2]);
        if (data == null) {
            replyKey(sender, "shared.player-offline");
            return;
        }
        String label = args[1].toLowerCase(Locale.ROOT);
        dataset().enrol(data, label);
        replyKey(sender, "crystal-data.added", Map.of("player", data.name(), "label", label));
    }

    private void remove(CommandSender sender, String[] args) {
        CrystalDataset.Entry entry = args.length < 2 ? null : dataset().byName(args[1]);
        if (entry == null) {
            replyKey(sender, "crystal-data.unknown", Map.of("player", args.length < 2 ? "?" : args[1]));
            return;
        }
        dataset().unenrol(entry.uuid());
        replyKey(sender, "crystal-data.removed", Map.of("player", entry.name()));
    }

    private void start(CommandSender sender) {
        if (dataset().roster().isEmpty()) {
            replyKey(sender, "crystal-data.empty");
            return;
        }
        int started = dataset().start();
        replyKey(sender, "crystal-data.started", Map.of("value", Integer.toString(started)));
    }

    private void stop(CommandSender sender) {
        if (!dataset().serviceAccepts()) {
            // Окна остаются у игроков: после обновления сервиса stop
            // отправит их. Отправить сейчас - значит получить отказ и
            // вечный повтор в очереди.
            replyKey(sender, "crystal-data.service-old");
            return;
        }
        Map<String, CompletableFuture<Integer>> results = dataset().stop();
        if (results.isEmpty()) {
            replyKey(sender, "crystal-data.nothing");
            return;
        }
        replyKey(sender, "crystal-data.sending", Map.of("value", Integer.toString(results.size())));
        results.forEach((name, future) -> future.thenAccept(stored -> plugin.mainThread().run(() ->
                replyKey(sender, stored == null ? "crystal-data.failed" : "crystal-data.saved",
                        Map.of("player", name, "value", stored == null ? "0" : stored.toString())))));
    }

    private void list(CommandSender sender) {
        if (dataset().roster().isEmpty()) {
            replyKey(sender, "crystal-data.empty");
            return;
        }
        replyKey(sender, dataset().collecting() ? "crystal-data.list-recording" : "crystal-data.list-idle");
        for (CrystalDataset.Entry entry : dataset().roster()) {
            int recorded = dataset().recorded(entry.uuid());
            replyKey(sender, "crystal-data.list-line", Map.of(
                    "player", entry.name(),
                    "label", entry.label(),
                    "value", recorded < 0 ? "offline" : Integer.toString(recorded)));
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(ACTIONS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            return filter(List.of("cheater", "legit"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            return onlineNames(args[2]);
        }
        if (args.length == 2 && args[0].toLowerCase(Locale.ROOT).startsWith("rem")) {
            List<String> names = new ArrayList<>();
            for (CrystalDataset.Entry entry : dataset().roster()) {
                names.add(entry.name());
            }
            return filter(names, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
