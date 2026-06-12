package com.smorki.smorkiautopickup.command;

import com.smorki.smorkiautopickup.SmorkiAutoPickup;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * PickupCommand — Handles {@code /smorkiautopickup} and its aliases.
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>{@code toggle}  — flips auto-pickup on/off for the executing player.</li>
 *   <li>{@code reload}  — asynchronously reloads config.yml (admin-only).</li>
 * </ul>
 *
 * <h3>Thread-Safety</h3>
 * <p>{@code toggle} modifies only the {@link java.util.concurrent.ConcurrentHashMap}
 * inside the plugin — safe from any thread. {@code reload} schedules a one-shot
 * async task via Bukkit's global async scheduler so it never blocks the region
 * thread that dispatched the command.
 *
 * @author  Smorki
 * @version 1.0.0
 */
public final class PickupCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS       = List.of("toggle", "reload");
    private static final List<String> SUB_COMMANDS_ADMIN = List.of("toggle", "reload");
    private static final List<String> SUB_COMMANDS_USER  = List.of("toggle");

    private final SmorkiAutoPickup plugin;
    private final MiniMessage       mm;

    public PickupCommand(final SmorkiAutoPickup plugin) {
        this.plugin = plugin;
        this.mm     = SmorkiAutoPickup.mm();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Command dispatch
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(
            @NotNull  final CommandSender sender,
            @NotNull  final Command       command,
            @NotNull  final String        label,
            @NotNull  final String[]      args) {

        if (args.length == 0) {
            sendMessage(sender, plugin.getMessage("unknown-command",
                    "<color:#8E44AD><bold>[SAP]</bold></color> "
                    + "<color:#00FFFF>Usage: <white>/autopickup <toggle|reload></white></color>"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "toggle" -> handleToggle(sender);
            case "reload" -> handleReload(sender);
            default       -> sendMessage(sender, plugin.getMessage("unknown-command",
                    "<color:#8E44AD><bold>[SAP]</bold></color> "
                    + "<color:#00FFFF>Usage: <white>/autopickup <toggle|reload></white></color>"));
        }
        return true;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Sub-command: toggle
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Flips the player's auto-pickup state.
     *
     * <p>Only {@link Player} senders are supported; console makes no sense here.
     * The toggle map is a {@link java.util.concurrent.ConcurrentHashMap}, so this
     * compute is atomic and safe regardless of which Folia region thread runs it.
     */
    private void handleToggle(final CommandSender sender) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text("Only players can toggle auto-pickup."));
            return;
        }

        if (!player.hasPermission("smorkiautopickup.use")) {
            sendMessage(sender, plugin.getMessage("reload-no-permission",
                    "<color:#8E44AD><bold>[SAP]</bold></color> "
                    + "<red>You do not have permission to run this command.</red>"));
            return;
        }

        final UUID uuid = player.getUniqueId();

        // Atomic flip: reads current state (defaulting to config default) then inverts.
        final boolean newState = plugin.getPlayerToggles()
                .compute(uuid, (k, current) -> {
                    final boolean cur = current != null ? current : plugin.isDefaultEnabled();
                    return !cur;
                });

        final String msgKey = newState ? "toggle-enabled" : "toggle-disabled";
        final String fallback = newState
                ? "<color:#8E44AD><bold>[SAP]</bold></color> <color:#00FFFF>Auto-Pickup & Auto-Smelt <bold>ENABLED</bold>.</color>"
                : "<color:#8E44AD><bold>[SAP]</bold></color> <color:#00FFFF>Auto-Pickup & Auto-Smelt <bold>DISABLED</bold>.</color>";

        sendMessage(sender, plugin.getMessage(msgKey, fallback));
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Sub-command: reload
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Reloads {@code config.yml} on a Folia async task to avoid blocking the
     * region thread (disk I/O + YAML parse can take a few ms).
     *
     * <p>After the config is parsed on the async thread, the success message
     * is dispatched back via the entity/global scheduler so the reply always
     * reaches the player's chunk region.
     */
    private void handleReload(final CommandSender sender) {
        if (!sender.hasPermission("smorkiautopickup.admin")) {
            sendMessage(sender, plugin.getMessage("reload-no-permission",
                    "<color:#8E44AD><bold>[SAP]</bold></color> "
                    + "<red>You do not have permission to run this command.</red>"));
            return;
        }

        // Schedule on Bukkit's global async scheduler — non-blocking for region threads.
        plugin.getServer().getAsyncScheduler().runNow(plugin, scheduledTask -> {
            plugin.reloadConfig();
            plugin.applyConfig();
            plugin.printBannerPublic("RELOADED");

            // Deliver feedback back on the correct scheduler context.
            final String successMsg = plugin.getMessage("reload-success",
                    "<color:#8E44AD><bold>[SAP]</bold></color> "
                    + "<color:#00FFFF>Configuration reloaded successfully.</color>");

            if (sender instanceof final Player player) {
                // Use the entity scheduler so the message arrives in the player's region.
                player.getScheduler().run(plugin,
                        t -> sendMessage(sender, successMsg),
                        null /* retired callback — player logged off */);
            } else {
                // Console: just send directly; no region concern.
                sendMessage(sender, successMsg);
            }
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Tab completion
    // ────────────────────────────────────────────────────────────────────────

    @Override
    @Nullable
    public List<String> onTabComplete(
            @NotNull  final CommandSender sender,
            @NotNull  final Command       command,
            @NotNull  final String        alias,
            @NotNull  final String[]      args) {

        if (args.length == 1) {
            final String partial = args[0].toLowerCase();
            final List<String> choices = sender.hasPermission("smorkiautopickup.admin")
                    ? SUB_COMMANDS_ADMIN
                    : SUB_COMMANDS_USER;
            return choices.stream()
                    .filter(s -> s.startsWith(partial))
                    .toList();
        }
        return List.of();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────────────

    private void sendMessage(final CommandSender sender, final String miniMessageStr) {
        sender.sendMessage(mm.deserialize(miniMessageStr));
    }
}
