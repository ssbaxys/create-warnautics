<img width="2167" height="850" alt="4392f0ae780a685812812fa1df82f1dfaf6f04cd_0" src="https://cdn.modrinth.com/data/cached_images/9bfd59bcf5d1f6950b16ebc0fd2e518b0c441ff2.png" />
<img width="2167" height="780" alt="4392f0ae780a685812812fa1df82f1dfaf6f04cd_0" src="https://cdn.modrinth.com/data/cached_images/9cb9fb784863499a8a4a4ac318a919fd752a6e30.png" />

**Create Warnautics** expands Create Big Cannons with bombs, mines, missiles, and more. 

# Main features
Aerial bombs

 - Small Bomb 
 - Medium Bomb 
 - Large Bomb
 - Sea Torpedo

Bombs are activated with a redstone pulse, at which point they fall and explode upon impact. Wrenches are also able to rotate them.

## Small-bomb bundles

Small bombs can be combined into bundles containing 2, 3, or 4 bombs.

 - Right-click a placed small bomb with more small bombs to assemble a bundle in survival.
- While powered, a bundle releases one bomb at a time. Set the interval to 6–100 game ticks with the settings key; it applies to the connected rack.

## In-game guides

All 25 items have their own Create-style Shift/Ctrl description and an animated Ponder guide. Hover over an item and hold the Ponder key to open its lesson. The guides are available in English and Russian, including instructions for racks, sea mines, chains, the cruise missile and the AIM-9 airframe. AIM-9 is placeable but does not fly yet.

## Cruise missiles

Right-click a placed missile with the settings key to choose its flight plan. **Direct** has the lowest fuel cost, **Arc** climbs before descending onto the target, and **Evasive** changes its heading and throttle during the approach. The illustrated cards show the selected plan; it is saved with the missile and survives assembly into a Sable ship.

Right-click missiles with one target designator to form a group of up to eight. This immediately enables remote guidance, so there is no need to open each missile's settings first. Hold the use key while aiming at a target at least 50 blocks away to acquire it, then press attack to launch the group. Your own ship's walls do not block target acquisition; other ships and terrain do. A missile locked onto a physical hull detonates on contact, including against small flying targets.

## Sea mines and chains

A sea mine floats as a physical body and can be connected using the separate chain coil. The chain connector accepts chains; the original Simulated rope and its connectors remain available. Chains use Simulated's connection and winch physics, and our connector can be waterlogged.

Wet mines rust through states **0–3**, with contact misfire chances of **0%, 15%, 35%, and 65%**. Each transition takes 36,000 wet game ticks (30 minutes at 20 TPS). A dry mine keeps its current corrosion progress. For operator testing, `/cw debug sea_mine stage 0` through `stage 3` changes the targeted mine, and `/cw debug sea_mine status` reports its state.

## Land mines

 - Small Mine — antipersonnel mine that produces shrapnel, intended for use on people.
 - Large Mine — anti-vehicle charge designed for heavier targets and moving structures.

# Compatibility

 - **Sable** — Bombs damage and launch from physics objects. Physical hulls also activate large mines.
 - **Sable Player Ragdoll / Ragdoll Reactions** — Ragdoll when near an explosion.
 - **Veil** — Recommended as it enhanced the explosions effects.

# Requirements

 - Minecraft: 1.21.1
 - Loader: NeoForge 21.1.234
 - Required: Create 6.0.10 or newer
 - Required: Create Big Cannons 5.11.x
 - Required by CBC: Ritchie's Projectile Library 2.1.2
 - Required: Sable 2.0.3 or newer
 - Required: Create Aeronautics with Simulated 1.3.2 or newer; tested with Aeronautics 1.3.2

# Configuration

Server config, writes to `serverconfig/cbc_more_content-server.toml` upon first load.

| Option | Default | Effect |
|--------|---------|--------|
| `detonation.friendlyChainDetonation` | `false` | Let Warnautics blasts ignite other **airborne** Warnautics bombs. Explosive blocks destroyed by a blast always chain-detonate. |
| `detonation.externalChainDetonation` | `true` | Let TNT, shells, fire and lava cook off placed bombs. |
| `performance.maxBlocksPerDetonation` | `2600` | Ceiling on blocks changed by one detonation. The main lever against carpet-bombing stalls. |
| `performance.blastFxScale` | `1.0` | Multiplier on blast particles and flash packets sent to clients. |
| `performance.releaseImpulse` | `1.0` | Multiplier on the release arc. `0.0` gives a pure drop that only inherits carrier velocity. |

# Disclaimer 

AI tools may be used during development to create or refine code, modules, comments, documentation, and changelog entries.
All content is reviewed and approved by the project maintainers before release.

# License

Create Warnautics is distributed under the GPLv3 License.
