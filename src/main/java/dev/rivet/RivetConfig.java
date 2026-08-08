package dev.rivet;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class RivetConfig {
    private static final Pattern LEGACY_MESSAGE_COLOR = Pattern.compile(
        "(?i)<(?:/?(?:black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|"
            + "dark_gray|blue|green|aqua|red|light_purple|yellow)|/?gradient(?::[^>]*)?|"
            + "/?rainbow(?::[^>]*)?|/?#(?!f72a4c)[0-9a-f]{6})>");
    static final List<String> MODULES = List.of(
        "chat", "homes", "warps", "graves", "breeders", "egg-capture", "tree-feller",
        "mob-heads", "holograms", "permissions", "worlds", "staff",
        "environment", "inventory", "spawn", "tpa", "kits", "afk", "join-leave",
        "announcements", "nicknames", "statistics", "trash", "utilities", "poses",
        "backpacks", "daily", "rtp", "near", "filter", "help");
    static final Set<String> ENABLED_BY_DEFAULT = Set.of(
        "chat", "homes", "warps", "graves", "breeders", "egg-capture", "tree-feller",
        "mob-heads", "holograms", "environment", "spawn", "afk", "join-leave",
        "nicknames", "statistics", "trash", "utilities", "filter", "help");

    private final RivetPlugin plugin;
    private final File settingsDirectory;
    private final File dataDirectory;
    private final Map<String, YamlConfiguration> settings = new HashMap<>();
    private final Map<String, YamlConfiguration> data = new HashMap<>();
    private final Map<String, Boolean> activeModules = new HashMap<>();
    private YamlConfiguration modules;

    RivetConfig(RivetPlugin plugin) throws IOException, InvalidConfigurationException {
        this.plugin = plugin;
        settingsDirectory = new File(plugin.getDataFolder(), "settings");
        dataDirectory = new File(plugin.getDataFolder(), "data");
        Files.createDirectories(settingsDirectory.toPath());
        Files.createDirectories(dataDirectory.toPath());

        migrateFile("chat.yml", "settings/chat.yml");
        migrateFile("permissions/groups.yml", "settings/permissions.yml");
        migrateFile("graves.yml", "data/graves.yml");
        migrateFile("holograms.yml", "data/holograms.yml");
        migrateFile("permissions/users.yml", "data/permissions.yml");

        File globalFile = new File(plugin.getDataFolder(), "config.yml");
        boolean migrateMessagePalette = loadChecked(globalFile)
            .getInt("message-palette-version", 0) < 1;
        boolean worldsSettingsExisted = settingsFile("worlds").exists();
        saveResource("modules.yml");
        for (String module : MODULES) {
            saveResource("settings/" + module + ".yml");
            YamlConfiguration configured = loadChecked(settingsFile(module));
            boolean settingsChanged = mergeBundledDefaults(module, configured);
            if (migrateMessagePalette && migrateMessagePalette(configured, module)) {
                settingsChanged = true;
            }
            if (settingsChanged) {
                configured.save(settingsFile(module));
            }
            settings.put(module, configured);
        }
        if (migrateMessagePalette) {
            plugin.getConfig().set("message-palette-version", 1);
            plugin.saveConfig();
            plugin.getLogger().info("Updated configurable messages to Rivet's white and #f72a4c palette.");
        }
        File modulesFile = new File(plugin.getDataFolder(), "modules.yml");
        addMissingModuleSwitches(modulesFile);
        modules = loadChecked(modulesFile);
        validateModules(modules, modulesFile);
        MODULES.forEach(module -> activeModules.put(module,
            modules.getBoolean(module, ENABLED_BY_DEFAULT.contains(module))));
        migrateLegacyConfig(worldsSettingsExisted);
    }

    boolean enabled(String module) {
        return activeModules.getOrDefault(module, ENABLED_BY_DEFAULT.contains(module));
    }

    YamlConfiguration settings(String module) {
        return settings.get(module);
    }

    File settingsFile(String module) {
        return new File(settingsDirectory, module + ".yml");
    }

    File dataFile(String module) {
        return new File(dataDirectory, module + ".yml");
    }

    YamlConfiguration data(String module) {
        return data.computeIfAbsent(module,
            ignored -> YamlConfiguration.loadConfiguration(dataFile(module)));
    }

    void saveData(String module) throws IOException {
        data(module).save(dataFile(module));
    }

    ReloadResult reload() throws IOException, InvalidConfigurationException {
        File globalFile = new File(plugin.getDataFolder(), "config.yml");
        loadChecked(globalFile);
        YamlConfiguration nextModules = loadChecked(new File(plugin.getDataFolder(), "modules.yml"));
        validateModules(nextModules, new File(plugin.getDataFolder(), "modules.yml"));
        Map<String, YamlConfiguration> nextSettings = new HashMap<>();
        for (String module : MODULES) {
            nextSettings.put(module, loadChecked(settingsFile(module)));
        }

        List<String> changedModules = changedModules(activeModules, nextModules);
        plugin.reloadConfig();
        modules.loadFromString(nextModules.saveToString());
        for (String module : MODULES) {
            settings.get(module).loadFromString(nextSettings.get(module).saveToString());
        }
        return new ReloadResult(changedModules, MODULES.size() + 2);
    }

    private static YamlConfiguration loadChecked(File file)
        throws IOException, InvalidConfigurationException {
        if (!file.isFile()) {
            throw new IOException(file.getPath() + ": file does not exist");
        }
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new InvalidConfigurationException(file.getPath() + ": " + exception.getMessage(), exception);
        }
        return configuration;
    }

    private static void validateModules(YamlConfiguration configuration, File file)
        throws InvalidConfigurationException {
        for (String module : MODULES) {
            if (!(configuration.get(module) instanceof Boolean)) {
                throw new InvalidConfigurationException(file.getPath() + ": '" + module
                    + "' must be true or false");
            }
        }
    }

    static List<String> changedModules(Map<String, Boolean> active, YamlConfiguration configured) {
        return MODULES.stream().filter(module -> active.getOrDefault(module,
                ENABLED_BY_DEFAULT.contains(module))
            != configured.getBoolean(module, ENABLED_BY_DEFAULT.contains(module))).toList();
    }

    static String themeMessage(String message) {
        String namedColors = "black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|"
            + "dark_gray|blue|green|aqua|red|light_purple|yellow";
        return message
            .replaceAll("(?i)<white>", "<#f72a4c>")
            .replaceAll("(?i)</white>", "</#f72a4c>")
            .replaceAll("(?i)<(?:" + namedColors + ")>", "<white>")
            .replaceAll("(?i)</(?:" + namedColors + ")>", "</white>")
            .replaceAll("(?i)<gradient:[^>]+>", "<#f72a4c>")
            .replaceAll("(?i)</gradient>", "</#f72a4c>")
            .replaceAll("(?i)<rainbow(?::[^>]*)?>", "<#f72a4c>")
            .replaceAll("(?i)</rainbow>", "</#f72a4c>")
            .replaceAll("(?i)<#(?!f72a4c)[0-9a-f]{6}>", "<#f72a4c>")
            .replaceAll("(?i)</#(?!f72a4c)[0-9a-f]{6}>", "</#f72a4c>");
    }

    record ReloadResult(List<String> changedModules, int fileCount) {
    }

    private void saveResource(String path) {
        if (!new File(plugin.getDataFolder(), path).exists()) {
            plugin.saveResource(path, false);
        }
    }

    private boolean mergeBundledDefaults(String module, YamlConfiguration configured) {
        try (var resource = plugin.getResource("settings/" + module + ".yml")) {
            if (resource == null) {
                return false;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(resource, StandardCharsets.UTF_8));
            boolean changed = false;
            for (Map.Entry<String, Object> entry : defaults.getValues(true).entrySet()) {
                if (!(entry.getValue() instanceof ConfigurationSection)
                    && !configured.contains(entry.getKey())) {
                    configured.set(entry.getKey(), entry.getValue());
                    changed = true;
                }
            }
            return changed;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read bundled settings for " + module, exception);
        }
    }

    private void addMissingModuleSwitches(File file)
        throws IOException, InvalidConfigurationException {
        YamlConfiguration current = loadChecked(file);
        for (String module : MODULES) {
            if (current.contains(module) && !(current.get(module) instanceof Boolean)) {
                throw new InvalidConfigurationException(file.getPath() + ": '" + module
                    + "' must be true or false");
            }
        }
        List<String> missing = MODULES.stream().filter(module -> !current.contains(module)).toList();
        if (missing.isEmpty()) {
            return;
        }
        StringBuilder addition = new StringBuilder(System.lineSeparator())
            .append("# Module switches added by a Rivet update.").append(System.lineSeparator());
        missing.forEach(module -> addition.append(module).append(": ")
            .append(ENABLED_BY_DEFAULT.contains(module))
            .append(System.lineSeparator()));
        Files.writeString(file.toPath(), addition, StandardOpenOption.APPEND);
        plugin.getLogger().info("Added " + missing.size() + " new module switches to modules.yml.");
    }

    private void migrateFile(String oldPath, String newPath) throws IOException {
        File source = new File(plugin.getDataFolder(), oldPath);
        File target = new File(plugin.getDataFolder(), newPath);
        if (!source.exists()) {
            return;
        }
        if (target.exists()) {
            plugin.getLogger().warning("Both " + oldPath + " and " + newPath
                + " exist; using the new file and leaving the legacy file untouched.");
            return;
        }
        Files.createDirectories(target.toPath().getParent());
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source.toPath(), target.toPath());
        }
        plugin.getLogger().info("Migrated " + oldPath + " to " + newPath + ".");
    }

    private void migrateLegacyConfig(boolean worldsSettingsExisted) throws IOException {
        FileConfiguration config = plugin.getConfig();
        boolean changed = migrateSection(config, "homes", data("homes"))
            | migrateSection(config, "warps", data("warps"))
            | migrateSection(config, "auto-breeders", data("breeders"));
        if (config.contains("flat-worlds.allow-natural-mob-spawning")) {
            String path = "flat-worlds.allow-natural-mob-spawning";
            if (!worldsSettingsExisted || !settings("worlds").contains(path)) {
                settings("worlds").set(path, config.getBoolean(path));
            }
            settings("worlds").save(settingsFile("worlds"));
            config.set("flat-worlds", null);
            changed = true;
        }
        if (changed) {
            config.save(new File(plugin.getDataFolder(), "config.yml"));
            plugin.getLogger().info("Migrated legacy values from config.yml.");
        }
    }

    private static boolean migrateMessagePalette(YamlConfiguration configuration, String module) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry
             : new HashMap<>(configuration.getValues(true)).entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String message) {
                String themed = migrateMessage(module, entry.getKey(), message);
                if (!themed.equals(message)) {
                    configuration.set(entry.getKey(), themed);
                    changed = true;
                }
            } else if (value instanceof List<?> values && values.stream().allMatch(String.class::isInstance)) {
                List<String> themed = values.stream().map(String.class::cast)
                    .map(message -> migrateMessage(module, entry.getKey(), message)).toList();
                if (!themed.equals(values)) {
                    configuration.set(entry.getKey(), themed);
                    changed = true;
                }
            }
        }
        return changed;
    }

    static String migrateMessage(String module, String path, String message) {
        return LEGACY_MESSAGE_COLOR.matcher(message).find()
            ? themeConfiguredMessage(module, path, message) : message;
    }

    static String themeConfiguredMessage(String module, String path, String message) {
        if (!module.equals("chat")) {
            return themeMessage(message);
        }
        return switch (path) {
            case "format" -> replaceExact(message,
                "<gray><player>:</gray> <white><message></white>",
                "<#f72a4c><player>:</#f72a4c> <white><message></white>");
            case "me.format" -> replaceExact(message,
                "<light_purple>* <player> <message></light_purple>",
                "<#f72a4c>* <player></#f72a4c> <white><message></white>");
            case "private-messages.sent" -> replaceExact(message,
                "<gray>[you → <player>]</gray> <white><message></white>",
                "<#f72a4c>[you → <player>]</#f72a4c> <white><message></white>");
            case "private-messages.received" -> replaceExact(message,
                "<gray>[<player> → you]</gray> <white><message></white>",
                "<#f72a4c>[<player> → you]</#f72a4c> <white><message></white>");
            case "social-spy.format" -> replaceExact(message,
                "<dark_gray>[spy]</dark_gray> <gray><sender> → <recipient>:</gray> <white><message></white>",
                "<#f72a4c>[spy] <sender> → <recipient>:</#f72a4c> <white><message></white>");
            default -> themeMessage(message);
        };
    }

    private static String replaceExact(String message, String legacy, String replacement) {
        return message.equals(legacy) ? replacement : themeMessage(message);
    }

    private boolean migrateSection(FileConfiguration source, String path,
                                   YamlConfiguration target) throws IOException {
        ConfigurationSection section = source.getConfigurationSection(path);
        if (section == null) {
            return false;
        }
        copyMissing(section, target, path);
        target.save(dataFile(path.equals("auto-breeders") ? "breeders" : path));
        source.set(path, null);
        return true;
    }

    static void copyMissing(ConfigurationSection source, YamlConfiguration target, String targetPath) {
        source.getValues(true).forEach((key, value) -> {
            String path = targetPath + "." + key;
            if (!(value instanceof ConfigurationSection) && !target.contains(path)) {
                target.set(path, value);
            }
        });
    }
}
