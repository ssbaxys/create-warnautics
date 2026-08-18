# Publishing checklist (partial)

Not published yet — use this before Modrinth/CurseForge upload.

## Required before upload

- [x] License set to **MIT** (`gradle.properties` + `LICENSE`)
- [ ] Add real `issueTrackerURL` / `displayURL` in `src/main/templates/META-INF/neoforge.mods.toml`
- [ ] Screenshots: place bomb, redstone drop, crater, distant war rumble note
- [ ] Gallery icon: `src/main/resources/icon.png` (already generated)
- [ ] Confirm Create **6.0.10+** and CBC **5.11.x** on the page
- [ ] Test with and without Sable
- [ ] `./gradlew.bat build` → upload `build/libs/cbc_more_content-*.jar`

## Modrinth snippet

**Summary:** Placeable aerial drop bombs for Create Big Cannons — CBC ballistics, custom blasts, optional Sable ship kick.

**Categories:** Technology, Adventure, Create add-on

**Loaders:** NeoForge  
**Game versions:** 1.21.1
