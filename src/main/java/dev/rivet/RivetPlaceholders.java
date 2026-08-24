package dev.rivet;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** Exposes Rivet poll totals when PlaceholderAPI is installed. */
final class RivetPlaceholders extends PlaceholderExpansion {
    private final RivetPlugin plugin;

    RivetPlaceholders(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "rivet";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Rivet";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return plugin.pollPlaceholder(params);
    }
}
