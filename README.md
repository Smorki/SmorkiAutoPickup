An enterprise-grade, ultra-performance economy and utility plugin engineered specifically for Folia and Paper multi-threaded server architectures running on Minecraft 1.21.11.✨ Features⚡ 100% Region-Safe & Asynchronous: Zero main-thread lag spikes. Fully integrated with Bukkit's Async Scheduler and Folia's regionized thread model.🔥 Auto-Smelt Mechanic: Automatically smelts raw mined ores (Iron, Gold, Copper) into pure ingots instantly.🎒 Integrated Auto-Pickup: Automatically puts mined drops directly into the player's inventory, completely preventing ground-entity item lag.💎 Enchantment Compatibility: Full native support for Fortune multiplier formulas and Silk Touch modifiers without checking loops on the main thread.🎨 Premium Aesthetic: Out of the box, all actionbars, chats, and console logs are themed with a gorgeous Matte Purple and Cyan palette using modern MiniMessage / Adventure API standards.🛠️ Performance Architecture (Why Folia Ready?)Traditional block-breaking listeners lock the main thread when manipulating inventories or computing drops during massive mining bursts (e.g., BoxPVP bombs or efficiency mining).SmorkiAutoPickup safely handles drop mathematics in independent async threads and offloads inventory insertions safely to the target entity's region scheduler:Java// Formulated for Folia's regionized architecture
player.getScheduler().run(plugin, task -> {
    player.getInventory().addItem(smeltedItem);
}, null);
💻 Commands & PermissionsCommandDescriptionPermission/autopickup toggleToggles the auto-smelt/pickup engine on or off.None (Default)/smorkiautopickup reloadAsynchronously reloads config.yml data.smorkiautopickup.admin⚙️ Configuration (config.yml)YAML#  ___ __  __  ___  ___  _  _____   _   _  _ _____ ___  ___ ___ ___ _  _ _   _ ___  
# / __|  \/  |/ _ \| _ \| |/ /_ _| /_\ | | |_   _/ _ \| _ \_ _/ __| |/ / | | | _ \ 
# \__ \ |\/| | (_) |   /| ' < | | / _ \| |_  | || (_) |  _/| | (__| ' <| |_| |  _/ 
# |___/_|  |_|\___/|_|_\|_|\_\___/_/   \_\___|___\___/|_| |___\___|_|\_\\___/|_|   
#
# Engineered by Smorki | Target Architecture: Folia 1.21.11

settings:
  # Interval configuration adjustments if needed
  async-processing: true
  give-xp: true
  filter-offline-cache: true

# Modern Matte Purple (#8E44AD) and Cyan (#00FFFF) Theme Configurations
messages:
  prefix: "<gradient:#8E44AD:#00FFFF><bold>℠ SMORKIAUTOPICKUP</bold></gradient> <dark_gray>»</dark_gray> "
  no-permission: "<red>⚠️ YETKİNİZ BULUNMAMAKTADIR.</red>"
  toggle-on: "<gray>Auto-Pickup özelliği <gradient:#8E44AD:#00FFFF><bold>AKTİF</bold></gradient> hâle getirildi.</gray>"
  toggle-off: "<gray>Auto-Pickup özelliği <red><bold>DEAKTİF</bold></red> hâle getirildi.</gray>"
  inventory-full: "<red>⚠️ ENVANTERİNİZ DOLU! Maddeler yere düşürüldü.</red>"

smelt-targets:
  IRON_ORE: "IRON_INGOT"
  DEEPSLATE_IRON_ORE: "IRON_INGOT"
  GOLD_ORE: "GOLD_INGOT"
  DEEPSLATE_GOLD_ORE: "GOLD_INGOT"
  COPPER_ORE: "COPPER_INGOT"
  DEEPSLATE_COPPER_ORE: "COPPER_INGOT"
🚀 Build SpecificationsLanguage: Java 21Platform APIs: Folia API, Paper API (1.21.11-R0.1-SNAPSHOT)Design Core: Adventure API / MiniMessage (Legacy ampersand formatting free)
