package com.cbc_more_content.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

public class WarnauticsBlockChipEvent extends Event implements ICancellableEvent {
    private final ServerLevel level;
    private final BlockPos pos;
    private final Vec3 origin;

    public WarnauticsBlockChipEvent(ServerLevel level, BlockPos pos, Vec3 origin) {
        this.level = level;
        this.pos = pos;
        this.origin = origin;
    }

    public ServerLevel getLevel() {
        return this.level;
    }

    /** The block about to be chipped. */
    public BlockPos getPos() {
        return this.pos;
    }

    /** Position of the mine that spawned the fragment. */
    public Vec3 getOrigin() {
        return this.origin;
    }
}
