package dev.rivet;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Duration;
import java.util.Locale;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class AuditSettings {
    private static final Duration MAX_LOOKUP_TIME = Duration.ofDays(36_500);
    private final YamlConfiguration settings;
    private final Map<AuditAction, Boolean> enabled;
    private final Set<String> excludedWorlds;
    private final Set<Material> excludedMaterials;

    AuditSettings(YamlConfiguration settings) {
        this.settings = settings;
        enabled = new EnumMap<>(AuditAction.class);
        for (AuditAction action : AuditAction.values()) {
            enabled.put(action, configured(action));
        }
        excludedWorlds = normalized(settings.getStringList("excluded-worlds"));
        excludedMaterials = settings.getStringList("excluded-materials").stream()
            .map(Material::matchMaterial).filter(java.util.Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
    }

    boolean enabled(AuditAction action) {
        return enabled.get(action);
    }

    private boolean configured(AuditAction action) {
        return settings.getBoolean("logging." + switch (action) {
            case BLOCK_PLACE -> "block-place";
            case BLOCK_BREAK -> "block-break";
            case CONTAINER_ADD, CONTAINER_REMOVE -> "containers";
            case ITEM_PICKUP -> "item-pickup";
            case ITEM_DROP -> "item-drop";
            case ENTITY_KILL -> "entities";
            case PLAYER_DEATH -> "deaths";
            case SIGN_EDIT -> "signs";
            case EXPLOSION, CREEPER_DAMAGE -> "explosions";
            case FIRE_DAMAGE -> "fire";
            case BLOCK_INTERACT -> "interactions";
            case COMMAND -> "commands";
        }, action != AuditAction.COMMAND);
    }

    boolean excluded(String world, String target) {
        if (excludedWorlds.contains(world.toLowerCase(Locale.ROOT))) {
            return true;
        }
        Material material = target == null ? null : Material.matchMaterial(target);
        return material != null && excludedMaterials.contains(material);
    }

    int retentionDays() {
        return Math.max(1, settings.getInt("retention.days", 30));
    }

    int pageSize() {
        return Math.max(3, Math.min(15, settings.getInt("lookup.page-size", 7)));
    }

    int inspectorLimit() {
        return Math.max(3, Math.min(15, settings.getInt("lookup.inspector-entries", 7)));
    }

    Duration defaultTime() {
        Duration parsed = parseTime(settings.getString("lookup.default-time", "30m"));
        return parsed == null ? Duration.ofMinutes(30) : parsed;
    }

    int defaultRadius() {
        return Math.max(0, Math.min(1_000, settings.getInt("lookup.default-radius", 10)));
    }

    static Duration parseTime(String input) {
        if (input == null) {
            return null;
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
            "^(\\d+)(s|sec|secs|m|min|mins|h|hr|hrs|d|day|days|w|week|weeks)$")
            .matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
        if (amount <= 0) {
            return null;
        }
        try {
            Duration parsed = switch (matcher.group(2).charAt(0)) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                case 'w' -> Duration.ofDays(Math.multiplyExact(amount, 7));
                default -> null;
            };
            return parsed != null && parsed.compareTo(MAX_LOOKUP_TIME) <= 0 ? parsed : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Set<String> normalized(java.util.List<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }
}
