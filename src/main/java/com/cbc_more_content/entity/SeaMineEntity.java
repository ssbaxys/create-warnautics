package com.cbc_more_content.entity;

import com.cbc_more_content.compat.sable.SeaMineSableCompat;
import com.cbc_more_content.damage.MineDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.mine.MineType;
import com.cbc_more_content.registry.ModEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A moored underwater contact mine.
 * <p>
 * An entity rather than a block for three reasons, in order: it has to settle at an
 * exact height above the seabed (a block is stuck to whole cells), it oxidizes through
 * four models without ever being four blockstates in the palette, and it has to ride a
 * rising chain smoothly while staying a live fuze. The chain below is rendered, not
 * placed — no column of blocks to break, grief or tick.
 * <p>
 * Contact fuze only. A player swimming into the horns, or a hull drifting over the
 * mooring, sets it off. Being struck — shot, chopped, whatever — does too: a mine is
 * a casing full of high explosive, not something you can take a swing at.
 */
public class SeaMineEntity extends Entity {
    /** How the four oxidization stages sync. Also the only synched datum there is. */
    private static final EntityDataAccessor<Integer> OXIDATION =
            SynchedEntityData.defineId(SeaMineEntity.class, EntityDataSerializers.INT);

    /** Slow drift toward the resting hover, blocks/tick² — a heavy object in water. */
    private static final double RISE_ACCEL = 0.012D;

    private static final double FALL_ACCEL = 0.010D;
    /** Water drag, per tick. */
    private static final double WATER_DRAG = 0.82D;
    /** Air drag, per tick — it should thud down out of the water, not float. */
    private static final double AIR_DRAG = 0.96D;
    /**
     * Settle-speed cap. Eased out toward as the hover is approached — the mine slows
     * into its mark over roughly the last block and a half instead of hitting a wall.
     */
    private static final double MAX_SPEED = 0.09D;

    /**
     * Base mooring depth: seabed to mine centre, in blocks. The user asked for about
     * four; the horns reach a little above the body, so the body hovers a shade under.
     */
    public static final double BASE_MOORING_HEIGHT = 3.85D;
    /** How far one added chain link lifts the mine. */
    public static final double HEIGHT_PER_CHAIN = 1.0D;
    /** Mooring height cap. Past this the chain visibly leaves its column. */
    public static final double MAX_MOORING_HEIGHT = 24.0D;

    /** Vanilla copper block oxidizes in an average of ~163 ticks per stage underwater; this is slower, so placement reads as an event. */
    private static final int OXIDATION_TICKS_PER_STAGE = 30 * 60;

    /** Mooring length in blocks, saved. Starts at {@link #BASE_MOORING_HEIGHT}. */
    private double mooringHeight = BASE_MOORING_HEIGHT;
    /** Ticks spent submerged, driving the oxidation stage. */
    private int ageInWater;
    /** Set the tick a fuze contact is accepted; the burst lands next tick. */
    private boolean triggered;

    public SeaMineEntity(EntityType<? extends SeaMineEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
    }

    public SeaMineEntity(Level level, Vec3 pos) {
        this(ModEntityTypes.SEA_MINE.get(), level);
        this.setPos(pos.x, pos.y, pos.z);
        this.xo = pos.x;
        this.yo = pos.y;
        this.zo = pos.z;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(OXIDATION, 0);
    }

    // —— ticking ——

    @Override
    public void tick() {
        this.baseTick();

        if (this.level().isClientSide) {
            // The wake of a settling mine: a bubble now and then while it is still moving.
            if (this.isInWater() && this.getDeltaMovement().lengthSqr() > 1.0E-6D && this.random.nextInt(4) == 0) {
                this.level()
                        .addParticle(
                                ParticleTypes.BUBBLE,
                                this.getX() + (this.random.nextDouble() - 0.5D) * 0.6D,
                                this.getY() + (this.random.nextDouble() - 0.5D) * 0.6D,
                                this.getZ() + (this.random.nextDouble() - 0.5D) * 0.6D,
                                0.0D,
                                0.02D,
                                0.0D);
            }
            return;
        }

        if (this.triggered) {
            this.detonate();
            return;
        }

        this.applyBuoyancy();
        this.ageAndOxidize();
        this.sweepForContact();
        SeaMineSableCompat.sweepHulls((ServerLevel) this.level(), this);
    }

    /**
     * Hangs at the mooring height: accelerates toward the target, drags in whatever
     * fluid it is in, never exceeds a settle-speed. Out of water it simply falls.
     */
    private void applyBuoyancy() {
        BlockPos floor = findAnchor(this.level(), this.blockPosition());
        if (floor == null) {
            // No mooring within reach of the chain: it sinks like the metal it is,
            // rather than hanging from nothing.
            Vec3 fall = this.getDeltaMovement().add(0.0D, -FALL_ACCEL * 2.0D, 0.0D);
            this.setDeltaMovement(fall);
            this.move(MoverType.SELF, fall);
            return;
        }
        double targetY = floor.getY() + 1.0D + this.mooringHeight();

        Vec3 motion = this.getDeltaMovement();
        double dy = targetY - this.getY();
        double accel = dy > 0.0D ? RISE_ACCEL : FALL_ACCEL;
        double step = Math.abs(dy) < accel ? dy : Math.signum(dy) * accel;

        // Only the horizontal axes are pinned to the mooring; leaving motion.y in
        // place lets the drag below actually act on the rise, instead of resetting it
        // every tick and turning the climb into a steppy, sawtooth approximation.
        double nextY = (motion.y + step) * (this.isInWater() ? WATER_DRAG : AIR_DRAG);
        // Ease out to the settle-speed cap as the mine approaches its hover: a hard
        // clamp reads as a bounce-stop, a blended one reads as settling. The easing
        // window is a block or so either side of the mark.
        double capped = MAX_SPEED * Math.min(1.0D, Math.abs(dy) / 1.5D);
        nextY = net.minecraft.util.Mth.clamp(nextY, -capped, capped);
        motion = new Vec3(0.0D, nextY, 0.0D);
        this.setDeltaMovement(motion);
        this.move(MoverType.SELF, motion);
    }

    /** One stage of four, at fixed intervals, while it sits in water. */
    private void ageAndOxidize() {
        if (!this.isInWater()) {
            return;
        }
        this.ageInWater++;
        int stage = Math.min(3, this.ageInWater / OXIDATION_TICKS_PER_STAGE);
        if (stage != this.getOxidation() && this.ageInWater % OXIDATION_TICKS_PER_STAGE == 0) {
            this.setOxidation(stage);
            this.playOxidizeSound();
        }
    }

    /**
     * The contact fuze.
     * <p>
     * A swimmer in the horns' reach goes off — the horns are a pressure device and a
     * body pushing through the water next to one is exactly what they are for. Fish and
     * drifting items do not: a fuze that a cod could trip would empty the ocean on its
     * own. Creative flying is skipped, as with every mine in this mod.
     */
    private void sweepForContact() {
        double reach = MineType.SEA_TRIGGER_REACH;
        AABB horns = this.getBoundingBox().inflate(reach);
        for (Player swimmer : this.level().getEntitiesOfClass(Player.class, horns, LivingEntity::isAlive)) {
            if (swimmer.isSpectator() || swimmer.getAbilities().flying) {
                continue;
            }
            this.trigger();
            return;
        }
    }

    /** One tick of grace, so the horn contact reads before the water erases it. */
    public void trigger() {
        if (!this.level().isClientSide) {
            this.triggered = true;
        }
    }

    private void detonate() {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.playDetonationSound();
        // Hulls only: the charge breaks ships, not the water it hangs in or the seabed
        // under it. Entities and Sable physics objects inside the burst still take the
        // full pressure and impulse.
        BombExplosionHandler.detonateSeaMine(
                server,
                null,
                MineDamageSource.create(server, MineType.SEA),
                this.position(),
                MineType.SEA.blockBlastPower,
                MineType.SEA.entityBlastPower);
        this.discard();
    }

    private void playOxidizeSound() {
        this.level()
                .playSound(
                        null,
                        this.getX(),
                        this.getY(),
                        this.getZ(),
                        SoundEvents.COPPER_BULB_TURN_OFF,
                        SoundSource.BLOCKS,
                        0.45f,
                        0.75f);
    }

    private void playDetonationSound() {
        this.level()
                .playSound(
                        null,
                        this.getX(),
                        this.getY(),
                        this.getZ(),
                        SoundEvents.GENERIC_EXPLODE.value(),
                        SoundSource.BLOCKS,
                        2.0f,
                        0.85f);
    }

    // —— chain interaction ——

    /**
     * Right-click with a chain: one more link, one block higher, the chain consumed.
     * Whether the anchor still exists decides nothing — the mine simply rides higher
     * on the same mooring. At the cap there is nothing more to pay out.
     */
    @Override
    public net.minecraft.world.InteractionResult interact(Player player, net.minecraft.world.InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() != Items.CHAIN) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        if (this.level().isClientSide) {
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        if (!this.addChainLink()) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("message.cbc_more_content.sea_mine.max_height"),
                    true);
            return net.minecraft.world.InteractionResult.CONSUME;
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        this.level()
                .playSound(
                        null,
                        this.getX(),
                        this.getY(),
                        this.getZ(),
                        SoundEvents.CHAIN_PLACE,
                        SoundSource.BLOCKS,
                        0.8f,
                        1.0f);
        return net.minecraft.world.InteractionResult.CONSUME;
    }

    /**
     * Right-click with a chain: one more link, one block higher. Whether the anchor
     * still exists decides nothing — the mine simply rides higher on the same mooring.
     */
    public boolean addChainLink() {
        if (this.mooringHeight() + HEIGHT_PER_CHAIN > MAX_MOORING_HEIGHT) {
            return false;
        }
        this.mooringHeight += HEIGHT_PER_CHAIN;
        return true;
    }

    public double mooringHeight() {
        return this.mooringHeight;
    }

    public int getOxidation() {
        return this.entityData.get(OXIDATION);
    }

    private void setOxidation(int stage) {
        this.entityData.set(OXIDATION, Math.max(0, Math.min(3, stage)));
    }

    // —— collision / fuze plumbing ——

    /**
     * Solid to everything, so hulls and swimmers actually touch it rather than passing
     * through — and so the sweep above is what decides to go off, not the physics.
     */
    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    /** A shove is a contact: any collision pushes the fuze, exactly as bumping the horns would. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide) {
            return true;
        }
        this.trigger();
        return true;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(0.94f, 0.94f);
    }

    @Override
    public void push(Entity entity) {
        super.push(entity);
        if (!this.level().isClientSide && entity.isAlive() && !(entity instanceof SeaMineEntity)) {
            this.trigger();
        }
    }

    /** Hit scans and projectiles reach it, which matters because a hit sets it off. */
    @Override
    public boolean canBeHitByProjectile() {
        return true;
    }

    // —— persistence ——

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.mooringHeight = tag.contains("Mooring")
                ? net.minecraft.util.Mth.clamp(tag.getDouble("Mooring"), BASE_MOORING_HEIGHT, MAX_MOORING_HEIGHT)
                : BASE_MOORING_HEIGHT;
        this.ageInWater = tag.getInt("WaterAge");
        this.setOxidation(tag.getInt("Oxidation"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putDouble("Mooring", this.mooringHeight);
        tag.putInt("WaterAge", this.ageInWater);
        tag.putInt("Oxidation", this.getOxidation());
    }

    // —— helpers ——

    /**
     * The seabed under a moored mine, wherever the mine has drifted to. Glass and leaves
     * are refused so a chain cannot be stood on a pane; any ordinary solid will do.
     */
    @javax.annotation.Nullable
    public static BlockPos findAnchor(Level level, BlockPos from) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(from.getX(), from.getY(), from.getZ());
        for (int i = 0; i < 32; i++) {
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

    /**
     * Whether the mine would hang free at {@code pos}: water around it, and a real
     * seabed close enough below that the chain has something to stand on.
     */
    public static boolean canMooring(Level level, BlockPos pos) {
        if (!level.getFluidState(pos).is(FluidTags.WATER)) {
            return false;
        }
        return SeaMineEntity.findAnchor(level, pos) != null;
    }

    /** Creative-picked or /summoned mines start unoxidized and unchained. */
    @Override
    public ItemStack getPickedResult(net.minecraft.world.phys.HitResult target) {
        return new ItemStack(com.cbc_more_content.registry.ModItems.SEA_MINE.get());
    }
}
