package com.smorki.smorkiautopickup;

import com.smorki.smorkiautopickup.command.PickupCommand;
import com.smorki.smorkiautopickup.listener.AsyncBlockListener;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * SmorkiAutoPickup — Main Plugin Class
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Lifecycle management (enable / disable / reload)</li>
 *   <li>Shared state: smelt map, blacklist, player-toggle map</li>
 *   <li>Console branding (ASCII art signature)</li>
 * </ul>
 *
 * <p>All mutable state that is accessed from multiple Folia region threads is
 * exposed through <em>unmodifiable</em> snapshots built on reload, or through
 * {@link ConcurrentHashMap} for per-player toggles. No locks are needed because
 * snapshotted collections are written once and then only read.
 *
 * @author  Smorki
 * @version 1.0.0
 */
public final class SmorkiAutoPickup extends JavaPlugin {

    // ── Singleton access ────────────────────────────────────────────────────
    private static SmorkiAutoPickup instance;

    /** @return the running plugin instance (never null after onEnable) */
    public static SmorkiAutoPickup getInstance() { return instance; }

    // ── Adventure / MiniMessage ─────────────────────────────────────────────
    /** Shared, thread-safe MiniMessage parser (stateless after construction). */
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static MiniMessage mm() { return MM; }

    // ── Shared state (written on main/reload thread, read from any thread) ──

    /**
     * Raw-ore → smelted-result map.
     * Written once per (re)load; afterwards treated as immutable snapshot.
     * {@code volatile} ensures subsequent reads see the freshly assigned map.
     */
    private volatile Map<Material, Material> smeltMap = Collections.emptyMap();

    /**
     * Blocks that should never be auto-picked up.
     * Same volatility contract as smeltMap.
     */
    private volatile Set<Material> blacklist = Collections.emptySet();

    /**
     * Per-player toggle: {@code true} = auto-pickup active.
     * ConcurrentHashMap for lock-free cross-region reads/writes.
     */
    private final ConcurrentHashMap<UUID, Boolean> playerToggles = new ConcurrentHashMap<>();

    // ── Flags read from config ───────────────────────────────────────────────
    private volatile boolean autoSmeltEnabled  = true;
    private volatile boolean autoPickupEnabled = true;
    private volatile boolean includeCreative   = false;
    private volatile boolean defaultEnabled    = true;
    private volatile boolean xpEnabled         = true;
    private volatile int     xpPerSmelt        = 1;

    // ── Listener / command handles ───────────────────────────────────────────
    private AsyncBlockListener blockListener;

    // ────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        instance = this;

        // 1. Save & parse config
        saveDefaultConfig();
        applyConfig();

        // 2. Register listener
        blockListener = new AsyncBlockListener(this);
        Bukkit.getPluginManager().registerEvents(blockListener, this);

        // 3. Register command
        PluginCommand cmd = getCommand("smorkiautopickup");
        if (cmd != null) {
            PickupCommand handler = new PickupCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        } else {
            getLogger().severe("Command 'smorkiautopickup' not found in plugin.yml — check your plugin.yml!");
        }

        // 4. Branding
        printBanner("ENABLED");
    }

    @Override
    public void onDisable() {
        printBanner("DISABLED");
        instance = null;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Config parsing
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Parses {@code config.yml} and atomically replaces all shared-state
     * snapshots. Safe to call from any thread as long as the caller has
     * already invoked {@link #reloadConfig()} beforehand.
     */
    public synchronized void applyConfig() {
        final FileConfiguration cfg = getConfig();

        defaultEnabled    = cfg.getBoolean("default-enabled", true);
        autoSmeltEnabled  = cfg.getBoolean("auto-smelt.enabled", true);
        autoPickupEnabled = cfg.getBoolean("auto-pickup.enabled", true);
        includeCreative   = cfg.getBoolean("auto-pickup.include-creative", false);
        xpEnabled         = cfg.getBoolean("xp.enabled", true);
        xpPerSmelt        = cfg.getInt("xp.amount-per-smelt", 1);

        // ── Build smelt map ───────────────────────────────────────────────
        final Map<Material, Material> newSmelt = new EnumMap<>(Material.class);
        final var recipesSection = cfg.getConfigurationSection("auto-smelt.recipes");
        if (recipesSection != null) {
            for (final String key : recipesSection.getKeys(false)) {
                final Material raw     = parseMaterial(key, null);
                final Material smelted = parseMaterial(recipesSection.getString(key, ""), null);
                if (raw != null && smelted != null) {
                    newSmelt.put(raw, smelted);
                } else {
                    getLogger().warning("Invalid smelt recipe entry: " + key
                            + " -> " + recipesSection.getString(key));
                }
            }
        }
        smeltMap = Collections.unmodifiableMap(newSmelt);

        // ── Build blacklist ───────────────────────────────────────────────
        final Set<Material> newBlacklist = EnumSet.noneOf(Material.class);
        for (final String entry : cfg.getStringList("auto-pickup.blacklist")) {
            final Material m = parseMaterial(entry, null);
            if (m != null) {
                newBlacklist.add(m);
            } else {
                getLogger().warning("Unknown material in blacklist: " + entry);
            }
        }
        blacklist = Collections.unmodifiableSet(newBlacklist);
    }

    /** Null-safe, case-insensitive material parser. */
    private static Material parseMaterial(final String name, final Material fallback) {
        if (name == null || name.isBlank()) return fallback;
        try {
            return Material.valueOf(name.trim().toUpperCase());
        } catch (final IllegalArgumentException ex) {
            return fallback;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Public accessors (read-only, safe from any thread)
    // ────────────────────────────────────────────────────────────────────────

    public Map<Material, Material> getSmeltMap()        { return smeltMap; }
    public Set<Material>           getBlacklist()       { return blacklist; }
    public ConcurrentHashMap<UUID, Boolean> getPlayerToggles() { return playerToggles; }

    public boolean isAutoSmeltEnabled()  { return autoSmeltEnabled; }
    public boolean isAutoPickupEnabled() { return autoPickupEnabled; }
    public boolean isIncludeCreative()   { return includeCreative; }
    public boolean isDefaultEnabled()    { return defaultEnabled; }
    public boolean isXpEnabled()         { return xpEnabled; }
    public int     getXpPerSmelt()       { return xpPerSmelt; }

    /**
     * Returns whether auto-pickup is currently active for the given player UUID.
     * Falls back to {@link #isDefaultEnabled()} for first-time players.
     */
    public boolean isPickupActive(final UUID uuid) {
        return playerToggles.getOrDefault(uuid, defaultEnabled);
    }

    /** Retrieve a MiniMessage string from config, with a plain-text fallback. */
    public String getMessage(final String path, final String fallback) {
        final String raw = getConfig().getString("messages." + path, fallback);
        return raw == null ? fallback : raw.strip();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  ASCII Branding
    // ────────────────────────────────────────────────────────────────────────

    /** Public entry-point used by {@link com.smorki.smorkiautopickup.command.PickupCommand} on reload. */
    public void printBannerPublic(final String status) { printBanner(status); }

    private void printBanner(final String status) {
        final Logger log = getLogger();
        log.info("-----------------------------------------------------------------------------------------");
        log.info("  ____                      _    _               _        ____  _      _               ");
        log.info(" / ___| _ __ ___   ___  _ __| | _(_)  /\\  _   _ _| |_ ___ |  _ \\(_) ___| | ___   _ _ __ ");
        log.info(" \\___ \\| '_ ` _ \\ / _ \\| '__| |/ / | /  \\| | | |_   _/ _ \\| |_) | |/ __| |/ / | | | '_ \\ ");
        log.info("  ___) | | | | | | (_) | |  |   <| |/ /\\ \\ |_| | | || (_) |  __/| | (__|   <| |_| | |_) |");
        log.info(" |____/|_| |_| |_|\\___/|_|  |_|\\_\\_/_/  \\_\\__,_| \\__/\\___/|_|   |_|\\___|_|\\_\\\\__,_| .__/ ");
        log.info("                                                                                  |_|    ");
        log.info(" [SmorkiAutoPickup] v1.0.0 (Target: Folia 1.21.4+ (MC 1.21.11))");
        log.info(" [SmorkiAutoPickup] Engineered by Smorki");
        log.info(" [SmorkiAutoPickup] Status: [" + status + "]");
        log.info("-----------------------------------------------------------------------------------------");
    }
}
