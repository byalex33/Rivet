package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.projectiles.ProjectileSource;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

final class DeathMessagesModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();

    private final RivetPlugin plugin;

    DeathMessagesModule(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        var settings = plugin.settings("death-messages");
        if (!settings.getBoolean("enabled", true)) {
            return;
        }

        DamageSource source = event.getDamageSource();
        Entity attacker = attacker(source);
        String pool = category(source == null ? null : source.getDamageType().getKey().getKey(),
            attacker instanceof Player, attacker instanceof LivingEntity);
        List<String> messages = settings.getStringList(pool);
        List<String> fallback = pool.equals("generic") ? List.of() : settings.getStringList("generic");
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String template = select(messages, fallback, settings.getStringList("rare"),
            settings.getDouble("rare-chance", .02), random.nextDouble(), random.nextInt());
        if (template == null) {
            return;
        }

        Player victim = event.getPlayer();
        Player killer = attacker instanceof Player player ? player : null;
        LivingEntity mob = attacker instanceof LivingEntity living && !(attacker instanceof Player)
            ? living : null;
        ItemStack weapon = weapon(source, attacker);
        Component message = render(template,
            Component.text(victim.getName()),
            killer == null ? Component.text("unknown") : Component.text(killer.getName()),
            mob == null ? Component.text("unknown") : entityName(mob),
            weaponName(weapon, attacker instanceof Player),
            Component.text(victim.getWorld().getName()));
        if (settings.getBoolean("killer-health", false) && killer != null) {
            message = message.append(Component.text(" (", NamedTextColor.DARK_GRAY))
                .append(Component.text(formatHealth(killer.getHealth()), NamedTextColor.RED))
                .append(Component.text(" HP)", NamedTextColor.DARK_GRAY));
        }
        event.deathMessage(message);
    }

    static String category(String damageType, boolean playerKiller, boolean mobKiller) {
        if (playerKiller) {
            return "player";
        }
        if (mobKiller) {
            return "mob";
        }
        if (damageType == null) {
            return "generic";
        }
        return switch (damageType.toLowerCase(Locale.ROOT)) {
            case "fall", "stalagmite", "falling_stalactite", "falling_anvil", "falling_block",
                 "fly_into_wall", "ender_pearl" -> "fall";
            case "in_fire", "campfire", "on_fire", "lava", "hot_floor" -> "fire";
            case "drown" -> "drowning";
            case "explosion", "player_explosion", "bad_respawn_point", "fireworks" -> "explosion";
            case "arrow", "trident", "mob_projectile", "spit", "fireball",
                 "unattributed_fireball", "wither_skull", "thrown", "wind_charge" -> "projectile";
            case "out_of_world", "outside_border" -> "void";
            case "in_wall", "cramming" -> "suffocation";
            case "magic", "indirect_magic", "wither", "dragon_breath", "sonic_boom" -> "magic";
            default -> "generic";
        };
    }

    static String select(List<String> primary, List<String> fallback, List<String> rare,
                         double rareChance, double roll, int randomValue) {
        List<String> rareMessages = usable(rare);
        if (!rareMessages.isEmpty() && roll < Math.max(0, Math.min(1, rareChance))) {
            return rareMessages.get(Math.floorMod(randomValue, rareMessages.size()));
        }
        List<String> messages = usable(primary);
        if (messages.isEmpty()) {
            messages = usable(fallback);
        }
        return messages.isEmpty() ? null : messages.get(Math.floorMod(randomValue, messages.size()));
    }

    static Component render(String template, Component player, Component killer, Component mob,
                            Component weapon, Component world) {
        return MM.deserialize(template,
            Placeholder.component("player", safeComponent(player)),
            Placeholder.component("killer", safeComponent(killer)),
            Placeholder.component("mob", safeComponent(mob)),
            Placeholder.component("weapon", safeWeaponComponent(weapon)),
            Placeholder.component("world", safeComponent(world)));
    }

    static Component safeComponent(Component component) {
        Component safe = component.clickEvent((ClickEvent) null)
            .hoverEvent((HoverEvent<?>) null).insertion(null);
        return safe.children(safe.children().stream()
            .map(DeathMessagesModule::safeComponent).toList());
    }

    static Component safeWeaponComponent(Component component) {
        Component safe = safeComponent(component);
        HoverEvent<?> hover = component.hoverEvent();
        return hover != null && hover.action() == HoverEvent.Action.SHOW_ITEM
            ? safe.hoverEvent(hover) : safe;
    }

    private static Entity attacker(DamageSource source) {
        if (source == null) {
            return null;
        }
        Entity causing = source.getCausingEntity();
        if (causing != null) {
            return causing;
        }
        if (source.getDirectEntity() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Entity entity) {
                return entity;
            }
        }
        return null;
    }

    private static ItemStack weapon(DamageSource source, Entity attacker) {
        if (source != null && source.getDirectEntity() instanceof AbstractArrow arrow) {
            ItemStack weapon = arrow.getWeapon();
            if (present(weapon)) {
                return weapon;
            }
        }
        if (attacker instanceof LivingEntity living) {
            EntityEquipment equipment = living.getEquipment();
            if (equipment != null && present(equipment.getItemInMainHand())) {
                return equipment.getItemInMainHand();
            }
        }
        return null;
    }

    private static Component entityName(LivingEntity entity) {
        Component custom = entity.customName();
        return custom == null ? Component.translatable(entity.getType().translationKey())
            : safeComponent(custom);
    }

    private static Component weaponName(ItemStack item, boolean playerWeapon) {
        if (!present(item)) {
            return Component.text(playerWeapon ? "their bare hands" : "natural attacks");
        }
        ItemMeta meta = item.getItemMeta();
        Component name;
        if (meta != null && meta.hasCustomName()) {
            name = safeComponent(meta.customName());
        } else if (meta != null && meta.hasItemName()) {
            name = safeComponent(meta.itemName());
        } else {
            name = Component.translatable(item.translationKey());
        }
        return name.hoverEvent(item.asHoverEvent());
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }

    private static List<String> usable(List<String> messages) {
        return messages == null ? List.of() : messages.stream()
            .filter(message -> message != null && !message.isBlank()).toList();
    }

    private static String formatHealth(double health) {
        return health == Math.rint(health) ? Long.toString(Math.round(health))
            : String.format(Locale.ROOT, "%.1f", health);
    }
}
