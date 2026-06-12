package com.smorki.smorkiautopickup.listener;

import com.smorki.smorkiautopickup.SmorkiAutoPickup;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * AsyncBlockListener — Core listener for auto-smelt and auto-pickup.
 *
 * <h2>Event flow (per block break)</h2>
 * <ol>
 *   <li>{@link BlockBreakEvent} fires on the region thread that owns the chunk.</li>
 *   <li>We immediately <em>cancel natural drops</em> so no item entity is spawned
 *       into the world (eliminates entity-tick overhead at high throughput).</li>
 *   <li>The drop list is calculated synchronously using Bukkit's
 *       {@link Block#getDrops(ItemStack)} — this is safe because we are still on
 *       the correct region thread at event time.</li>
 *   <li>If auto-smelt applies, raw-ore stacks are replaced with their smelted
 *       counterparts before the pickup step.</li>
 *   <li>Inventory insertion is dispatched via
 *       {@code player.getScheduler().run(plugin, task, retiredCallback)}
 *       — this guarantees that the inventory mutation happens on the entity's
 *       owning region thread, which is <em>always</em> the correct thread for
 *       direct player inventory writes in Folia.</li>
 * </ol>
 *
 * <h2>Thread-Safety Guarantees</h2>
 * <ul>
 *   <li>All config snapshots ({@code smeltMap}, {@code blacklist}) are
 *       {@code volatile} unmodifiable views — safe to read from any thread.</li>
 *   <li>The {@code playerToggles} map is a {@link java.util.concurrent.ConcurrentHashMap}
 *       — safe to read from any thread without locking.</li>
 *   <li>Inventory mutations ({@link PlayerInventory#addItem}) only occur inside
 *       the entity scheduler callback, never directly in the event handler.</li>
 *   <li>World drops ({@link Location#getWorld()}{@code .dropItemNaturally}) for
 *       full-inventory fallback are dispatched via the region scheduler of the
 *       block's chunk, ensuring the drop is placed in the correct region.</li>
 * </ul>
 *
 * @author  Smorki
 * @version 1.0.0
 */
public final class AsyncBlockListener implements Listener {

    private final SmorkiAutoPickup plugin;
    private final MiniMessage      mm;

    public AsyncBlockListener(final SmorkiAutoPickup plugin) {
        this.plugin = plugin;
        this.mm     = SmorkiAutoPickup.mm();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Event handler
    // ────────────────────────────────────────────────────────────────────────

    /**
     * MONITOR priority: we want all protection/claim plugins (NORMAL/HIGH) to
     * have already made their cancellation decisions before we act.
     * {@code ignoreCancelled = true}: if the event is already cancelled (e.g. by
     * WorldGuard) we do nothing — natural drop behaviour is preserved.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {

        final Player player = event.getPlayer();

        // ── Guard: feature switches ──────────────────────────────────────
        if (!plugin.isAutoPickupEnabled()) return;

        // ── Guard: creative mode (optional) ─────────────────────────────
        if (!plugin.isIncludeCreative()
                && player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return;
        }

        // ── Guard: player toggle ─────────────────────────────────────────
        if (!plugin.isPickupActive(player.getUniqueId())) return;

        final Block     block    = event.getBlock();
        final Material  blockMat = block.getType();

        // ── Guard: blacklist ─────────────────────────────────────────────
        final Set<Material> blacklist = plugin.getBlacklist();
        if (blacklist.contains(blockMat)) return;

        // ── Suppress natural item drops ──────────────────────────────────
        // Setting this to true prevents Bukkit from spawning ground Item entities,
        // eliminating entity-tick overhead for every mined block.
        event.setDropItems(false);

        // ── Calculate drops (still on region thread — safe) ──────────────
        final ItemStack tool  = player.getInventory().getItemInMainHand();
        final List<ItemStack> drops = computeDrops(block, tool, blockMat, event);

        if (drops.isEmpty()) {
            // Nothing to give (e.g. silk-touch on stone-like block with no silk drop,
            // or the block simply has no drops). Nothing else to do.
            return;
        }

        // ── XP ───────────────────────────────────────────────────────────
        // Paper's BlockBreakEvent.setExpToDrop() still applies even when
        // setDropItems(false) is used, so XP orbs are dropped normally
        // (centred on the block) unless we override here.
        if (plugin.isXpEnabled() && plugin.getSmeltMap().containsKey(blockMat)) {
            final int currentXp = event.getExpToDrop();
            event.setExpToDrop(currentXp + plugin.getXpPerSmelt());
        }

        // ── Snapshot drops as an immutable list for lambda capture ───────
        final List<ItemStack> finalDrops = Collections.unmodifiableList(drops);
        final Location dropLocation = block.getLocation().clone();

        // ── Schedule inventory insertion on the player's entity region ───
        // player.getScheduler().run() is the correct Folia API for mutating
        // a player's inventory: it runs on the region that owns the player.
        //
        // The 'retired' callback fires if the player has disconnected between
        // the event and the scheduler tick; in that case we just drop the
        // items at the original block location (world-region task).
        player.getScheduler().run(plugin,
                scheduledTask -> insertOrDrop(player, finalDrops, dropLocation),
                () -> scheduleWorldDrop(finalDrops, dropLocation)   /* retired */
        );
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Drop calculation
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Calculates what items the block would have dropped, applying Silk Touch,
     * Fortune, and auto-smelt transformations.
     *
     * <p>Called on the region thread that owns the block — fully safe.
     *
     * @param block    the broken block
     * @param tool     the tool in the player's main hand (may be AIR)
     * @param blockMat the block's material
     * @param event    the original event (used to check Silk Touch shortcut)
     * @return a mutable list of ItemStacks to give to the player
     */
    private List<ItemStack> computeDrops(
            final Block          block,
            final ItemStack      tool,
            final Material       blockMat,
            final BlockBreakEvent event) {

        // Paper's Block#getDrops(ItemStack) respects Silk Touch and Fortune
        // enchantments on the supplied tool and returns accurate vanilla drops.
        final Collection<ItemStack> rawDrops;
        try {
            rawDrops = block.getDrops(tool, event.getPlayer());
        } catch (final Exception ex) {
            // Defensive: if Paper's API throws for any reason, fall back to empty.
            plugin.getLogger().log(Level.WARNING,
                    "Failed to compute drops for block " + blockMat + ": " + ex.getMessage(), ex);
            return Collections.emptyList();
        }

        if (rawDrops.isEmpty()) return Collections.emptyList();

        final boolean hasSilkTouch = tool != null
                && tool.getType() != Material.AIR
                && tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0;

        final Map<Material, Material> smeltMap = plugin.getSmeltMap();
        final boolean autoSmeltOn = plugin.isAutoSmeltEnabled();

        final List<ItemStack> result = new ArrayList<>(rawDrops.size());

        for (final ItemStack drop : rawDrops) {
            if (drop == null || drop.getType() == Material.AIR || drop.getAmount() <= 0) continue;

            // Do NOT smelt when Silk Touch is active — player intentionally wants the raw block.
            if (autoSmeltOn && !hasSilkTouch) {
                final Material smelted = smeltMap.get(drop.getType());
                if (smelted != null) {
                    // Replace drop with its smelted equivalent, preserving the original amount.
                    result.add(new ItemStack(smelted, drop.getAmount()));
                    continue;
                }
            }
            result.add(drop.clone());
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Inventory insertion
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Attempts to insert every item in {@code drops} into the player's inventory.
     * Any items that did not fit are dropped at {@code dropLocation}.
     *
     * <p><b>Must only be called from the player's entity region scheduler callback.</b>
     *
     * @param player       the receiving player
     * @param drops        the items to insert (unmodifiable snapshot)
     * @param dropLocation world location for overflow drops
     */
    private void insertOrDrop(
            final Player          player,
            final List<ItemStack> drops,
            final Location        dropLocation) {

        final PlayerInventory inv = player.getInventory();
        boolean warnedFull = false;

        for (final ItemStack item : drops) {
            // addItem returns a map of items that could NOT be added (inventory full).
            final Map<Integer, ItemStack> leftover = inv.addItem(item.clone());

            if (!leftover.isEmpty()) {
                // Inventory is full for at least part of this stack.
                if (!warnedFull) {
                    sendFullWarning(player);
                    warnedFull = true;
                }
                // Drop overflow items at the block's location (world-region safe task).
                for (final ItemStack overflow : leftover.values()) {
                    scheduleWorldDrop(Collections.singletonList(overflow), dropLocation);
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  World-region drop (overflow / player retired)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Schedules a natural item drop at {@code location} on the region thread
     * that owns that chunk. This avoids cross-region inventory issues and is the
     * correct Folia pattern for spawning entities into the world.
     *
     * @param items    items to drop
     * @param location world location for the drop
     */
    private void scheduleWorldDrop(
            final List<ItemStack> items,
            final Location        location) {

        if (location.getWorld() == null || items.isEmpty()) return;

        // getRegionScheduler().run() places the task on the scheduler for the
        // region that owns (world, chunkX, chunkZ) — correct for entity spawning.
        plugin.getServer().getRegionScheduler().run(
                plugin,
                location,
                scheduledTask -> {
                    for (final ItemStack item : items) {
                        if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                            location.getWorld().dropItemNaturally(location, item);
                        }
                    }
                }
        );
    }

    // ────────────────────────────────────────────────────────────────────────
    //  UI
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Sends the "inventory full" action-bar warning to the player.
     * Called from inside the entity scheduler callback — safe.
     */
    private void sendFullWarning(final Player player) {
        final String raw = plugin.getMessage(
                "inventory-full",
                "<red><bold>\u26A0</bold> Your inventory is full! Items dropped on the ground.</red>");
        final Component msg = mm.deserialize(raw);
        player.sendActionBar(msg);
    }
}
