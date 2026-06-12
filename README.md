# SmorkiAutoPickup

> Enterprise-grade, ultra-performance economy and utility mechanics plugin engineered specifically for [Folia](https://github.com/PaperMC/Folia)'s regionized multi-threading architecture.

<div align="center">

![Version](https://img.shields.io/badge/version-1.0.0-8E44AD?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Folia%20%7C%20Paper%201.21.11-00FFFF?style=flat-square&labelColor=1a1a2e)
![Java](https://img.shields.io/badge/java-21+-8E44AD?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-00FFFF?style=flat-square&labelColor=1a1a2e)

</div>

---

## What it does

Redstone lag machines are one of the most effective ways to degrade server performance. On traditional servers a single limiter can guard the whole world, but **Folia runs each region on its own thread** — a naive global lock would introduce cross-region contention and defeat the entire point of Folia.

SmorkiAutoPickup solves this correctly:

- Tracks block break drops **per player, per action** using lock-free thread structures.
- When a block is broken, its drops are **calculated completely asynchronously** — zero impact on other regions.
- Inventory insertions are executed by `player.getScheduler().run()`, which runs on the **same thread that owns the mining player**.
- Configuration reloads happen on `AsyncScheduler` — disk I/O never touches a region thread.

---

## Performance architecture

### Why Folia's RegionScheduler changes everything

Vanilla Paper (and most Paper forks) use a single-threaded tick loop. Every plugin task, event handler, and scheduler callback runs on that one thread. Under load, a spike in one chunk stalls the entire server.

Folia replaces this with a **regionized model**:
World

├── Region A (chunks 0–31, 0–31)    → Thread A
├── Region B (chunks 32–63, 0–31)   → Thread B
└── Region C (chunks 0–31, 32–63)   → Thread C
Each region ticks independently. A lag spike or heavy mining operation in Region A does **not** slow the tick rate of Region B or C.

SmorkiAutoPickup is designed around this model:

| Operation | Scheduler used | Why |
|---|---|---|
| Block break drop cancellation | Region thread (event callback) | Events already fire on the owning region thread — no dispatch needed |
| Fortune & Smelt processing | Asynchronous Multi-threading | Mathematical matrix calculations happen outside the hot game loop |
| Inventory insertion task | `player.getScheduler().run()` | Ingestion runs on the same region thread that currently owns the player |
| Config reload | `AsyncScheduler.runNow` | File I/O must never block a region or game thread |

### Lock-free data structures

| Structure | Usage | Why not synchronized? |
|---|---|---|
| `ConcurrentHashMap` | Player preference cache (Toggles) | Lock-striped; reads never block writers |
| Folia Entity Scheduler | Secure inventory modification | Dispatches inventory additions to the specific region thread owning the player |
| Adventure API (`MiniMessage`) | Advanced Text Component parsing | Immutable component tree processing prevents legacy string compilation delays |

### Event listener design

```java
@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
public void onBlockBreak(BlockBreakEvent event) { … }

LOWEST priority — the event is processed and natural drops are wiped before any other plugin handles it, avoiding wasted downstream drop processing.

ignoreCancelled = true — already-cancelled events (from protection plugins like WorldGuard) are skipped entirely.

No synchronization inside the handler — all shared state is either thread-local to the region or backed by lock-free structures.

Installation
Make sure you are running Folia or Paper (Minecraft 1.21.11) (not Spigot).

Download SmorkiAutoPickup-1.0.0.jar from Releases.

Place it in your server's plugins/ folder.

Restart the server (not /reload — Folia discourages hot-reload).

Configuration (config.yml)
YAML
settings:
  async-processing: true
  give-xp: true
  filter-offline-cache: true

# ============================================================
#  SmorkiAutoPickup — config.yml
#  Theme: Matte Purple (#8E44AD) & Cyan (#00FFFF)
#  MiniMessage syntax is used for ALL message strings.
# ============================================================

# ── General ─────────────────────────────────────────────────
# Whether auto-pickup is enabled by default for new players
# (players who have never run /autopickup toggle).
default-enabled: true

# ── Auto-Smelt ──────────────────────────────────────────────
auto-smelt:
  # Master switch — set to false to disable smelting entirely
  # while keeping auto-pickup active.
  enabled: true

  # Map of raw ore → smelted result.
  # You may add any material pair recognised by the Bukkit API.
  recipes:
    IRON_ORE: IRON_INGOT
    DEEPSLATE_IRON_ORE: IRON_INGOT
    GOLD_ORE: GOLD_INGOT
    DEEPSLATE_GOLD_ORE: GOLD_INGOT
    COPPER_ORE: COPPER_INGOT
    DEEPSLATE_COPPER_ORE: COPPER_INGOT
    NETHER_GOLD_ORE: GOLD_NUGGET
    ANCIENT_DEBRIS: NETHERITE_SCRAP

# ── Auto-Pickup (all blocks) ─────────────────────────────────
auto-pickup:
  # Master switch — set to false to disable pickup entirely.
  enabled: true

  # If true, items are also picked up when the player is in
  # Creative mode (useful for creative-survival hybrid servers).
  include-creative: false

  # Blocks listed here are NEVER auto-picked up (exact Material names).
  # Useful to exclude things like bedrock, barriers, etc.
  blacklist:
    - BEDROCK
    - BARRIER
    - COMMAND_BLOCK
    - CHAIN_COMMAND_BLOCK
    - REPEATING_COMMAND_BLOCK
    - STRUCTURE_BLOCK
    - STRUCTURE_VOID
    - LIGHT

# ── XP Awards ───────────────────────────────────────────────
xp:
  # Grant bonus XP when an ore is auto-smelted.
  enabled: true
  # XP points awarded per smelted ore.
  amount-per-smelt: 1

# ── Messages (MiniMessage format) ───────────────────────────
# Colour palette reference:
#   Matte Purple : <color:#8E44AD>
#   Cyan         : <color:#00FFFF>
#   Red warning  : <red>
messages:
  prefix: "<color:#8E44AD><bold>[SAP]</bold></color> "

  toggle-enabled: >
    <color:#8E44AD><bold>[SAP]</bold></color>
    <color:#00FFFF>Auto-Pickup & Auto-Smelt <bold>ENABLED</bold>.</color>

  toggle-disabled: >
    <color:#8E44AD><bold>[SAP]</bold></color>
    <color:#00FFFF>Auto-Pickup & Auto-Smelt <bold>DISABLED</bold>.</color>

  inventory-full: >
    <red><bold>⚠</bold> Your inventory is full! Items dropped on the ground.</red>

  reload-success: >
    <color:#8E44AD><bold>[SAP]</bold></color>
    <color:#00FFFF>Configuration reloaded successfully.</color>

  reload-no-permission: >
    <color:#8E44AD><bold>[SAP]</bold></color>
    <red>You do not have permission to run this command.</red>

  unknown-command: >
    <color:#8E44AD><bold>[SAP]</bold></color>
    <color:#00FFFF>Usage: <white>/autopickup <toggle|reload></white></color>

Reload without restarting:

/smorkiautopickup reload

git clone [https://github.com/smorki/SmorkiAutoPickup.git](https://github.com/smorki/SmorkiAutoPickup.git)
cd SmorkiAutoPickup
mvn clean package
# Output: target/SmorkiAutoPickup-1.0.0.jar
  
