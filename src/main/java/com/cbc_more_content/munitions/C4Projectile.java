package com.cbc_more_content.munitions;

import com.cbc_more_content.block.C4Block;
import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModEntityTypes;
import com.cbc_more_content.registry.ModItems;
import com.cbc_more_content.registry.ModSounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A C4 charge in the air, either just thrown or knocked loose when the surface holding it
 * was mined away. A live charge keeps counting down the whole way and lands still ticking.
 */
public class C4Projectile extends ThrowableItemProjectile {
    /** Twice a snowball's pull, so the throw is a short lob rather than a long arc. */
    private static final double GRAVITY = 0.06D;

    private int fuseSeconds = C4BlockEntity.DEFAULT_SECONDS;
    private int remaining;
    private int code = -1;
    private boolean armed;
    /** Quarter turns about the face it lands on, taken from the thrower's heading. */
    private int rotation;
    private int sinceLastBeep;

    public C4Projectile(EntityType<? extends C4Projectile> type, Level level) {
        super(type, level);
    }

    public C4Projectile(Level level, LivingEntity thrower) {
        super(ModEntityTypes.C4.get(), thrower, level);
        // Thrown charges land turned to face whoever threw them, rather than all facing
        // the same way whatever direction they came from.
        this.rotation = Math.floorMod(Math.round(thrower.getYRot() / 90.0f), 4);
    }

    /**
     * Turns a placed charge back into a falling one, carrying its fuse across intact.
     * Mining the floor out from under a live charge drops it onto whatever is below;
     * it does not defuse it.
     */
    public static void dislodge(ServerLevel level, BlockPos pos, BlockState state) {
        C4Projectile charge = new C4Projectile(ModEntityTypes.C4.get(), level);
        if (level.getBlockEntity(pos) instanceof C4BlockEntity be) {
            charge.fuseSeconds = be.fuseSeconds();
            charge.remaining = be.remaining();
            charge.code = be.code();
            charge.armed = be.isArmed();
        }
        charge.rotation = state.getValue(C4Block.ROTATION);
        charge.setItem(new ItemStack(ModItems.C4.get()));
        charge.setPos(Vec3.atCenterOf(pos));
        // A slight tumble, so it reads as a charge coming loose rather than a block
        // model sliding straight down.
        RandomSource random = level.getRandom();
        charge.setDeltaMovement(
                (random.nextDouble() - 0.5D) * 0.08D,
                -0.08D,
                (random.nextDouble() - 0.5D) * 0.08D);
        // A charge coming loose keeps lying flat. The renderer reads the launch heading,
        // and -90 pitch is what leaves the model the way up it was already sitting.
        charge.setXRot(-90.0f);
        charge.setYRot(random.nextInt(4) * 90.0f);
        charge.xRotO = charge.getXRot();
        charge.yRotO = charge.getYRot();

        // Removed without drops: the charge is becoming the entity, not being broken.
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        level.addFreshEntity(charge);
    }

    @Override
    protected double getDefaultGravity() {
        return GRAVITY;
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.C4.get();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide || !this.armed || this.isRemoved()) {
            return;
        }
        // A fuse does not pause because the charge is in mid-air, and neither does the
        // beeping — a charge falling past you should still be audible, and one whose
        // timer runs out on the way down goes off there.
        if (--this.remaining <= 0) {
            C4BlockEntity.explode((ServerLevel) this.level(), this.position());
            this.discard();
            return;
        }
        this.beep();
    }

    private void beep() {
        float urgency = C4BlockEntity.urgency(this.remaining, this.fuseSeconds);
        if (++this.sinceLastBeep < C4BlockEntity.beepInterval(urgency)) {
            return;
        }
        this.sinceLastBeep = 0;
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                ModSounds.C4_TICK.get(), SoundSource.BLOCKS,
                1.1f, C4BlockEntity.beepPitch(urgency));
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (this.level().isClientSide) {
            return;
        }
        if (result instanceof BlockHitResult blockHit) {
            this.stickTo(blockHit);
        } else if (this.armed) {
            // Bounced off something while live: it stays live and keeps falling.
            return;
        } else {
            this.dropAsItem();
        }
        this.discard();
    }

    private void stickTo(BlockHitResult hit) {
        Direction face = hit.getDirection();
        BlockPos target = hit.getBlockPos().relative(face);
        BlockState existing = this.level().getBlockState(target);
        BlockState charge = ModBlocks.C4.get().defaultBlockState()
                .setValue(C4Block.FACING, face)
                .setValue(C4Block.ROTATION, C4Block.usableRotation(face, this.rotation));

        if (!existing.canBeReplaced() || !charge.canSurvive(this.level(), target)) {
            if (this.armed) {
                C4BlockEntity.explode((ServerLevel) this.level(), this.position());
            } else {
                this.dropAsItem();
            }
            return;
        }

        this.level().setBlock(target, charge, 3);
        if (this.level().getBlockEntity(target) instanceof C4BlockEntity be) {
            be.restore(this.fuseSeconds, this.remaining, this.code, this.armed);
        }
        this.level().playSound(null, target, ModSounds.C4_PLACE.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
    }

    private void dropAsItem() {
        this.spawnAtLocation(this.getDefaultItem());
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("FuseSeconds", this.fuseSeconds);
        tag.putInt("Remaining", this.remaining);
        tag.putInt("Code", this.code);
        tag.putBoolean("Armed", this.armed);
        tag.putInt("Rotation", this.rotation);
        tag.putInt("SinceLastBeep", this.sinceLastBeep);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.fuseSeconds = tag.contains("FuseSeconds")
                ? tag.getInt("FuseSeconds") : C4BlockEntity.DEFAULT_SECONDS;
        this.remaining = tag.getInt("Remaining");
        this.code = tag.contains("Code") ? tag.getInt("Code") : -1;
        this.armed = tag.getBoolean("Armed");
        this.rotation = tag.getInt("Rotation");
        this.sinceLastBeep = tag.getInt("SinceLastBeep");
    }
}
