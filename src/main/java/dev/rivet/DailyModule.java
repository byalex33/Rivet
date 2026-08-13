package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

final class DailyModule {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final YamlConfiguration data;

    DailyModule(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("daily");
        data = plugin.data("daily");
    }

    boolean command(Player player, String[] args) {
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<white>Usage: /daily"));
            return true;
        }
        long now = System.currentTimeMillis();
        String path = "players." + player.getUniqueId();
        long period = hours(settings.getDouble("claim-period-hours", 24));
        long reset = Math.max(period, hours(settings.getDouble("streak-reset-hours", 48)));
        ClaimState state = evaluate(data.getLong(path + ".last-claim"),
            data.getInt(path + ".streak"), period, reset, now);
        if (!state.eligible()) {
            message(player, "wait", "<white>Your next daily reward is available in <#f72a4c>%time%</#f72a4c>.",
                "time", duration(state.remainingMillis()));
            return true;
        }

        int rewardDay = rewardDay(settings.getConfigurationSection("rewards"), state.streak(),
            settings.getBoolean("cycle-rewards", true));
        ConfigurationSection reward = rewardDay < 1 ? null
            : settings.getConfigurationSection("rewards." + rewardDay);
        if (reward == null) {
            message(player, "unconfigured", "<white>No reward is configured for this streak day.",
                "streak", Integer.toString(state.streak()));
            return true;
        }

        Object oldLast = data.get(path + ".last-claim");
        Object oldStreak = data.get(path + ".streak");
        data.set(path + ".last-claim", now);
        data.set(path + ".streak", state.streak());
        try {
            plugin.saveData("daily");
        } catch (IOException exception) {
            data.set(path + ".last-claim", oldLast);
            data.set(path + ".streak", oldStreak);
            plugin.getLogger().severe("Could not save data/daily.yml: " + exception.getMessage());
            message(player, "save-error", "<white>Your reward could not be saved safely. Try again.",
                "streak", Integer.toString(state.streak()));
            return true;
        }

        grant(player, reward);
        ConfigurationSection milestone = settings.getConfigurationSection("milestones." + state.streak());
        if (milestone != null) {
            grant(player, milestone);
        }
        message(player, "claimed", "<white>Daily reward claimed! <white>Streak: <#f72a4c>%streak%</#f72a4c>",
            "streak", Integer.toString(state.streak()));
        return true;
    }

    private void grant(Player player, ConfigurationSection reward) {
        reward.getMapList("items").stream().map(values -> KitsModule.item(plugin, values))
            .filter(java.util.Objects::nonNull).forEach(item -> give(player, item));
        int experience = Math.max(0, reward.getInt("experience"));
        if (experience > 0) {
            player.giveExp(experience);
        }
        reward.getStringList("commands").forEach(command -> plugin.getServer().dispatchCommand(
            plugin.getServer().getConsoleSender(), command.replace("%player%", player.getName())
                .replace("<player>", player.getName())));
    }

    private void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
            .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void message(Player player, String key, String fallback, String placeholder, String value) {
        plugin.messageActions().run(player, settings, "messages." + key, fallback,
            Placeholder.unparsed(placeholder, value));
    }

    private static long hours(double hours) {
        return (long) (Math.max(1D / 60D, hours) * 3_600_000L);
    }

    private static String duration(long milliseconds) {
        Duration duration = Duration.ofMillis(Math.max(0, milliseconds));
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        long seconds = duration.minusHours(hours).minusMinutes(minutes).toSeconds();
        return hours > 0 ? hours + "h " + minutes + "m"
            : minutes > 0 ? minutes + "m " + seconds + "s" : Math.max(1, seconds) + "s";
    }

    static ClaimState evaluate(long lastClaim, int streak, long period, long reset, long now) {
        if (lastClaim > 0 && now - lastClaim < period) {
            return new ClaimState(false, Math.max(0, streak), lastClaim + period - now);
        }
        boolean resetStreak = lastClaim <= 0 || now < lastClaim || now - lastClaim >= reset;
        return new ClaimState(true, resetStreak ? 1 : Math.max(0, streak) + 1, 0);
    }

    static int rewardDay(ConfigurationSection rewards, int streak, boolean cycle) {
        if (rewards == null || streak < 1) {
            return -1;
        }
        if (rewards.isConfigurationSection(Integer.toString(streak))) {
            return streak;
        }
        int maximum = rewards.getKeys(false).stream().mapToInt(key -> {
            try {
                return Integer.parseInt(key);
            } catch (NumberFormatException exception) {
                return 0;
            }
        }).max().orElse(0);
        int day = cycle && maximum > 0 ? (streak - 1) % maximum + 1 : -1;
        return day > 0 && rewards.isConfigurationSection(Integer.toString(day)) ? day : -1;
    }

    record ClaimState(boolean eligible, int streak, long remainingMillis) {
    }
}
