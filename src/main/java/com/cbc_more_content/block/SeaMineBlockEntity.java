package com.cbc_more_content.block;

import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.mine.MineType;
import com.cbc_more_content.registry.ModBlockEntities;
import dev.ryanhcode.sable.api.physics.object.rope.RopeHandle;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public class SeaMineBlockEntity extends BlockEntity {
    private static final int OXIDATION_TICKS_PER_STAGE = 30 * 60;
    private static final double ROPE_RADIUS = 0.035D;
    private int ageInWater;
    private int oxidation;
    private boolean triggered;

    @Nullable
    private BlockPos anchor;

    private double ropeLength;

    @Nullable
    private transient RopePhysicsObject rope;

    public SeaMineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SEA_MINE.get(), pos, state);
    }

    public int oxidation() {
        return this.oxidation;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, SeaMineBlockEntity mine) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        if (mine.triggered) {
            mine.detonate(server);
            return;
        }
        if (level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) {
            mine.ageInWater++;
            int stage = Math.min(3, mine.ageInWater / OXIDATION_TICKS_PER_STAGE);
            if (stage != mine.oxidation) {
                mine.oxidation = stage;
                mine.setChanged();
            }
        }

        AABB bounds = new AABB(pos).inflate(MineType.SEA_TRIGGER_REACH);
        for (Player player : server.getEntitiesOfClass(Player.class, bounds, LivingEntity::isAlive)) {
            if (!player.isSpectator() && !player.getAbilities().flying) {
                mine.triggered = true;
                mine.setChanged();
                break;
            }
        }
        mine.updateRope(server);
    }

    public void trigger() {
        this.triggered = true;
        this.setChanged();
    }

    public void anchor(ServerLevel level, BlockPos anchor) {
        this.unanchor(level);
        this.anchor = anchor.immutable();
        this.ropeLength = Vec3.atCenterOf(this.worldPosition).distanceTo(Vec3.atCenterOf(anchor));
        ServerSubLevel host = com.cbc_more_content.compat.SableDropCompat.containingSubLevel(level, this.worldPosition);
        Vec3 start = Vec3.atCenterOf(anchor);
        Vec3 end = Vec3.atCenterOf(this.worldPosition);
        this.rope = new RopePhysicsObject(
                List.of(
                        new Vector3d(start.x, start.y, start.z),
                        new Vector3d((start.x + end.x) * 0.5D, (start.y + end.y) * 0.5D, (start.z + end.z) * 0.5D),
                        new Vector3d(end.x, end.y, end.z)),
                ROPE_RADIUS);
        this.rope.setAttachment(RopeHandle.AttachmentPoint.START, new Vector3d(start.x, start.y, start.z), null);
        this.rope.setAttachment(RopeHandle.AttachmentPoint.END, new Vector3d(end.x, end.y, end.z), host);
        SubLevelPhysicsSystem.require(level).addObject(this.rope);
        this.setChanged();
    }

    public void unanchor(ServerLevel level) {
        if (this.rope != null && this.rope.isActive()) {
            SubLevelPhysicsSystem.require(level).removeObject(this.rope);
        }
        this.rope = null;
        this.anchor = null;
        this.ropeLength = 0.0D;
        this.setChanged();
    }

    private void updateRope(ServerLevel level) {
        if (this.rope == null || !this.rope.isActive()) {
            return;
        }
        Vec3 mine = Vec3.atCenterOf(this.worldPosition);
        this.rope.setAttachment(
                RopeHandle.AttachmentPoint.END,
                new Vector3d(mine.x, mine.y, mine.z),
                com.cbc_more_content.compat.SableDropCompat.containingSubLevel(level, this.worldPosition));
        this.rope.setFirstSegmentLength(this.ropeLength * 0.5D);
    }

    private void detonate(ServerLevel level) {
        this.triggered = false;
        this.unanchor(level);
        level.removeBlock(this.worldPosition, false);
        BombExplosionHandler.detonateSeaMine(
                level,
                null,
                com.cbc_more_content.damage.MineDamageSource.create(level, MineType.SEA),
                this.worldPosition.getCenter(),
                MineType.SEA.blockBlastPower,
                MineType.SEA.entityBlastPower);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        this.ageInWater = tag.getInt("WaterAge");
        this.oxidation = Math.max(0, Math.min(3, tag.getInt("Oxidation")));
        this.triggered = tag.getBoolean("Triggered");
        this.anchor = tag.contains("Anchor") ? BlockPos.of(tag.getLong("Anchor")) : null;
        this.ropeLength = tag.getDouble("RopeLength");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("WaterAge", this.ageInWater);
        tag.putInt("Oxidation", this.oxidation);
        tag.putBoolean("Triggered", this.triggered);
        if (this.anchor != null) {
            tag.putLong("Anchor", this.anchor.asLong());
            tag.putDouble("RopeLength", this.ropeLength);
        }
    }

    @Nullable
    public static BlockPos findAnchor(Level level, BlockPos from) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(from.getX(), from.getY(), from.getZ());
        for (int i = 0; i < 256; i++) {
            cursor.move(0, -1, 0);
            if (cursor.getY() < level.getMinBuildHeight()) {
                return null;
            }
            var state = level.getBlockState(cursor);
            if (state.isAir() || !state.getFluidState().isEmpty() || !state.isSolidRender(level, cursor)) {
                continue;
            }
            return cursor.immutable();
        }
        return null;
    }
}
