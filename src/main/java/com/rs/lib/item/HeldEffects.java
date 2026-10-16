package com.rs.lib.item;

import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.rs.lib.Rs;
import com.rs.lib.scheduler.RsScheduler;
import com.rs.lib.scheduler.TaskHandle;

/**
 * The {@code held-effects} of one plugin's items: a timer that, every
 * {@value ItemRegistry#HELD_EFFECT_PERIOD} ticks, gives each online player the effects of the
 * items they hold or wear, on that player's own thread.
 */
final class HeldEffects {

    private enum Hold { MAINHAND, OFFHAND, ARMOR }

    private final ItemRegistry registry;
    private final Rs rs;
    private final Logger logger;
    private TaskHandle task;

    HeldEffects(ItemRegistry registry, Rs rs, Logger logger) {
        this.registry = registry;
        this.rs = rs;
        this.logger = logger;
    }

    /** Starts the timer unless it runs already or the registry is shut down. */
    void start() {
        if (task != null || registry.closed()) {
            return;
        }
        try {
            task = rs.scheduler().timer(ItemRegistry.HELD_EFFECT_PERIOD, ItemRegistry.HELD_EFFECT_PERIOD, this::applyAll);
        } catch (RuntimeException notScheduled) {
            logger.warning("Held item effects could not start: " + notScheduled);
        }
    }

    /** Stops the timer. */
    void cancel() {
        TaskHandle running = task;
        task = null;
        if (running != null) {
            running.cancel();
        }
    }

    /** Every 40 ticks: each online player's held effects, applied on that player's own thread. */
    private void applyAll() {
        RsScheduler scheduler = rs.scheduler();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (scheduler.ownsHere(player)) {
                apply(player);
            } else {
                try {
                    scheduler.entity(player, () -> apply(player));
                } catch (RuntimeException disabled) {
                    return;
                }
            }
        }
    }

    private void apply(Player player) {
        if (registry.closed() || !player.isOnline()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        held(player, inventory.getItemInMainHand(), Hold.MAINHAND);
        held(player, inventory.getItemInOffHand(), Hold.OFFHAND);
        for (ItemStack armour : inventory.getArmorContents()) {
            held(player, armour, Hold.ARMOR);
        }
    }

    private void held(Player player, ItemStack item, Hold where) {
        String id = registry.idOf(item);
        ItemDef def = registry.def(id);
        if (def == null) {
            return;
        }
        List<String> lines = switch (where) {
            case MAINHAND -> def.heldEffectsMainhand();
            case OFFHAND -> def.heldEffectsOffhand();
            case ARMOR -> def.heldEffectsArmor();
        };
        for (String line : lines) {
            List<String> parts = ItemParts.tokens(List.of(line));
            if (parts.isEmpty()) {
                continue;
            }
            PotionEffectType type = ItemCompat.effect(parts.get(0));
            if (type == null) {
                ItemWarnings.to(logger, "item " + id + " -> held-effects").accept("Unknown effect '" + parts.get(0) + "'; skipped.");
                continue;
            }
            Integer amplifier = parts.size() > 1 ? ItemParts.intOrNull(parts.get(1)) : Integer.valueOf(0);
            player.addPotionEffect(new PotionEffect(type, ItemRegistry.HELD_EFFECT_TICKS,
                    amplifier == null ? 0 : Math.max(0, amplifier), true, false));
        }
    }
}
