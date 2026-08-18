# Create Warnautics

NeoForge **1.21.1** add-on for [Create Big Cannons](https://modrinth.com/mod/create-big-cannons) that adds placeable **aerial drop bombs** with CBC projectile ballistics, custom blast FX/sounds, and optional [Sable](https://modrinth.com/mod/sable) sub-level kick.

## Requirements

| Mod | Version |
|-----|---------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| [Create](https://modrinth.com/mod/create) | 6.0.10+ (required) |
| [Create Big Cannons](https://modrinth.com/mod/create-big-cannons) | 5.11.x (required) |
| Ritchie's Projectile Library | bundled / required by CBC |
| Sable | optional (ship inertia on drop) |

## Content

- Bomb racks release the lowest contiguous bomb first and continue upward while powered, so a single redstone line can empty a bay dozens of bombs deep.
- **Right-click a placed bomb with the Settings Key** to open its release dial. Drag or scroll the knob to pick a `4/8/12/20/26/40` tick interval — it always applies to the entire contiguous rack, and stays with those bombs until they are released, broken or destroyed.
- Released bombs leave the rack along their nose, plus a small downward bias, added on top of the carrier's velocity. A **side-mounted** rack throws the bomb out flat and it steepens into a dive; a **nose-up** rack lobs it up first; a **nose-down** rack drops it straight away. Bombs eject through the nearest free side when the nose side is walled in, so they never spawn inside the airframe.
- Detonation flash renders on **every** client. Veil clients keep their real point lights and bloom pass; a screen overlay draws on top of it and carries the whole flash on clients without Veil, including Sodium and Iris. The fireball core additionally uses flame and lava particles, which shader packs light as real emitters.
- Warnautics bomb blasts never trigger payload still mounted on the aircraft, in flight or on the rack. External blasts, fire, lava, and projectile damage still cause cook-off. Both behaviours are configurable.

## Configuration

Server config: `serverconfig/cbc_more_content-server.toml`.

| Option | Default | Effect |
|--------|---------|--------|
| `detonation.friendlyChainDetonation` | `false` | Let Warnautics blasts cook off other Warnautics bombs (pre-1.0.2 behaviour). |
| `detonation.externalChainDetonation` | `true` | Let TNT, shells, fire and lava cook off placed bombs. |
| `performance.maxBlocksPerDetonation` | `2600` | Ceiling on blocks changed by one detonation. Every changed block costs the client a section re-mesh, so this is the main lever against carpet-bombing stalls. |
| `performance.blastFxScale` | `1.0` | Multiplier on blast particles and flash packets sent to clients. |
| `performance.releaseImpulse` | `1.0` | Multiplier on the release arc. `0.0` gives a pure drop that only inherits carrier velocity. |

- **Small / Medium / Large Bomb** — placeable blocks; nose faces the clicked side; redstone rising edge launches them.
- Detonation uses CBC shell crater logic, custom aviation sounds, distant war rumble, and strong near-field camera shake.
- Most destroyed blocks are vaporized (no item spam); a minority still drop.

## Crafting

Recipes follow CBC material economy (`high_explosive_materials` = packed guncotton, iron plates, powder charges, Create mechanical crafting for the large bomb).

| Bomb | Recipe type | Notes |
|------|-------------|--------|
| Small | Crafting table | Iron plates + packed guncotton + gunpowder + wooden slab |
| Medium | Crafting table | More HE + powder charge + plates + slabs |
| Large | Mechanical crafter | Heavier HE load than an HE shell |

## Build

```bash
./gradlew.bat build
```

Output JAR: `build/libs/Create_Warnautics-<version>+mc.1.21.1.jar`

## License

[MIT](LICENSE)

## Credits

Depends on Create, Create Big Cannons, and Ritchie's Projectile Library. Optional Sable compatibility for contraption / ship drops.
