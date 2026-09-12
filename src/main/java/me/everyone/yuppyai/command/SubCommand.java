package me.everyone.yuppyai.command;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;

public abstract class SubCommand {

    protected final YuppyAI plugin;

    private final String name;
    private final List<String> aliases;
    private final String permission;
    private final String usage;
    private final String description;
    private final boolean playerOnly;

    protected SubCommand(YuppyAI plugin, String name, List<String> aliases, String permission,
                         String usage, String description, boolean playerOnly) {
        this.plugin = plugin;
        this.name = name;
        this.aliases = aliases;
        this.permission = permission;
        this.usage = usage;
        this.description = description;
        this.playerOnly = playerOnly;
    }

    public abstract void execute(CommandSender sender, String[] args);

    public List<String> complete(CommandSender sender, String[] args) {
        return List.of();
    }

    public boolean matches(String input) {
        if (name.equalsIgnoreCase(input)) {
            return true;
        }
        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(input)) {
                return true;
            }
        }
        return false;
    }

    protected void reply(CommandSender sender, String miniMessage) {
        sender.sendMessage(Msg.parse(plugin.config().prefix() + miniMessage));
    }

    protected void replyKey(CommandSender sender, String key) {
        sender.sendMessage(plugin.config().prefix() + plugin.lang().text(key));
    }

    protected void replyKey(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(plugin.config().prefix() + plugin.lang().text(key, placeholders));
    }

    protected List<String> onlineNames(String partial) {
        String lower = partial.toLowerCase(java.util.Locale.ROOT);
        List<String> names = new java.util.ArrayList<>();
        for (org.bukkit.entity.Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getName().toLowerCase(java.util.Locale.ROOT).startsWith(lower)) {
                names.add(player.getName());
            }
        }
        return names;
    }

    public String name() {
        return name;
    }

    public String permission() {
        return permission;
    }

    public String usage() {
        return usage;
    }

    public String description() {
        return description;
    }

    public boolean playerOnly() {
        return playerOnly;
    }
}
