package com.cbc_more_content.munitions;

import com.cbc_more_content.block.CruiseMissileBlockEntity.Guidance;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

/** Mutable targeting state carried from a placed missile into its projectile. */
public final class MissileTargetingState {
    private Guidance guidance = Guidance.NONE;

    @Nullable
    private BlockPos target;

    private int lockedSubLevel = -1;

    @Nullable
    private BlockPos controller;

    @Nullable
    private String contact;

    public Guidance guidance() {
        return this.guidance;
    }

    @Nullable
    public BlockPos target() {
        return this.target;
    }

    public int lockedSubLevel() {
        return this.lockedSubLevel;
    }

    @Nullable
    public BlockPos controller() {
        return this.controller;
    }

    @Nullable
    public String contact() {
        return this.contact;
    }

    public void setGuidance(Guidance guidance, @Nullable BlockPos target, int lockedSubLevel) {
        this.guidance = guidance;
        this.target = target;
        this.lockedSubLevel = lockedSubLevel;
    }

    public void setController(@Nullable BlockPos controller) {
        this.controller = controller;
    }

    public void setContact(@Nullable String contact) {
        this.contact = contact;
    }

    public void writeTo(net.minecraft.nbt.CompoundTag tag) {
        tag.putInt("Guidance", this.guidance.ordinal());
        tag.putInt("Lock", this.lockedSubLevel);
        if (this.controller != null) {
            tag.putInt("RadarX", this.controller.getX());
            tag.putInt("RadarY", this.controller.getY());
            tag.putInt("RadarZ", this.controller.getZ());
        }
        if (this.target != null) {
            tag.putInt("TargetX", this.target.getX());
            tag.putInt("TargetY", this.target.getY());
            tag.putInt("TargetZ", this.target.getZ());
        }
    }

    public void readFrom(net.minecraft.nbt.CompoundTag tag) {
        this.guidance = Guidance.byId(tag.getInt("Guidance"));
        this.lockedSubLevel = tag.contains("Lock") ? tag.getInt("Lock") : -1;
        this.controller = tag.contains("RadarX")
                ? new BlockPos(tag.getInt("RadarX"), tag.getInt("RadarY"), tag.getInt("RadarZ"))
                : null;
        this.target = tag.contains("TargetX")
                ? new BlockPos(tag.getInt("TargetX"), tag.getInt("TargetY"), tag.getInt("TargetZ"))
                : null;
        this.contact = null;
    }
}
