<img width="2167" height="850" alt="4392f0ae780a685812812fa1df82f1dfaf6f04cd_0" src="https://cdn.modrinth.com/data/cached_images/9bfd59bcf5d1f6950b16ebc0fd2e518b0c441ff2.png" />
<img width="2167" height="780" alt="4392f0ae780a685812812fa1df82f1dfaf6f04cd_0" src="https://cdn.modrinth.com/data/cached_images/9cb9fb784863499a8a4a4ac318a919fd752a6e30.png" />

**Create Warnautics** expands Create Big Cannons with bombs, mines, missiles, and more. 

# Main features
Aerial bombs

 - Small Bomb 
 - Medium Bomb 
 - Large Bomb
 - Sea Bomb

Bombs are activated with a redstone pulse, at which point they fall and explode upon impact. Wrenches are also able to rotate them.

## Small-bomb bundles

Small bombs can be combined into bundles containing 2, 3, or 4 bombs.

 - Right-click a placed small bomb with more small bombs to assemble a bundle in survival.
 - While powered, a bundle releases one bomb every 1.3 seconds.

## Land mines

 - Small Mine — antipersonnel mine that produces shrapnel, intended for use on people.
 - Large Mine — anti-vehicle charge designed for heavier targets and moving structures.

# Optional Compatibility  

 - **Sable** - Bombs damage, launch from, and such on physics objects. Physics objects also activate large mines. 
 - **Sable Player Ragdoll / Ragdoll Reactions** — blast reactions.
 - **Veil** — enhanced physical blast lights and post-processing.

# Requirements

 - Minecraft: 1.21.1
 - Loader: NeoForge 21.1.243
 - Required: Create 6.0.10 or newer
 - Required: Create Big Cannons 5.11.x
 - Required by CBC: Ritchie's Projectile Library 2.1.2

# Configuration

Server config, writes to `serverconfig/cbc_more_content-server.toml` upon first load.

| Option | Default | Effect |
|--------|---------|--------|
| `detonation.friendlyChainDetonation` | `false` | Let Warnautics blasts cook off other Warnautics bombs. Off means tightly packed bomb bays are safe. |
| `detonation.externalChainDetonation` | `true` | Let TNT, shells, fire and lava cook off placed bombs. |
| `performance.maxBlocksPerDetonation` | `2600` | Ceiling on blocks changed by one detonation. The main lever against carpet-bombing stalls. |
| `performance.blastFxScale` | `1.0` | Multiplier on blast particles and flash packets sent to clients. |
| `performance.releaseImpulse` | `1.0` | Multiplier on the release arc. `0.0` gives a pure drop that only inherits carrier velocity. |

# Disclaimer 

AI tools may be used during development to create or refine code, modules, comments, documentation, and changelog entries.
All content is reviewed and approved by the project maintainers before release.

# License

Create Warnautics is distributed under the MIT License.
