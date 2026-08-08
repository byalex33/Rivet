package dev.rivet;

import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;

final class ConfiguredEffect {
    private ConfiguredEffect() {
    }

    static Sound sound(RivetPlugin plugin, YamlConfiguration settings, String path, Sound fallback) {
        String configured = settings.getString(path, Registry.SOUND_EVENT.getKeyOrThrow(fallback).asString());
        NamespacedKey key = key(configured);
        Sound sound = key == null ? null : Registry.SOUND_EVENT.get(key);
        if (sound == null) {
            plugin.getLogger().warning("Invalid sound '" + configured + "' at " + path
                + "; using " + Registry.SOUND_EVENT.getKeyOrThrow(fallback).asString() + ".");
            return fallback;
        }
        return sound;
    }

    static Particle particle(RivetPlugin plugin, YamlConfiguration settings,
                             String path, Particle fallback) {
        String configured = settings.getString(path, fallback.getKey().asString());
        NamespacedKey key = key(configured);
        Particle particle = key == null ? null : Registry.PARTICLE_TYPE.get(key);
        if (particle == null || particle.getDataType() != Void.class) {
            plugin.getLogger().warning("Invalid data-free particle '" + configured + "' at " + path
                + "; using " + fallback.getKey().asString() + ".");
            return fallback;
        }
        return particle;
    }

    static Color color(RivetPlugin plugin, YamlConfiguration settings,
                       String path, int fallback) {
        String configured = settings.getString(path, String.format("#%06X", fallback));
        try {
            String hex = configured.startsWith("#") ? configured.substring(1) : configured;
            if (hex.length() != 6) {
                throw new NumberFormatException("expected six digits");
            }
            return Color.fromRGB(Integer.parseInt(hex, 16));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid RGB color '" + configured + "' at " + path
                + "; using " + String.format("#%06X", fallback) + ".");
            return Color.fromRGB(fallback);
        }
    }

    private static NamespacedKey key(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        return NamespacedKey.fromString(normalized.contains(":")
            ? normalized : "minecraft:" + normalized);
    }
}
