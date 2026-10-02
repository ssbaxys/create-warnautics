# Changelog

## 1.1

Cruise missiles now occasionally aim wide: roughly twelve percent of launches have a larger, bounded error, while ordinary shots retain their small dispersion. The error stays with the projectile across all three flight plans and save/reload; the selected target itself is not changed.

AIM-9 can miss an approach, especially against a crossing or sharply manoeuvring target. It flies past, makes a wider return arc and tries the same contact again with its remaining fuel. Actual contact still detonates the interceptor even on an inaccurate approach. Reloading preserves its current pass and fuel budget.

Marine blasts have procedural Veil waves, foam, falling spray and underwater bubble fronts. The effect checks the surrounding water and the route to its surface: dry ground produces no spray, and a solid roof keeps the surface burst out. Multiple water blasts share a bounded render budget and fade away normally.

Placing an airframe across water and air no longer copies the first cell's water state into every segment. Each part keeps only the source water already at its own position; flowing water does not become a bucketable source. AIM-9 also checks the target's line of sight and its initial rising path before leaving the rack, including on rotated Sable ships. A blocked interceptor waits for clearance.

Fixed Veil's Fabulous weather-buffer pairing without replacing the library. Its clear handler was attached to the setup call; Warnautics repairs that specific broken handler and closes the buffer at the end of the pass. The compatibility patch checks the installed handler, so a corrected Veil build is left alone.

Repeated resource reloads and language changes no longer leave Ponder using a retired bitmap font. Font lookup and font replacement now share the same short synchronization boundary, preserving Minecraft's existing font cache. The regression check forces a background lookup during retirement, reloads with the guide both open and closed, and renders all 25 lessons in Russian and English.

Small and medium bombs have their animated Create Big Cannons smoke back, together with the softer smoke body. C4 and the vest produce a wider ground-level bloom; the sea mine leaves a denser cloud and a water burst. Busy chain reactions retain both smoke layers for each charge, with bounded particle counts instead of miniature clouds. Blast damage has not changed.

AIM-9 searches and intercepts from moving Sable ships again. Gluing any one cell of an AIM-9, MOAB or cruise missile to the hull now collects the entire three-cell airframe, whether assembly starts from the hull or the weapon.

A blast near flying munitions applies pressure rather than instantly igniting every round in its radius. Missiles recover their course over several ticks; strong close pressure can set off an exposed armed charge after a short delay, while cover attenuates the impulse. Placed explosives keep their existing chain-reaction rules.

Cruise missiles accelerate smoothly and turn more readily, but remain slower than AIM-9. All three flight plans add a small variation around the selected impact point. The variation is tighter against small physical targets so it does not undermine direct hits.

AIM-9 is now a working interceptor. The settings key opens its own panel with an enable switch, a cruise-missile filter and a 40–220 block range slider. It clears the rack on a smooth upward ejection, then lights its motor with a pop and accelerates into a lead pursuit. Launchers keep a round back when another interceptor already owns the contact. Moving Sable racks preserve the launch frame, and flight chunks are prepared ahead of the interceptor.

Grouped cruise missiles take separate approach lanes and avoid nearby rounds. Missile collisions now use the slender moving airframes instead of overlapping tracking boxes, so flying beside another missile no longer counts as hitting it. Old chunk tickets are released as the flight moves on.

Small, medium and large bomb flashes have twice the previous radius. Small and medium charges leave larger overlapping soft smoke billows. Smoke delivery follows rendered chunks rather than the sound radius, and chain reactions retain a cloud for each charge while reducing small details under heavy load. Separate charges keep separate flash origins; only nearly coincident explosions merge.

Small flying targets exposed a problem with the cruise missile's fuze: it could go off before touching the hull, or simply because it had started flying away after a miss. A missile locked onto a physical ship now waits for contact. Coordinate and radar targets keep a proximity fuze, but it checks the actual flight segment and only fires close to the target. Warhead power is unchanged.

The settings key now offers three flight plans, with animated drawings: direct, lofted and evasive. They have different throttle behaviour and fuel costs. The evasive plan weaves during the approach and straightens for the final attack. Right-clicking a missile with the designator enables remote guidance immediately; one designator can hold a group of up to eight missiles, including after their racks are assembled into a Sable ship.

Missile exhaust follows the client's view distance, with cheaper rendering for small distant sources and more detail when magnified. The flame uses the current camera projection and fog instead of render state left behind by another pass. It is drawn before clouds so a rocket behind them no longer shines over their composited colour. Veil flashes and concussion now remain enabled with Sodium Extra; the old compatibility fallback was replacing the concussion with a pale overlay and hiding the flash.

Ponder scenes have more distinct settings and show the controls working while the explanation is on screen. Falling charges accelerate, missiles turn towards the target, exhaust follows the nozzle, and impacts throw the mod's own fractured debris. Every item keeps its Create-style description; redundant old static tooltip text has been removed. Stored charge locations and other live status information are still shown.

Disturbed soil reaches farther beyond the crater and also covers smaller explosive rounds, C4, mines and the vest. Freshly exposed crater ground is processed after destruction. A floating sea mine also clears copied water from its plot after assembly, including when the copy finishes later than its placement callback. The music disc's loot modifiers now use the directory expected by NeoForge.

This update is largely about making a battle behave the same way on a moving ship as it does on ordinary terrain. Bombs now cut into physical hulls, blasts leave rounder craters and disturbed ground, and a ship can be pushed as well as broken. The old balance and the broad footprint of the MOAB remain; the biggest change is where the damage actually lands.

The sea mine is back. It floats as a physical body, damages ships on contact, and rusts through four states (0–3) while wet. Rust makes a contact misfire more likely; the dull knock means this impact failed, not that the mine is harmless. A separate chain coil and chain connector can be used with Simulated connections and winches, without replacing its ordinary rope.

Bomb racks are easier to work with now. Small-bomb bundles of two, three and four release their charges separately, and an explosion destroying a stored bundle can set off the individual bombs in sequence. The settings key gives the whole connected rack an adjustable release interval. The MOAB and the new AIM-9 airframe both fit into three-block spaces more reliably. AIM-9 now supports automatic interception through its own settings panel.

The sea torpedo keeps moving when it leaves water: it follows a falling arc, and resumes its run if it lands back in water. The cruise missile can launch from a physical ship, its blast has the character of a smaller MOAB, and the target designator now acquires moving ships from at least 50 blocks away. It ignores your own hull while aiming, but other obstacles still matter.

Siren drive, sound and lighting received another pass, including on moving ships. Missile exhaust has a fuller, more continuous smoke trail and a flame that spreads at its tip. Explosion flashes and debris are smoother when several blasts happen close together, with less work spent on effects the player cannot see.

Every one of the mod's 25 items now has its own Create-style Shift/Ctrl description and a six-step Ponder lesson, in English and Russian. Hold the Ponder key while hovering an item to see its guide. Lessons show the actual controls and limitations, from mine burial and chain endpoints to remote charges and missile guidance.

Compatibility: Minecraft 1.21.1 / NeoForge 21.1.234, Create 6.0.10, Create Big Cannons 5.11.x, Sable 2.0.3+, and Create Aeronautics 1.3.2. Veil remains optional for the extra effects.

## 1.0.7

Create Warnautics 1.0.7: the C4 breaching charge, wire cutters and a defusal minigame, a guided cruise missile, and mines that leave the ground looking like something happened to it.

NOTE: The small, medium and large bomb recipes are all roughly twice as expensive as in 1.0.3, and the medium and large now want an impact fuze. Existing bombs are unaffected.

### Added

* **C4 charge** — thrown, sticks where it lands, and set with the settings key
   * A keypad code is chosen when planting; the same code is the only way to stop the fuse
   * The code is stored and compared on the server alone and never travels to a client
   * Screen and detonator lamp blink with the countdown, and the timer cog turns while it runs
   * Breaking a live charge sets it off partway through; mining the block out from under one drops it, still ticking, onto whatever is below
* **Detonator** — a charge can be set to answer a radio set instead of its own clock
   * Planting runs code, then trigger, then timer. A charge set to answer a detonator has no fuse to set, so it arms on the trigger page and the timer page never opens
   * Pairing is physical: touch the armed charge with the set. Firing reaches 250 blocks, and past that the plunger sends nothing
   * One set holds up to twelve charges, listed with their coordinates on the tooltip, and the plunger fires the whole ring at once
   * Sneak + right-click a charge to take it off the set; sneak + right-click in the open drops all of them
   * A charge set to remote wears an aerial. Armed, it shows the lamp alone with a dark screen and a still cog — there is no clock behind it to turn. Paired, the screen lights and the cog turns: there is a set at the other end now
   * The set shows its own lamp lit while it holds anything, and lets charges go on its own once they are gone
* **Big Cannons blasts get this mod's effects** — the flash, the late roll from a long way off, the concussion, the thrown debris and the scarred ground, on shells and mortar rounds and flak bursts rather than only on the mod's own bombs
   * The profile is picked from the blast's own radius, so a shell is dressed to the size it actually is and add-on munitions built on the same explosion types are covered without knowing about them
   * Shrapnel bursts are left alone: a cloud of fragments is not a detonation, and a mushroom on each one would bury the map
* **`/cw panel`** — an operator-only switchboard with one large lever
   * The switch is server-wide and saved with the world. What a blast looks like belongs to the server everyone is on, not to each client, so one person sets it and every player gets the change
   * The permission is checked again when the switch is thrown, not only when the command opens the screen
* **Air-raid siren** — a driven post that wails on a redstone signal, or on its own
   * A Create machine: it takes a shaft from below, shows the shaft turning in its socket, and costs the network 1024 stress. A siren is a rotor in a housing, so drive is what makes a note at all — with the lever thrown and no shaft it is armed and silent, and it finds its voice the moment the shaft turns
   * The faster the rotor, the louder it is, in straight proportion: full voice at 64 RPM, half at 32, nothing at rest, and no more to give past 64. The pitch sags as it winds down, so spinning one up and letting it die reads as a rotor rather than a switch
   * The settings key opens it: sound on its own or not, watched radius, how long it keeps wailing after the threat has passed, and whether it is listening for inbound missiles, falling bombs, or both
   * A missile merely crossing the sky does not set it off; one turned this way does
   * Nothing announces the all-clear. The wail simply runs out, so standing under it you find out the same way everyone else does
   * Two layers, held open as looping voices and crossfaded by distance: close by it is the wail with the swell under it, and by a hundred and twenty blocks only the swell is left. Walking away fades between them across sixty blocks rather than switching
   * The lamp is the control circuit and lights on a signal alone, drive or no drive. A post standing lit and silent is one armed and waiting for a shaft
   * Breaking a post stops it. The wail is not a fired sample that has to finish
   * A signal holds the post and lets it go. Only a sighting starts the linger, so cutting the line stops the wail within a tick rather than leaving it running out a timer nobody asked for
   * Walking away from a raid no longer silences it. The voices used to live on a block lookup alone, so they died at the listener's own render distance — a post two hundred blocks off went quiet even though the far layer is mixed to carry three hundred and thirty. The post now says how long it has left and the client runs the rest out itself
* **Wire cutters** — open the panel on a live charge for three coloured wires
   * One defuses, one detonates, one halves the remaining time; which is which is rolled per charge and never sent to the client
   * Cuts persist, so closing the panel does not hand back a fresh board
   * The cutters hinge shut as the arm swings, in first and third person alike
* **Cruise missile guidance**
   * A flight plan typed on the settings key, or a hull painted with the new target designator and tracked as it moves
   * Steering is rate limited: terrain along the route is cleared, but anything that appears in front of the nose cannot be dodged
   * The airframe now places vertically as well as horizontally
* **Target designator** — pair with a missile, then hold attack on a hull within arm's reach to lock it
* **Breaker of Skies** plays quietly where it lies and whistles as it falls, and turns up in dungeon loot
* Public `WarnauticsBlockDetonateEvent` and `WarnauticsBlockChipEvent` for add-ons to veto individual blocks

### Changed

* Antipersonnel mine fragments leave in a flat fan at shin height rather than an upward cone, with their own particle
* Both mines scar the ground around them: stone to cobble, turf to dirt, brick to cracked
* Blasts near a Sable sub-level go through the normal explosion again, which Sable already extends to hulls, instead of a bounded stand-in that skipped it
* Bomb recipes roughly doubled in cost across all three sizes
* The cruise missile now costs more than the large bomb rather than half of it: same explosive filler and powder charges, on top of the guidance, the airframe and the engine. The pattern is 9x5 instead of 9x3, so an existing crafter array needs extending
* Heavy warheads bite into hard material instead of scuffing it. Explosion resistance is charged linearly by the ordinary model — a scale built so that TNT can never touch obsidian, with the side effect that nothing else can either, so a missile sized to breach a bunker took the dirt around it and left the bunker unmarked. A second pass over what the first refused charges a sub-linear curve, so soft ground is unchanged and payload size decides how far into hard material a blast reaches: about six blocks of obsidian for the cruise missile, a block or so for a large bomb, none at all for a small one. It walks as rays, so a slab of obsidian still shields what is behind it, and it goes through the same protection gate as everything else
* The cruise missile flies faster and burns longer: 1.4 blocks a tick over nine and a half seconds, so about 265 blocks of powered range instead of 130. Its lookahead grew with it, since avoidance is a nudge held against a turn circle that is now twenty-five blocks wide, and the proximity ring was widened past one tick of travel so a faster run-in cannot step over it
* Antipersonnel mines are survivable. They were sharing the shell curve the bombs use, which came out at eighty-odd damage at the seat — four times over what it takes to kill anyone, at any range inside the burst. On their own curve now: standing on one leaves about two hearts before fragments, a couple of blocks away leaves five or six, and cover cuts it again. The antivehicle mine is unchanged

### Fixed

* Fixed a crash the moment a rack released a bomb or torpedo on a redstone signal ([#2](https://github.com/ssbaxys/create-warnautics/issues/2)). The launch seeded the projectile's previous-tick position by writing `xOld` directly — a field the development workspace exposes and a shipped game keeps private, so it compiled cleanly here and threw `IllegalAccessError` for everybody else. It goes through the vanilla method now
* NeoForge 21.1.234 and up, instead of only the exact build the release happened to be compiled on. The declared range was pinned to the compiler's own version, which locked out anyone on an older 21.1; the mod is now built against 21.1.234 so the floor is one the code is actually known to run on
* Fixed blast particles vanishing 32 blocks away — `sendParticles` without a player hardcodes short range, so effects now carry to view distance
* Fixed a guided missile always flying straight: the airframe was cleared before its flight plan was read, so the plan was always gone by launch
* Fixed a cruise missile flying straight through a Sable physics hull without going off — a hull's blocks are not where the hull appears to be, so the ordinary sweep along the flight path found nothing there
* Fixed an intercept round never leaving the rack. It launches itself on the first contact its network paints, because the thing it exists to stop is already inbound by the time anyone could throw a lever; redstone still fires it early
* The intercept mode is hidden entirely without Create Radar, instead of offering a mode that armed the missile against a picture nobody was painting
* Fixed the target designator mining the block it was aimed at instead of painting it
* Fixed concussion being cancelled by the death it was caused by; it now plays over the death screen and clears on respawn
* Fixed the C4 fuse resetting to 15 seconds when its screen was opened and closed
* Fixed missing-texture particles when walking on a cruise missile
* Fixed the C4 and keypad panels drawing over furniture belonging to the timer sheet

## 1.0.3

Create Warnautics 1.0.3, a compatibility fix for the animated creative-tab banner.

### Fixed

* [GeckoLib] Fixed the animated creative-tab banner rendering incorrectly
   * The strip lived in `textures/gui/sprites/`, which is stitched into the shared GUI atlas, and its animation mcmeta told the stitcher it was a twelve-frame sprite — so the file was live as both an atlas sprite and a directly blitted texture, and which one won depended on texture init order
   * Fixed the highlight clip being applied with a raw GL scissor around batched GuiGraphics draws, so it was lifted before the text it was meant to clip was ever submitted
   * Fixed depth state being left changed for everything drawn after the card

## 1.0.2

Create Warnautics 1.0.2: a proper settings screen for bomb racks, the sea bomb becomes the Sea Torpedo, and submerged blasts finally damage ordinary underwater structures instead of only Sable hulls!

NOTE: Blast damage was badly under-applied on servers running Sable, and cover is now scored by material and thickness. Bombs hit far harder than in 1.0.1, and anyone relying on the old numbers should re-check their builds.

### Added

* Added a concussion effect for surviving a near miss
   * A white flash snaps in and decays, a drifting haze stands in for lost focus, and white-noise speckle thins out as it passes
   * Drawn above the HUD with plain GUI calls, so it behaves the same on vanilla, Sodium and Iris
   * The white-out runs for a fixed 2.3 seconds, while the haze decays across the full length of the ringing sound, so vision clears just as the audio ends
   * Strength and length both scale with how close the blast was and whether cover was in the way; overlapping blasts refresh the effect instead of stacking to solid white
   * `cbc_more_content:bomb_concussion` is played as a UI sound, so the ringing sits in the affected player's head and bystanders never hear it
* Added dedicated land mine death messages, separate from the aerial bombing ones
   * Antipersonnel and antivehicle mines each have their own set, so the message matches what actually killed you
* Added the Settings Key, crafted from an iron plate, a brass nugget and redstone
   * Right-click any placed bomb with it to open a release-interval dial
   * The dial is turned by dragging or scrolling, snaps to the six presets with a detent click, and shows the interval in both ticks and seconds
   * The interval always covers the whole contiguous rack, so a deep bomb bay is configured in one click, and it stays with those bombs until they are released, broken or destroyed
   * Styled after Simulated's instrument screens, drawn from this mod's own atlas and palette

* Added blast cover evaluation
   * Twenty-seven sample rays per target charge the explosion resistance of everything they pass through, so a pane of glass and a metre of obsidian no longer protect equally
   * Cover the same blast is about to destroy is excluded: the wall that fails absorbs its share, breaks, and the rest carries through
   * A single block passes 97.6% through glass, 66.7% through stone and 1.0% through obsidian; a four-block stone wall passes 33.3%

### Changed

* Renamed the Sea Bomb to the Sea Torpedo in English, Russian, German, Spanish, French, Japanese and Chinese (Simplified)
   * The registry id stays `cbc_more_content:sea_bomb`, so existing worlds, recipes, contraptions and schematics are unaffected
* Replaced sneak-and-click interval cycling with the Settings Key
   * Cycling gave no indication of the current value and made you step through all six presets to reach the one you wanted
   * Sneak-and-click on a bomb now does nothing at all; the key is the only way in
* Removed the release interval from bomb tooltips
   * The interval belongs to a rack standing in the world, not to an item in an inventory
* Changed the settings dial to drop its "Whole rack" toggle; a rack only makes sense with one shared interval
* Changed the torpedo wake to read as parted water rather than a trail of bubbles left behind
   * A bow wave seeded ahead of the nose and pushed outward into a widening V, a bubble sheet down each flank, a screw cavitation helix and a collapse zone where the water folds back in
   * Every emitter carries outward velocity, which is what makes it read as displacement
   * On the surface, foam is thrown out to either side of the track
* Changed the sub-level break budget to scale with payload instead of sitting at Sable Destructive's flat per-explosion figure
* Changed the world break ceiling to follow `maxBlocksPerDetonation` rather than a hardcoded 1024
* Changed the fracture model to charge resistance sub-linearly, so payload size decides what a bomb can breach

### Fixed

* Fixed bombs dealing no damage to a player standing next to them
   * Entity damage was left to the loop inside `Explosion#explode`, which selects and range-gates entities on the explosion's *block* radius and then routes the amount through CBC's damage calculator
   * Warnautics now applies blast damage and knockback itself, so the world path and the Sable path use the same numbers
* Fixed the detonation flash only being visible from above
   * Blasts sit slightly inside the surface they struck, so the single line-of-sight ray aimed at the blast point landed in that block from nearly every angle
   * Several points around the blast are sampled now, and anything close enough to be inside the fireball skips the occlusion test entirely
* Fixed underwater detonations doing no block damage to ordinary world structures
   * Water has an explosion resistance of 100, and an explosion's ray pass spends all of its energy inside the first block of water, so nothing was ever queued for destruction
   * Sable hulls appeared to work only because the sub-level path walks its sphere directly and never casts a ray through water
   * Submerged blasts now charge energy against each block's own resistance instead of against the water in between, matching how sub-level damage was already calculated
   * Applies to every munition, so a large bomb dropped into a lake now craters the lakebed
* Fixed the new underwater damage pass being able to bypass claim protection
   * Blocks added after the explosion posted `ExplosionEvent.Detonate` are now run past the event separately, sharing the filter introduced for the Sable path in 1.0.1

* Fixed underwater blasts punching permanent air pockets into the sea
   * Blocks are cleared without neighbour updates, deliberately, so no fluid tick was ever scheduled to close the hole; kelp made it obvious because every kelp block carries water
   * Submerged positions are now filled with water directly, which is what the fluid tick would have produced
* Fixed heavy payloads barely marking hardened blocks
   * Vanilla charges resistance linearly, so obsidian costs two hundred times stone and a large bomb behaved exactly like TNT against it
   * A large bomb now breaches obsidian at close range; medium and smaller still cannot at any distance
* Fixed large craters collapsing to a fraction of their radius, from a flat block ceiling that kept only the innermost tenth of the sphere
* Fixed concussion never firing at point-blank range, the one case that should hit hardest
   * The survivor check ran after damage was applied, so a lethal blast killed the player before the cue was sent
   * Bombs bury their detonation point inside whatever they struck, so the cover rays ran through the ground the charge was sitting in and reported near-total shielding; close range now bypasses cover entirely
* Fixed concussion not extending during a bombing run
   * Any blast weaker than 75% of the one in progress was discarded outright, so the first and closest detonation set the bar and the effect expired while bombs were still landing
   * A later blast now tops the level up and pushes recovery out, and can only extend the effect, never cut it short
* Fixed concussion permanently ceasing to appear after leaving and rejoining a world, from state left stranded in client statics
* Fixed the ringing stacking copies of itself, since the previous sound handle was dropped without being stopped
* Fixed sound being muffled almost to silence behind cover; a wall now passes 70% of the ringing, because sound reaches around cover even when the blast does not

## 1.0.1

Create Warnautics 1.0.1, focused on making heavy bombers actually buildable, with more fixes and more support, particularly for Sable, Sodium/Iris and claim protection mods!

NOTE: This update rebalances every explosive recipe against the Create Big Cannons material economy. Existing autocrafters and schematics for bombs and mines will need to be rebuilt!

NOTE: The large bomb uses a new model and a single atlas texture. Resource packs that retextured `large_bomb_side`, `large_bomb_top`, `large_bomb_bottom` or `large_bomb_fin` will no longer apply.

### Added

* Added a server config (`serverconfig/cbc_more_content-server.toml`) covering detonation chaining, blast performance limits and release impulse
* Added a renderer-agnostic detonation flash that draws on every client
   * Veil clients keep their point lights and bloom pass; the overlay tops them up instead of replacing them
   * Clients with no Veil, and clients running Sodium, Embeddium, Iris or Oculus, now get the flash for the first time
* Added an emissive fireball core built from flame, small flame, lava and soul fire flame particles
   * Shader packs light these as real emitters, unlike the dust particles used before
* Added whole-rack release interval editing
   * Sneak + right-click now applies the selected interval to every bomb in the contiguous rack and reports how many were set
* Added `detonation.friendlyChainDetonation` to restore pre-1.0.1 chaining for packs that want it (disabled by default)
* Added `detonation.externalChainDetonation` to control cook-off from TNT, shells, fire and lava (enabled by default)
* Added `performance.maxBlocksPerDetonation` to cap how many blocks one detonation may change
* Added `performance.blastFxScale` to scale blast particles and flash packets sent to clients
* Added `performance.releaseImpulse` to scale the release arc, including `0.0` for a pure drop
* [1.21.1] Added `message.cbc_more_content.release_delay_rack` to English and Russian translations

### Changed

* Changed the large bomb model and texture, courtesy of creat560
   * Four 16x16 textures replaced by a single 64x64 atlas, cutout render type, and 45-degree X-shaped fins
* Changed small bomb recipe to require a powder charge and four iron plates
* Changed sea bomb recipe to mechanical crafting, now using two high explosive materials
* Changed medium bomb recipe to mechanical crafting, now using three high explosive materials and a powder charge
* Changed large bomb recipe to use five high explosive materials, two powder charges, eight iron plates and a cast iron nose
* Changed small mine recipe to yield one instead of two, now using shot balls and gunpowder
* Changed large mine recipe to use two high explosive materials and cast iron
* Changed the release impulse to follow the bomb's nose exactly plus a small downward bias
   * A side rack now throws the bomb out flat and it steepens into a dive, a nose-up rack lobs it, a nose-down rack drops it immediately
   * The separate arc and lift impulses, which added height regardless of facing, have been removed
* Changed bomb release to pick the nearest free side when the nose side is walled in, instead of spawning inside the carrier

### Fixed

* Fixed bombs cooking off other bombs, which made multi-bomb aircraft unusable
   * The payload guard covered only the crater pass and stopped before `finalizeExplosion`, which is what actually dispatches entity damage and `wasExploded`
   * Both flying bombs and bombs still on the rack were therefore free to chain
* Fixed side-mounted bombs being flung upward into the aircraft that released them
   * The spawn point was offset strictly along the nose, placing the bomb inside the hull, and collision resolution then pushed it out upward
* Fixed placed bombs scheduling cook-off unconditionally when caught in any explosion
* Fixed bombs digging through protected land
   * The Sable explosion path replaces the vanilla explosion outright and never posted `ExplosionEvent.Detonate`, so claim protection never saw the blocks
   * Candidate blocks are now filtered through the event before anything is broken
* Fixed unbounded craters flooding clients with block updates, the most likely cause of stalls and out-of-memory crashes during carpet bombing
* Fixed the detonation flash never rendering without Veil
   * The flash payload arrived and was tracked client-side, but nothing consumed it
* [Sable] Fixed hull and entity damage on sub-levels re-entering bomb cook-off
* [Sodium] [Iris] Fixed detonations producing no flash and no emissive core

### Removed

* Removed the unused `launchArc` and `launchUp` bomb tier fields
