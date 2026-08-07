package dev.rivet;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RivetConfig {
    static final List<String> MODULES = List.of(
        "chat", "homes", "warps", "graves", "breeders", "egg-capture", "tree-feller",
        "mob-heads", "holograms", "glow", "permissions", "worlds", "staff",
        "environment", "inventory", "spawn", "tpa", "kits", "afk", "join-leave",
        "announcements", "nicknames", "statistics", "trash", "utilities", "poses",
        "backpacks", "daily", "rtp", "near", "filter");

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
        migrateFile("glows.yml", "data/glow.yml");
        migrateFile("holograms.yml", "data/holograms.yml");
        migrateFile("permissions/users.yml", "data/permissions.yml");

        boolean worldsSettingsExisted = settingsFile("worlds").exists();
        saveResource("modules.yml");
        for (String module : MODULES) {
            saveResource("settings/" + module + ".yml");
            settings.put(module, loadChecked(settingsFile(module)));
        }
        File modulesFile = new File(plugin.getDataFolder(), "modules.yml");
        addMissingModuleSwitches(modulesFile);
        modules = loadChecked(modulesFile);
        validateModules(modules, modulesFile);
        MODULES.forEach(module -> activeModules.put(module, modules.getBoolean(module, true)));
        migrateLegacyConfig(worldsSettingsExisted);
    }

    boolean enabled(String module) {
        return activeModules.getOrDefault(module, true);
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
        return MODULES.stream().filter(module -> active.getOrDefault(module, true)
            != configured.getBoolean(module, true)).toList();
    }

    record ReloadResult(List<String> changedModules, int fileCount) {
    }

    private void saveResource(String path) {
        if (!new File(plugin.getDataFolder(), path).exists()) {
            plugin.saveResource(path, false);
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
        missing.forEach(module -> addition.append(module).append(": true")
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
