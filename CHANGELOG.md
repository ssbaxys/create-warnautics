# Changelog

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
