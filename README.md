![Uploading 4392f0ae780a685812812fa1df82f1dfaf6f04cd_0.webp…]()

**Create Warnautics** expands Create Big Cannons with placeable aerial ordnance built for Create machines, moving vehicles, bombing runs, and destructive multiplayer battles.

Place a bomb, point its nose, give it a redstone signal, and let CBC projectile physics handle the rest. Every bomb tier has its own model, flight behaviour, blast power, sound profile, flash, smoke, and terrain response.

# Main features
Aerial bombs

    - Small Bomb — compact general-purpose bomb suitable for dispensers and cassette drops.
    - Medium Bomb — stronger blast with a larger crater and heavier visual effects.
    - Large Bomb — the main heavy payload, with the strongest blast, flash, smoke column, knockback, and distant rumble.
    - Sea Bomb — water-capable ordnance with an animated rear propeller, splash effects, underwater movement, and a dedicated water shockwave.

Bombs are placeable directional blocks. Their nose faces the side used during placement and can be rotated with a Create wrench. A rising redstone signal launches the bomb along its nose direction, including downward drops, horizontal releases, and upward launches.

Falling bombs use looping, distance-aware audio. The sound changes with bomb size and the listener's position, while nearby simultaneous bombs are mixed to avoid painful volume stacking.

## Small-bomb cassettes

Small bombs can be combined into full-size bundles containing 2, 3, or 4 bombs.

    - Right-click a placed small bomb with more small bombs to assemble a cassette in survival.
    - Ready-made 2-, 3-, and 4-bomb variants are available in the creative tab.
    - While powered, a cassette releases one bomb approximately every 1.3 seconds.
    - Removing the redstone signal pauses the sequence, allowing controllable bombing mechanisms.
    - Every cassette size has its own full-size model and visible support rods.

## Land mines

    - Small Mine — antipersonnel mine focused on shrapnel and entity damage instead of carving an oversized crater.
    - Large Mine — anti-vehicle charge designed for heavier targets and moving structures.
    - Mines receive a short arming delay after placement.
    - Ordinary hand breaking does not trigger placed bombs; fire, explosions, sustained projectile damage, and nearby detonations can start a delayed cook-off.

# Explosions and effects

Warnautics builds its terrain destruction around Create Big Cannons behaviour instead of using a plain vanilla explosion:

    - Resistance-aware craters and differentiated bomb power.
    - Reliable outward knockback for entities.
    - Sympathetic detonation with randomized delays for nearby bombs.
    - Native CBC smoke and explosion-cloud particles.
    - Bright distance-scaled flashes, camera response, and low-frequency war rumble.
    - Night-visible blast lighting and elevated sky glow, including light visible above terrain when the direct explosion is hidden behind a hill.
    - Water splashes, bubbles, surface rings, and underwater shockwaves for sea-bomb detonations.

Effect and sound budgets reduce duplicate particles, lights, and audio during large bombing runs. Optional Veil support enhances dynamic lights and bloom; the core explosion effects still work without it.

# Sable and vehicle compatibility

**Sable** is optional.

When Sable is installed:

    - Bombs released from a moving sub-level inherit the structure's pose and inertia.
    - Explosions can damage blocks inside Sable sub-levels instead of affecting only the parent world.
    - Sub-level destruction uses bounded scans and material resistance, preventing unsafe access outside a ship's plot.
    - Ordinary blocks can fail across the blast radius, while obsidian and reinforced materials remain exceptionally resistant.
    - Large mines can react to vehicle-hull contact.

Optional compatibility is also included for:

    - **Create Offroad** — wheels can trigger large anti-vehicle mines.
    - **Sable Player Ragdoll / Ragdoll Reactions** — blast reactions without applying duplicate launch forces.
    - **Veil** — enhanced physical blast lights and post-processing.

These integrations are soft dependencies; they are not required to start the game.

# Crafting and creative inventory

Recipes use materials from Create and Create Big Cannons, including iron plates, powder charges, gunpowder, and high-explosive materials. The Large Bomb requires Create mechanical crafting.

All content is collected in the dedicated Create Warnautics creative tab. Its animated Bombed card groups the available bombs, cassette variants, sea bomb, and mines.

# Requirements

    - Minecraft: 1.21.1
    - Loader: NeoForge 21.1.243 or newer for Minecraft 1.21.1
    - Java: 21
    - Required: Create 6.0.10 or newer within the 6.0.x line
    - Required: Create Big Cannons 5.11.x
    - Required by CBC: Ritchie's Projectile Library 2.1.2

This release is for NeoForge only. It is not a Fabric or legacy Forge build.

# Installation

    1. Install NeoForge for Minecraft 1.21.1.
    2. Install Create, Create Big Cannons, and their required dependencies.
    3. Place the Create Warnautics JAR in the mods folder.
    4. For multiplayer, install the same JAR and required dependencies on both the dedicated server and every client.

The mod uses one universal JAR for client and dedicated-server installation.

# Important

Create Warnautics is intentionally destructive. Back up important worlds before testing heavy bombs, cassette bombing systems, chain reactions, or vehicle-scale explosives.

# License

Create Warnautics is distributed under the MIT License.
