package com.cbc_more_content.block;

import javax.annotation.Nullable;

import com.cbc_more_content.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The guidance package on a placed missile: where it is meant to go, and how it was told.
 * <p>
 * Lives on the middle cell only — the nose and tail are structural. Everything here is
 * set before launch and read once, when redstone lets the missile go.
 */
public class CruiseMissileBlockEntity extends BlockEntity {
    @Nullable
    private BlockPos target;
    private Guidance guidance = Guidance.NONE;
    /** Sub-level this missile was slaved to, when the designator locked one. */
    private int lockedSubLevel = -1;

    public CruiseMissileBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CRUISE_MISSILE.get(), pos, state);
    }

    @Nullable
    public BlockPos target() {
        return this.target;
    }

    public Guidance guidance() {
        return this.guidance;
    }

    public int lockedSubLevel() {
        return this.lockedSubLevel;
    }

    /** Aim at a fixed point. Clears any lock, since the two modes are exclusive. */
    public void setTarget(BlockPos target) {
        this.target = target;
        this.guidance = Guidance.COORDINATES;
        this.lockedSubLevel = -1;
        this.sync();
    }

    /** Slave the missile to a moving sub-level rather than a point on the map. */
    public void lockOnto(int subLevelId, Vec3 seenAt) {
        this.lockedSubLevel = subLevelId;
        this.target = BlockPos.containing(seenAt);
        this.guidance = Guidance.LOCK;
        this.sync();
    }

    public void clearTarget() {
        this.target = null;
        this.lockedSubLevel = -1;
        this.guidance = Guidance.NONE;
        this.sync();
    }

    private void sync() {
        this.setChanged();
        if (this.level != null) {
            this.level.sendBlockUpdated(this.worldPosition,
                    this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.target = tag.contains("TargetX")
                ? new BlockPos(tag.getInt("TargetX"), tag.getInt("TargetY"), tag.getInt("TargetZ"))
                : null;
        this.lockedSubLevel = tag.contains("Lock") ? tag.getInt("Lock") : -1;
        this.guidance = Guidance.byId(tag.getInt("Guidance"));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (this.target != null) {
            tag.putInt("TargetX", this.target.getX());
            tag.putInt("TargetY", this.target.getY());
            tag.putInt("TargetZ", this.target.getZ());
        }
        tag.putInt("Lock", this.lockedSubLevel);
        tag.putInt("Guidance", this.guidance.ordinal());
    }

    /** The screen shows the current aim point, so it all travels. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public enum Guidance {
        /** Unguided: flies straight ahead until the fuel runs out, as before. */
        NONE,
        /** Steers toward a fixed point. */
        COORDINATES,
        /** Steers toward a sub-level, following it as it moves. */
        LOCK;

        public static Guidance byId(int id) {
            Guidance[] values = values();
            return id >= 0 && id < values.length ? values[id] : NONE;
        }
    }
}
