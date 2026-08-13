package dev.rivet;

import io.papermc.paper.event.player.PlayerTradeEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

final class VillagerRerollModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private static final String DEFAULT_PERMISSION = "rivet.villager-reroll";

    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final NamespacedKey rerollMarker;
    private final Set<UUID> rerolling = new HashSet<>();

    VillagerRerollModule(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("villager-reroll");
        rerollMarker = new NamespacedKey(plugin, "villager_reroll_trade");
        cleanupLoadedVillagers();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
            || !(event.getRightClicked() instanceof Villager villager)
            || !canUse(event.getPlayer())
            || !canReroll(villager)) {
            return;
        }

        addRerollTrade(villager);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (villager.isValid() && !hasViewer(villager)) {
                removeRerollTrade(villager);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onMerchantClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
            || !(event.getInventory() instanceof MerchantInventory merchant)
            || event.getRawSlot() != 2
            || !selectedRerollTrade(merchant, event.getCurrentItem())) {
            return;
        }

        event.setCancelled(true);
        if (!(merchant.getMerchant() instanceof Villager villager)) {
            return;
        }
        requestReroll(player, villager);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRerollPurchase(PlayerTradeEvent event) {
        if (!isRerollItem(event.getTrade().getResult())) {
            return;
        }
        event.setCancelled(true);
        if (event.getMerchant() instanceof Villager villager) {
            requestReroll(event.getPlayer(), villager);
        }
    }

    @EventHandler
    public void onMerchantClose(InventoryCloseEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory merchant)
            || !(merchant.getMerchant() instanceof Villager villager)) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (villager.isValid() && !hasViewer(villager)) {
                removeRerollTrade(villager);
            }
        });
    }

    void shutdown() {
        rerolling.clear();
        cleanupLoadedVillagers();
    }

    private void requestReroll(Player player, Villager villager) {
        if (!canUse(player)) {
            message(player, "messages.no-permission",
                "<white>You do not have permission to reroll villager trades.</white>");
            return;
        }
        if (!canReroll(villager)) {
            removeRerollTrade(villager);
            message(player, restrictionMessage(villager), restrictionFallback(villager));
            return;
        }
        if (!rerolling.add(villager.getUniqueId())) {
            message(player, "messages.busy",
                "<white>That villager is already rerolling its trades.</white>");
            return;
        }

        List<MerchantRecipe> previous = ordinaryRecipes(villager);
        try {
            removeRerollTrade(villager);
            int level = villager.getVillagerLevel();
            villager.resetOffers();
            int extra = extraTradesForLevel(level);
            if (extra > 0) {
                villager.addTrades(extra);
            }
            if (ordinaryRecipes(villager).isEmpty()) {
                villager.setRecipes(previous);
                throw new IllegalStateException("Paper generated no replacement offers");
            }
            addRerollTrade(villager);
        } catch (RuntimeException exception) {
            villager.setRecipes(previous);
            rerolling.remove(villager.getUniqueId());
            plugin.getLogger().log(Level.WARNING,
                "Could not reroll trades for villager " + villager.getUniqueId(), exception);
            message(player, "messages.failed",
                "<white>The villager's trades could not be rerolled.</white>");
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            rerolling.remove(villager.getUniqueId());
            if (!player.isOnline() || !villager.isValid()) {
                removeRerollTrade(villager);
                return;
            }
            player.openMerchant(villager, true);
            player.playSound(player.getLocation(), configuredSound(),
                (float) Math.max(0, settings.getDouble("effects.sound.volume", .8)),
                (float) Math.max(0, settings.getDouble("effects.sound.pitch", 1.2)));
            message(player, "messages.rerolled",
                "<white>Villager trades <#f72a4c>rerolled</#f72a4c>.</white>");
        });
    }

    private boolean canUse(Player player) {
        String permission = settings.getString("permission", DEFAULT_PERMISSION);
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    private boolean canReroll(Villager villager) {
        if (!villager.isAdult()
            || villager.getProfession() == Villager.Profession.NONE
            || villager.getProfession() == Villager.Profession.NITWIT) {
            return false;
        }
        if (!settings.getBoolean("allow-after-trading", false)
            && hasBeenTraded(villager.getVillagerLevel(), villager.getVillagerExperience(),
                ordinaryRecipes(villager).stream().mapToInt(MerchantRecipe::getUses).toArray())) {
            return false;
        }
        return !settings.getBoolean("require-workstation", true)
            || villager.getMemory(MemoryKey.JOB_SITE) != null;
    }

    private String restrictionMessage(Villager villager) {
        if (settings.getBoolean("require-workstation", true)
            && villager.getMemory(MemoryKey.JOB_SITE) == null) {
            return "messages.no-workstation";
        }
        return "messages.trades-locked";
    }

    private String restrictionFallback(Villager villager) {
        if (settings.getBoolean("require-workstation", true)
            && villager.getMemory(MemoryKey.JOB_SITE) == null) {
            return "<white>This villager needs its workstation before its trades can be rerolled.</white>";
        }
        return "<white>This villager has already been traded with, so its trades are locked.</white>";
    }

    private void addRerollTrade(Villager villager) {
        List<MerchantRecipe> recipes = ordinaryRecipes(villager);
        recipes.add(createRerollTrade());
        villager.setRecipes(recipes);
    }

    private void removeRerollTrade(Villager villager) {
        List<MerchantRecipe> current = villager.getRecipes();
        List<MerchantRecipe> filtered = current.stream()
            .filter(recipe -> !isRerollItem(recipe.getResult())).toList();
        if (filtered.size() != current.size()) {
            villager.setRecipes(filtered);
        }
    }

    private List<MerchantRecipe> ordinaryRecipes(Villager villager) {
        return new ArrayList<>(villager.getRecipes().stream()
            .filter(recipe -> !isRerollItem(recipe.getResult())).toList());
    }

    private MerchantRecipe createRerollTrade() {
        ItemStack result = new ItemStack(configuredMaterial(
            settings.getString("trade.result.material"), Material.NETHER_STAR));
        result.editMeta(meta -> {
            meta.displayName(MM.deserialize(settings.getString("trade.result.name",
                "<#f72a4c><bold>↻ Reroll trades</bold></#f72a4c>")));
            List<String> configuredLore = settings.getStringList("trade.result.lore");
            if (configuredLore.isEmpty()) {
                configuredLore = List.of(
                    "<white>Generate fresh vanilla trades.</white>",
                    "<white>The emerald is not consumed.</white>");
            }
            meta.lore(configuredLore.stream().map(MM::deserialize).toList());
            meta.getPersistentDataContainer().set(rerollMarker, PersistentDataType.BYTE, (byte) 1);
        });

        MerchantRecipe recipe = new MerchantRecipe(result, 9_999, 0, false, 0, 0f);
        recipe.addIngredient(new ItemStack(configuredMaterial(
            settings.getString("trade.ingredient.material"), Material.EMERALD),
            Math.max(1, settings.getInt("trade.ingredient.amount", 1))));
        recipe.setExperienceReward(false);
        recipe.setVillagerExperience(0);
        recipe.setIgnoreDiscounts(true);
        return recipe;
    }

    private boolean selectedRerollTrade(MerchantInventory inventory, ItemStack clicked) {
        if (isRerollItem(clicked) || isRerollItem(inventory.getItem(2))) {
            return true;
        }
        MerchantRecipe selected = inventory.getSelectedRecipe();
        return selected != null && isRerollItem(selected.getResult());
    }

    private boolean isRerollItem(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer()
                .has(rerollMarker, PersistentDataType.BYTE);
    }

    private boolean hasViewer(Villager villager) {
        return plugin.getServer().getOnlinePlayers().stream().anyMatch(player ->
            player.getOpenInventory().getTopInventory() instanceof MerchantInventory inventory
                && inventory.getMerchant() instanceof Villager open
                && open.getUniqueId().equals(villager.getUniqueId()));
    }

    private void cleanupLoadedVillagers() {
        plugin.getServer().getWorlds().forEach(world ->
            world.getEntitiesByClass(Villager.class).forEach(this::removeRerollTrade));
    }

    private Sound configuredSound() {
        return ConfiguredEffect.sound(plugin, settings, "effects.sound.name",
            Sound.ENTITY_VILLAGER_WORK_LIBRARIAN);
    }

    private void message(Player player, String path, String fallback) {
        if (!settings.getBoolean(path + ".enabled", true)) {
            return;
        }
        player.sendMessage(MM.deserialize(settings.getString(path + ".text", fallback)));
    }

    static boolean hasBeenTraded(int level, int experience, int... recipeUses) {
        if (level > 1 || experience > 0) {
            return true;
        }
        for (int uses : recipeUses) {
            if (uses > 0) {
                return true;
            }
        }
        return false;
    }

    static int extraTradesForLevel(int level) {
        return Math.max(0, Math.min(5, level) - 1) * 2;
    }

    static Material configuredMaterial(String configured, Material fallback) {
        Material material = configured == null ? null : Material.matchMaterial(configured);
        return material == null || !material.isItem() ? fallback : material;
    }
}
