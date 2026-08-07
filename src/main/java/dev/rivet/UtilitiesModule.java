package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

final class UtilitiesModule {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RivetPlugin plugin;

    UtilitiesModule(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    boolean command(Player player, String command, String[] args) {
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<red>Usage: /" + command));
            return true;
        }
        if (!plugin.settings("utilities").getBoolean("interfaces." + command, true)) {
            player.sendMessage(MM.deserialize("<yellow>That portable utility is disabled."));
            return true;
        }
        switch (command) {
            case "craft" -> player.openWorkbench(null, true);
            case "anvil" -> player.openAnvil(null, true);
            case "smithing" -> player.openSmithingTable(null, true);
            case "stonecutter" -> player.openStonecutter(null, true);
            case "grindstone" -> player.openGrindstone(null, true);
            default -> {
                return false;
            }
        }
        return true;
    }
}
