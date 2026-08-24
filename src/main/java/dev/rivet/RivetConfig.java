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
        "chat", "homes", "warps", "graves", "breeders", "egg-capture", "creeper-restoration", "tree-feller",
        "mob-heads", "villager-reroll", "holograms", "permissions", "worlds", "staff",
        "environment", "inventory", "spawn", "tpa", "kits", "afk", "join-leave",
        "announcements", "nicknames", "statistics", "trash", "utilities", "poses",
        "backpacks", "daily", "rtp", "near", "filter", "help", "lagg", "logs", "death-messages",
        "snapshots", "magnet");
    static final Set<String> ENABLED_BY_DEFAULT = Set.of(
        "chat", "homes", "warps", "graves", "breeders", "egg-capture", "creeper-restoration", "tree-feller",
        "mob-heads", "villager-reroll", "holograms", "environment", "spawn", "afk", "join-leave",
        "nicknames", "statistics", "trash", "utilities", "filter", "help", "lagg", "logs",
        "death-messages", "snapshots", "magnet");
    static final List<String> SETTINGS = settingsFiles();

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
        YamlConfiguration persistedGlobal = loadChecked(globalFile);
        boolean migrateMessagePalette = persistedGlobal.getInt("message-palette-version", 0) < 1;
        int configurationVersion = persistedGlobal.getInt("configuration-version", 0);
        boolean worldsSettingsExisted = settingsFile("worlds").exists();
        boolean gameplaySettingsExisted = settingsFile("gameplay").exists();
        saveResource("modules.yml");
        for (String name : SETTINGS) {
            saveResource("settings/" + name + ".yml");
            YamlConfiguration configured = loadChecked(settingsFile(name));
            if (name.equals("permissions")) {
                configured.options().pathSeparator('/');
            }
            boolean settingsChanged = name.equals("permissions")
                && PermissionModule.migrateGroups(configured);
            settingsChanged |= migrateRenamedMessageActions(name, configured);
            settingsChanged |= migrateMessageActions(name, configured);
            if (name.equals("statistics") && StatisticsModule.migrateSeenV2(configured)) {
                settingsChanged = true;
            }
            if (name.equals("chat") && migrateLegacyChatSettings(configured)) {
                settingsChanged = true;
            }
            settingsChanged |= mergeBundledDefaults(name, configured);
            if (migrateMessagePalette && migrateMessagePalette(configured, name)) {
                settingsChanged = true;
            }
            if (migratePlaceholderSyntax(configured, name)) {
                settingsChanged = true;
            }
            if (name.equals("chat") && migrateChatFormat(configured)) {
                settingsChanged = true;
            }
            if (settingsChanged) {
                configured.save(settingsFile(name));
            }
            settings.put(name, configured);
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
        migrateGameplaySettings(gameplaySettingsExisted, configurationVersion);
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
        for (String name : SETTINGS) {
            YamlConfiguration configured = loadChecked(settingsFile(name));
            if (name.equals("permissions")) {
                configured.options().pathSeparator('/');
            }
            nextSettings.put(name, configured);
        }

        List<String> changedModules = changedModules(activeModules, nextModules);
        plugin.reloadConfig();
        modules.loadFromString(nextModules.saveToString());
        for (String name : SETTINGS) {
            settings.get(name).loadFromString(nextSettings.get(name).saveToString());
        }
        return new ReloadResult(changedModules, SETTINGS.size() + 2);
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
            if (module.equals("permissions")) {
                defaults.options().pathSeparator('/');
            }
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

    private boolean migrateMessageActions(String module, YamlConfiguration configured) {
        try (var resource = plugin.getResource("settings/" + module + ".yml")) {
            if (resource == null) {
                return false;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(resource, StandardCharsets.UTF_8));
            boolean changed = false;
            for (String actionsPath : defaults.getValues(true).keySet().stream()
                 .filter(path -> path.endsWith(".actions") && defaults.isList(path)).toList()) {
                String eventPath = actionsPath.substring(0, actionsPath.length() - ".actions".length());
                String legacyTextPath = eventPath + ".text";
                String legacyMessagePath = eventPath + ".message";
                String legacy = configured.isString(eventPath) ? configured.getString(eventPath)
                    : configured.isString(legacyTextPath) ? configured.getString(legacyTextPath)
                    : configured.isString(legacyMessagePath) ? configured.getString(legacyMessagePath) : null;
                List<String> legacyActions = configured.isList(eventPath)
                    ? List.copyOf(configured.getStringList(eventPath)) : null;
                if (legacy == null && legacyActions == null) {
                    continue;
                }
                List<String> defaultActions = defaults.getStringList(actionsPath);
                String tag = defaultActions.isEmpty() ? "message"
                    : java.util.Optional.ofNullable(GuiActions.parseAction(defaultActions.getFirst()))
                        .map(GuiActions.Action::tag).orElse("message");
                boolean enabled = configured.getBoolean(eventPath + ".enabled", true);
                configured.set(eventPath, null);
                configured.set(eventPath + ".enabled", enabled);
                configured.set(actionsPath, legacyActions != null
                    ? legacyActions : List.of("[" + tag + "] " + legacy));
                changed = true;
            }
            return changed;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read bundled settings for " + module, exception);
        }
    }

    private static boolean migrateRenamedMessageActions(String module,
                                                        YamlConfiguration configured) {
        boolean changed = false;
        if (module.equals("statistics")) {
            if (!configured.isConfigurationSection("statistics")
                && (configured.isString("header") || configured.isList("lines"))) {
                java.util.ArrayList<String> actions = new java.util.ArrayList<>();
                if (configured.isString("header")) {
                    actions.add("[message] " + configured.getString("header"));
                }
                configured.getStringList("lines").forEach(line -> actions.add("[message] " + line));
                configured.set("header", null);
                configured.set("lines", null);
                configured.set("statistics.enabled", true);
                configured.set("statistics.actions", actions);
                changed = true;
            }
            if (configured.isString("playtime-format")
                && !configured.isConfigurationSection("playtime")) {
                String legacy = configured.getString("playtime-format");
                configured.set("playtime-format", null);
                configured.set("playtime.enabled", true);
                configured.set("playtime.actions", List.of("[message] " + legacy));
                changed = true;
            }
        } else if (module.equals("utilities")) {
            changed |= migrateRenamedMessage(configured, "list.format", "list.output");
            changed |= migrateRenamedMessage(configured, "ping.format", "ping.output");
        } else if (module.equals("join-leave")) {
            changed |= migrateRenamedMessage(configured, "join.message", "join", "broadcast");
            changed |= migrateRenamedMessage(configured, "leave.message", "leave", "broadcast");
            changed |= migrateRenamedMessage(configured, "first-join.message", "first-join", "broadcast");
            changed |= migrateRenamedMessage(configured, "welcome-chat.message", "welcome-chat");
            if (!configured.contains("welcome-title.actions")
                && (configured.isString("welcome-title.title")
                    || configured.isString("welcome-title.subtitle"))) {
                String title = configured.getString("welcome-title.title", "");
                String subtitle = configured.getString("welcome-title.subtitle", "");
                configured.set("welcome-title.title", null);
                configured.set("welcome-title.subtitle", null);
                configured.set("welcome-title.actions", List.of(
                    "[title] " + title + " | " + subtitle + " | 5 | 30 | 10"));
                changed = true;
            }
            if (configured.isList("motd.lines") && !configured.contains("motd.actions")) {
                List<String> actions = configured.getStringList("motd.lines").stream()
                    .map(line -> "[message] " + line).toList();
                configured.set("motd.lines", null);
                configured.set("motd.actions", actions);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean migrateRenamedMessage(YamlConfiguration configured,
                                                 String legacyPath, String eventPath) {
        return migrateRenamedMessage(configured, legacyPath, eventPath, "message");
    }

    private static boolean migrateRenamedMessage(YamlConfiguration configured,
                                                 String legacyPath, String eventPath,
                                                 String actionTag) {
        if (!configured.isString(legacyPath) || configured.isConfigurationSection(eventPath)) {
            return false;
        }
        String legacy = configured.getString(legacyPath);
        configured.set(legacyPath, null);
        configured.set(eventPath + ".enabled", true);
        configured.set(eventPath + ".actions", List.of("[" + actionTag + "] " + legacy));
        return true;
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

    private void migrateGameplaySettings(boolean gameplaySettingsExisted, int configurationVersion)
        throws IOException, InvalidConfigurationException {
        if (configurationVersion >= 2) {
            return;
        }
        YamlConfiguration gameplay = settings("gameplay");
        if (!gameplaySettingsExisted) {
            YamlConfiguration worlds = settings("worlds");
            copyValue(worlds, "crop-trample-protection", gameplay, "crop-trample-protection");
            copyValue(worlds, "water-harvest-replanting", gameplay, "water-harvest-replanting");
            copyValue(worlds, "iron-golem-poppy-drops", gameplay, "iron-golem-poppy-drops");

            File legacyHoppersFile = settingsFile("hoppers");
            if (legacyHoppersFile.isFile()) {
                YamlConfiguration legacyHoppers = loadChecked(legacyHoppersFile);
                copyValue(legacyHoppers, "transfer-cooldown-ticks", gameplay,
                    "hoppers.transfer-cooldown-ticks");
            }
            File modulesFile = new File(plugin.getDataFolder(), "modules.yml");
            YamlConfiguration configuredModules = loadChecked(modulesFile);
            if (configuredModules.contains("hoppers")) {
                gameplay.set("hoppers.enabled", configuredModules.getBoolean("hoppers"));
            }
            gameplay.save(settingsFile("gameplay"));
        }
        plugin.getConfig().set("configuration-version", 2);
        plugin.saveConfig();
        plugin.getLogger().info("Migrated small gameplay mechanics to settings/gameplay.yml; "
            + "legacy keys were left untouched.");
    }

    private static void copyValue(YamlConfiguration source, String sourcePath,
                                  YamlConfiguration target, String targetPath) {
        if (source.contains(sourcePath)) {
            target.set(targetPath, source.get(sourcePath));
        }
    }

    private static List<String> settingsFiles() {
        java.util.ArrayList<String> names = new java.util.ArrayList<>(MODULES);
        names.add("gameplay");
        names.add("teleports");
        return List.copyOf(names);
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

    private static boolean migratePlaceholderSyntax(YamlConfiguration configuration, String module) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry
             : new HashMap<>(configuration.getValues(true)).entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String message) {
                String migrated = migratePlaceholders(module, entry.getKey(), message);
                if (!migrated.equals(message)) {
                    configuration.set(entry.getKey(), migrated);
                    changed = true;
                }
            } else if (value instanceof List<?> values
                && values.stream().allMatch(String.class::isInstance)) {
                List<String> migrated = values.stream().map(String.class::cast)
                    .map(message -> migratePlaceholders(module, entry.getKey(), message)).toList();
                if (!migrated.equals(values)) {
                    configuration.set(entry.getKey(), migrated);
                    changed = true;
                }
            }
        }
        return changed;
    }

    static String migratePlaceholders(String module, String path, String message) {
        return path.toLowerCase(java.util.Locale.ROOT).contains("usage")
            ? message : RivetMiniMessage.toPercentPlaceholders(message);
    }

    static boolean migrateChatFormat(YamlConfiguration configuration) {
        String old = "<#f72a4c>%player%:</#f72a4c> <white>%message%</white>";
        if (!old.equals(configuration.getString("format"))) {
            return false;
        }
        configuration.set("format",
            "%prefix%%tag% %player%%suffix%<dark_gray>: </dark_gray>%message%");
        return true;
    }

    static boolean migrateLegacyChatSettings(YamlConfiguration configuration) {
        boolean changed = false;
        changed |= copyLegacyChatSetting(configuration, "chat-colors.allow-hex",
            "chat-styles.allow-custom-hex");
        changed |= copyLegacyChatSetting(configuration, "chat-colors.allow-gradients",
            "chat-styles.allow-custom-gradients");
        changed |= copyLegacyChatSetting(configuration, "chat-colors.allow-rainbow",
            "chat-styles.allow-rainbow");
        return changed;
    }

    private static boolean copyLegacyChatSetting(YamlConfiguration configuration,
                                                 String oldPath, String newPath) {
        if (!configuration.isBoolean(oldPath) || configuration.contains(newPath)) {
            return false;
        }
        configuration.set(newPath, configuration.getBoolean(oldPath));
        return true;
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
                "<#f72a4c>%player%:</#f72a4c> <white>%message%</white>");
            case "me.format" -> replaceExact(message,
                "<light_purple>* <player> <message></light_purple>",
                "<#f72a4c>* %player%</#f72a4c> <white>%message%</white>");
            case "private-messages.sent" -> replaceExact(message,
                "<gray>[you → <player>]</gray> <white><message></white>",
                "<#f72a4c>[you → %player%]</#f72a4c> <white>%message%</white>");
            case "private-messages.received" -> replaceExact(message,
                "<gray>[<player> → you]</gray> <white><message></white>",
                "<#f72a4c>[%player% → you]</#f72a4c> <white>%message%</white>");
            case "social-spy.format" -> replaceExact(message,
                "<dark_gray>[spy]</dark_gray> <gray><sender> → <recipient>:</gray> <white><message></white>",
                "<#f72a4c>[spy] %sender% → %recipient%:</#f72a4c> <white>%message%</white>");
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
