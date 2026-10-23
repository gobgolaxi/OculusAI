package me.everyone.oculusai.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.util.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public abstract class SubCommand {

    protected final OculusAI plugin;

    private final String name;
    private final List<String> aliases;
    private final String permission;
    private final String usage;
    private final String description;
    private final boolean playerOnly;

    protected SubCommand(OculusAI plugin, String name, List<String> aliases, String permission,
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
        String lower = partial.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
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

    /** The usage line, safe to hand to the message parser.
     *
     *  <p>{@code Msg.parse} treats every {@code <...>} as a tag and replaces
     *  an unknown one with nothing, so printing a usage line through it ate
     *  the placeholders: {@code /oai report <name> [reason]} arrived as
     *  {@code /oai report  [reason]} on every one of these commands. The
     *  escapes are turned back into angle brackets at the end of parsing, so
     *  this is the form to interpolate - never {@link #usage()} itself. */
    public String usageText() {
        return usage.replace("<", "&lt;").replace(">", "&gt;");
    }

    public String description() {
        return description;
    }

    public boolean playerOnly() {
        return playerOnly;
    }
}
