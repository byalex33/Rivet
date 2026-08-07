package dev.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SpawnEggMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

final class EggCapture implements Listener {
    private static final int ANIMATION_TICKS = 28;
    private static final Particle.DustTransition VORTEX_DUST = new Particle.DustTransition(
        Color.fromRGB(85, 255, 255), Color.fromRGB(190, 85, 255), 1.25f);

    private final CorePlugin plugin;
    private final Map<UUID, Capture> captures = new HashMap<>();

    EggCapture(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEggHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Egg egg)
            || !(egg.getShooter() instanceof Player player)
            || !(event.getHitEntity() instanceof Mob mob)
            || !player.hasPermission("core.eggcapture")
            || captures.containsKey(mob.getUniqueId())) {
            return;
        }

        EntitySnapshot snapshot = mob.createSnapshot();
        Material material = Bukkit.getItemFactory().getSpawnEgg(mob.getType());
        AttributeInstance scale = mob.getAttribute(Attribute.SCALE);
        if (snapshot == null || material == null || scale == null) {
            player.sendActionBar(Component.text("That creature cannot be captured.", NamedTextColor.RED));
            return;
        }

        ItemStack capturedEgg = new ItemStack(material);
        if (!(capturedEgg.getItemMeta() instanceof SpawnEggMeta meta)) {
            return;
        }
        meta.setSpawnedEntity(snapshot);
        meta.displayName(Component.text("Captured ", NamedTextColor.LIGHT_PURPLE).append(mob.name()));
        meta.lore(java.util.List.of(Component.text("Contains the original creature.", NamedTextColor.GRAY)));
        capturedEgg.setItemMeta(meta);

        event.setCancelled(true);
        egg.remove();
        Capture capture = new Capture(mob, player, capturedEgg, scale, scale.getBaseValue(),
            mob.hasAI(), mob.hasGravity(), mob.isInvulnerable(), mob.isGlowing());
        captures.put(mob.getUniqueId(), capture);
        mob.setAI(false);
        mob.setGravity(false);
        mob.setInvulnerable(true);
        mob.setGlowing(true);
        mob.setVelocity(new Vector());
        animate(capture);
    }

    void shutdown() {
        captures.values().forEach(capture -> {
            restore(capture);
            give(capture.player, new ItemStack(Material.EGG), capture.mob.getLocation());
        });
        captures.clear();
    }

    private void animate(Capture capture) {
        Mob mob = capture.mob;
        mob.getWorld().playSound(mob.getLocation(), Sound.BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE,
            .8f, 1.35f);
        new BukkitRunnable() {
            private int tick;

            @Override
            public void run() {
                if (!captures.containsKey(mob.getUniqueId())) {
                    cancel();
                    return;
                }
                if (tick == ANIMATION_TICKS) {
                    captures.remove(mob.getUniqueId());
                    cancel();
                    pop(capture);
                    return;
                }

                try {
                    double progress = (tick + 1d) / ANIMATION_TICKS;
                    capture.scale.setBaseValue(captureScale(capture.originalScale, progress));
                    Location center = mob.getLocation().add(0, Math.min(mob.getHeight() / 2, 4), 0);
                    double radius = .65 + progress * 1.5;
                    for (int point = 0; point < 4; point++) {
                        double angle = tick * .55 + point * Math.PI / 2;
                        Location ring = center.clone().add(Math.cos(angle) * radius,
                            Math.sin(tick * .35 + point) * .45, Math.sin(angle) * radius);
                        mob.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, ring, 1, VORTEX_DUST);
                    }
                    mob.getWorld().spawnParticle(Particle.REVERSE_PORTAL, center, 8,
                        radius, Math.max(1, mob.getHeight() / 3), radius, .15);
                    tick++;
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "Capture animation failed for "
                        + mob.getType() + "; completing capture without it.", exception);
                    captures.remove(mob.getUniqueId());
                    cancel();
                    pop(capture);
                }
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    private void pop(Capture capture) {
        Location drop = capture.mob.getLocation();
        Location center = drop.clone().add(0, Math.min(capture.mob.getHeight() / 2, 4), 0);
        World world = drop.getWorld();
        capture.mob.remove();
        give(capture.player, capture.egg, drop);
        world.spawnParticle(Particle.FLASH, center, 1);
        world.spawnParticle(Particle.EXPLOSION, center, 4, .5, .5, .5, 0);
        world.spawnParticle(Particle.ELECTRIC_SPARK, center, 45, 1.5, 1.5, 1.5, .3);
        world.spawnParticle(Particle.REVERSE_PORTAL, center, 70, 1.5, 1.5, 1.5, .4);
        world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1, .8f);
    }

    private static void restore(Capture capture) {
        if (!capture.mob.isValid()) {
            return;
        }
        capture.scale.setBaseValue(capture.originalScale);
        capture.mob.setAI(capture.ai);
        capture.mob.setGravity(capture.gravity);
        capture.mob.setInvulnerable(capture.invulnerable);
        capture.mob.setGlowing(capture.glowing);
    }

    private static void give(Player player, ItemStack item, Location fallback) {
        if (!player.isOnline()) {
            fallback.getWorld().dropItemNaturally(fallback, item);
            return;
        }
        player.getInventory().addItem(item).values()
            .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.playSound(player.getLocation(), Sound.ENTITY_ALLAY_ITEM_GIVEN, .8f, 1.3f);
    }

    static double captureScale(double original, double progress) {
        return original + (Math.min(original * 6, 16) - original) * progress * progress;
    }

    private record Capture(Mob mob, Player player, ItemStack egg, AttributeInstance scale,
                           double originalScale, boolean ai, boolean gravity,
                           boolean invulnerable, boolean glowing) {
    }
}
