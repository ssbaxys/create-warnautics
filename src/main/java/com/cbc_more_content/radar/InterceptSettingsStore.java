package com.cbc_more_content.radar;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Intercept conditions, one set per radar network, saved with the level.
 * <p>
 * Held against the controller's position rather than on the missiles: a missile is
 * consumed the moment it launches, and the whole point of setting this up on the
 * controller is that the next twenty missiles inherit it.
 */
public class InterceptSettingsStore extends SavedData {
    private static final String NAME = "cbc_more_content_intercept";

    private final Map<BlockPos, InterceptSettings> byController = new HashMap<>();

    public static InterceptSettingsStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(InterceptSettingsStore::new, InterceptSettingsStore::load), NAME);
    }

    public InterceptSettings forController(BlockPos controller) {
        return this.byController.getOrDefault(controller.immutable(), InterceptSettings.DEFAULT);
    }

    public void set(BlockPos controller, InterceptSettings settings) {
        this.byController.put(controller.immutable(), settings);
        this.setDirty();
    }

    private static InterceptSettingsStore load(CompoundTag tag, HolderLookup.Provider registries) {
        InterceptSettingsStore store = new InterceptSettingsStore();
        ListTag entries = tag.getList("Controllers", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            NbtUtils.readBlockPos(entry, "Pos").ifPresent(pos ->
                    store.byController.put(pos, InterceptSettings.load(entry.getCompound("Settings"))));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        this.byController.forEach((pos, settings) -> {
            CompoundTag entry = new CompoundTag();
            entry.put("Pos", NbtUtils.writeBlockPos(pos));
            entry.put("Settings", settings.save());
            entries.add(entry);
        });
        tag.put("Controllers", entries);
        return tag;
    }
}
