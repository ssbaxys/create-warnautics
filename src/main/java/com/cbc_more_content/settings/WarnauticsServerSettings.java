package com.cbc_more_content.settings;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-wide switches, saved with the world and the same for everybody on it.
 * <p>
 * Deliberately not a client config. What a cannon's blast looks like is a property of the
 * server everyone is playing on — half a battery seeing a mushroom and the other half
 * seeing a puff would be worse than either. So it lives here, an operator sets it once,
 * and it is pushed to every client that connects.
 * <p>
 * Kept on the overworld rather than per-dimension: a setting called "server-wide" that
 * quietly differed in the nether would not be one.
 */
public class WarnauticsServerSettings extends SavedData {
    private static final String NAME = "cbc_more_content_settings";

    /** Whether Big Cannons blasts are dressed with this mod's effects. */
    private boolean cannonFx = true;

    public static WarnauticsServerSettings get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new Factory<>(WarnauticsServerSettings::new, WarnauticsServerSettings::load), NAME);
    }

    /** Convenience for the many places that only hold a level. */
    public static WarnauticsServerSettings get(Level level) {
        MinecraftServer server = level.getServer();
        return server == null ? new WarnauticsServerSettings() : get(server);
    }

    public boolean cannonFx() {
        return this.cannonFx;
    }

    public void setCannonFx(boolean value) {
        if (this.cannonFx == value) {
            return;
        }
        this.cannonFx = value;
        this.setDirty();
    }

    private static WarnauticsServerSettings load(CompoundTag tag, HolderLookup.Provider registries) {
        WarnauticsServerSettings settings = new WarnauticsServerSettings();
        settings.cannonFx = !tag.contains("CannonFx") || tag.getBoolean("CannonFx");
        return settings;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("CannonFx", this.cannonFx);
        return tag;
    }
}
