Kral, sinirlenme haklısın! Kod blokları (```) araya girince parça parça kopyalamak zorunda kalıyorsun, o da işi zorlaştırıyor.

Madem tüm dosyayı tek bir seferde, hiç bölünmeden çat diye kopyalamak istiyorsun; aradaki o kod kutularının hepsini kaldırdım. Aşağıdaki düz metnin en başından en sonuna kadar tek bir seferde hepsini seçip kopyalayabilirsin!

SmorkiAutoPickup
by smorki

Enterprise-grade, ultra-performance economy and utility mechanics plugin engineered specifically for Folia's regionized multi-threaded architectures.

Version: 1.0.0
Platform: Folia | Paper 1.21.11
Java: 21
License: MIT



[SmorkiAutoPickup] v1.0.0 (Target: Folia 1.21.11)
[SmorkiAutoPickup] Engineered by Smorki

What it does
Traditional block-breaking listeners lock the main tick loop when manipulating player inventories, validating drops, or computing massive drop mathematics during heavy mining bursts (e.g., BoxPVP custom bombs or high-efficiency haste mining). On high-player-count networks, this creates massive thread contention and instant TPS drops.

SmorkiAutoPickup solves this efficiently by utilizing multi-threaded operations:

Intercepts block breaks on Paper's BlockBreakEvent and cancels natural drops instantly to prevent ground-entity item lag.

Processes heavy math configurations (Fortune multipliers and Silk Touch triggers) completely asynchronously.

Offloads inventory insertions directly to the player's owning entity/region scheduler—completely eliminating region-wide lag spikes.

Uses a thread-safe caching system to manage individual player preferences seamlessly.

Performance architecture
Why Folia's Threading Model Requires Asynchronous Execution
Vanilla Paper runs game mechanics on a single primary tick loop. Folia replaces this with independent, regionized thread sheets. Offloading heavy task computations away from these hot region paths is mandatory for maintaining smooth performance.

World
├── Region A (Mining Zone)  → Thread A [SmorkiAutoPickup Processes Async Tasks Here]
├── Region B (Spawn Area)   → Thread B [Completely Unaffected by Mining Drops]
└── Region C (Factions/PvP) → Thread C [Zero Tick Interruption]

SmorkiAutoPickup maps perfectly to this modern infrastructure:

Drop Formula Interception: Region Thread (Event Pipeline) -> Captures block breakdowns directly on the ticking thread without stalling.

Fortune & Smelt Processing: Asynchronous Multi-threading -> Mathematical drop matrix calculations happen outside the game loop.

Inventory Ingestion: player.getScheduler().run(...) -> Safely modifies the player's personal inventory inside their respective region.

Configuration Syncing: AsyncScheduler Execution -> Reading file IO parameters never blocks a running active game thread.

Data Management Performance Matrix
ConcurrentHashMap: Player preference cache (Toggles) -> Lock-striped architecture allows concurrent reads/writes with zero lock-ups.

Folia Entity Scheduler: Secure inventory modification -> Dispatches inventory additions to the specific region thread owning the player.

Adventure API (MiniMessage): Advanced Text Component parsing -> Immutable component tree processing prevents legacy string compilation delays.

Installation
Verify that your server software is running Folia or Paper (Minecraft 1.21.11).

Download SmorkiAutoPickup-1.0.0.jar from the Releases portal.

Drop the compiled artifact file into your server's local plugins/ directory.

Restart your target instance securely to enable correct architecture hook-ups.

Configuration (config.yml)
settings:
async-processing: true
give-xp: true
filter-offline-cache: true

messages:
prefix: "gradient:#8E44AD:#00FFFF℠ SMORKIAUTOPICKUP <dark_gray>»</dark_gray> "
no-permission: "⚠️ YETKİNİZ BULUNMAMAKTADIR."
toggle-on: "Auto-Pickup özelliği gradient:#8E44AD:#00FFFFAKTİF hâle getirildi."
toggle-off: "Auto-Pickup özelliği DEAKTİF hâle getirildi."
inventory-full: "⚠️ ENVANTERİNİZ DOLU! Maddeler yere düşürüldü."

smelt-targets:
IRON_ORE: "IRON_INGOT"
DEEPSLATE_IRON_ORE: "IRON_INGOT"
GOLD_ORE: "GOLD_INGOT"
DEEPSLATE_GOLD_ORE: "GOLD_INGOT"
COPPER_ORE: "COPPER_INGOT"
DEEPSLATE_COPPER_ORE: "COPPER_INGOT"

Commands & Permissions
/autopickup toggle -> Toggles the auto-smelt/pickup engine on or off. (Permission: None)

/smorkiautopickup reload -> Asynchronously reloads all configuration details. (Permission: smorkiautopickup.admin)

Project Structure
src/main/java/com/smorki/smorkiautopickup/
├── SmorkiAutoPickup.java         Main plugin bootstrap, lifecycles, and console logs
├── config/
│   └── ConfigManager.java        Thread-safe configurations loader and async handler
├── listener/
│   └── AsyncBlockListener.java   Intercepts block drops, fortune handling, auto-smelt
├── command/
│   └── PickupCommand.java        Executes toggle controls & administrative reloads
└── util/
└── MessageUtil.java          Adventure MiniMessage parsing engine for gradients

Building from source
Requirements: Java 21+, Maven 3.9+

git clone https://github.com/smorki/SmorkiAutoPickup.git
cd SmorkiAutoPickup
mvn clean package

Output Artifact Location: target/SmorkiAutoPickup-1.0.0.jar
Why this matters for high-tier networks
Scenario: 50 players mining with Haste III / Bombs

Without SmorkiAutoPickup: Global thread stalls, item drops trigger massive ground-entity lag spikes.

With SmorkiAutoPickup: Ground drops are bypassed. Mechanics calculation drops off to async processing.

Scenario: Player inventory fills completely

Without SmorkiAutoPickup: Server loops continuously checking drop positions, creating spikes.

With SmorkiAutoPickup: Instant actionbar alerts appear using MiniMessage. Drops dump safely without thread hitching.

Scenario: Administrative configuration hot-reloads

Without SmorkiAutoPickup: Server thread blocks IO channels, risking chunk tick synchronization.

With SmorkiAutoPickup: Async parsing reads configuration details safely in separate context threads.

License
MIT — feel free to use, tweak, or redistribute with proper attribution.

Made with care by Smorki
