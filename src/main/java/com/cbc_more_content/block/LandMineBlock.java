package com.cbc_more_content.block;

import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.damage.MineDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.effects.MineExplosionHandler;
import com.cbc_more_content.mine.MineType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import rbasamoyai.createbigcannons.CBCCompatTransformers;

/**
 * Flat land mine. Arms after a short delay so the placer is safe.
 * Small = infantry {@link #stepOn}; large = Sable / Offroad wheel (via callbacks).
 */
public class LandMineBlock extends Block implements IWrenchable {
    public static final BooleanProperty ARMED = BooleanProperty.create("armed");
    /** How far the mine has been dug in: 0 sitting proud, {@link #MAX_BURIAL} flush. */
    public static final IntegerProperty BURIAL = IntegerProperty.create("burial", 0, 8);

    public static final int MAX_BURIAL = 8;
    /** Planted into a bed rather than into the ground; the whole charge drops onto the bedding. */
    public static final BooleanProperty IN_BED = BooleanProperty.create("in_bed");
    /** How far the model sinks so it lies on a mattress instead of hovering over it. */
    private static final double BED_DROP = 7.0D / 16.0D;
    /** Ticks after place before the mine can detonate. */
    public static final int ARM_DELAY_TICKS = 20;
    /** How often a proximity charge looks around itself. */
    private static final int PROXIMITY_INTERVAL = 2;
    /**
     * Physics and wheel casts may report the same mine several times per sub-step.
     * Keep at most one server task per mine until that task has run.
     */
    private static final Set<PendingDetonation> PENDING_VEHICLE_DETONATIONS = ConcurrentHashMap.newKeySet();

    public static final MapCodec<LandMineBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    propertiesCodec(), Codec.STRING.fieldOf("mine_type").forGetter(b -> b.type.name()))
            .apply(instance, (props, name) -> new LandMineBlock(props, MineType.valueOf(name))));

    private final MineType type;

    public LandMineBlock(Properties properties, MineType type) {
        super(properties);
        this.type = type;
        this.registerDefaultState(this.stateDefinition
                .any()
                .setValue(ARMED, false)
                .setValue(BURIAL, 0)
                .setValue(IN_BED, false));
    }

    public MineType getMineType() {
        return this.type;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ARMED, BURIAL, IN_BED);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Clicking the top of a bed puts the charge in the air cell above it, which is
        // the only place it can live; from there it is dropped onto the bedding.
        boolean bed = this.type.beddable
                && context.getLevel()
                                .getBlockState(context.getClickedPos().below())
                                .getBlock()
                        instanceof net.minecraft.world.level.block.BedBlock;
        return this.defaultBlockState()
                .setValue(ARMED, false)
                .setValue(BURIAL, 0)
                .setValue(IN_BED, bed);
    }

    /** A charge laid in a bed goes with the bed, and no mine sits on open water. */
    @Override
    protected boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        if (state.getValue(IN_BED)) {
            return level.getBlockState(pos.below()).getBlock() instanceof net.minecraft.world.level.block.BedBlock;
        }
        BlockState below = level.getBlockState(pos.below());
        return !below.isAir() && below.getFluidState().isEmpty();
    }

    /**
     * Ground a charge can actually be dug into: anything a shovel is the right tool for
     * — soil, sand, gravel, snow, clay. Stone, planks and open water are not.
     */
    private static boolean isDiggableGround(net.minecraft.world.level.LevelReader level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)
                && state.getFluidState().isEmpty();
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            net.minecraft.core.Direction direction,
            BlockState neighborState,
            net.minecraft.world.level.LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos) {
        if (direction == net.minecraft.core.Direction.DOWN && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide && !state.getValue(ARMED)) {
            level.scheduleTick(pos, this, ARM_DELAY_TICKS);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(ARMED)) {
            level.setBlock(pos, state.setValue(ARMED, true), Block.UPDATE_CLIENTS);
            level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.35f, 0.6f);
            if (this.type.triggerReach > 0.0D) {
                level.scheduleTick(pos, this, PROXIMITY_INTERVAL);
            }
            return;
        }
        if (this.type.triggerReach <= 0.0D) {
            return;
        }
        // A charge that notices someone nearby cannot wait to be stepped on, so it keeps
        // its own watch. Polling rather than a contact hook because there is no callback
        // for "an entity moved through the cell next to mine".
        if (this.sweepForProximity(level, pos, state)) {
            return;
        }
        level.scheduleTick(pos, this, PROXIMITY_INTERVAL);
    }

    /**
     * Looks a little way past the charge's own cell for anything worth going off at.
     *
     * @return true once it has gone off, so nothing reschedules against a dead block
     */
    private boolean sweepForProximity(ServerLevel level, BlockPos pos, BlockState state) {
        double reach = this.type.triggerReach;
        AABB around = new AABB(pos).inflate(reach);
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, around, LivingEntity::isAlive)) {
            if (living.isSpectator()) {
                continue;
            }
            if (living instanceof Player player && player.getAbilities().flying) {
                continue;
            }
            this.trip(level, pos, state);
            return true;
        }
        return false;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.buriedShape(state);
    }

    /**
     * The outline sinks with the mine, so a fully dug-in charge is flush with the ground
     * and no longer catches the cursor from across the field.
     */
    private VoxelShape buriedShape(BlockState state) {
        int burial = state.getValue(BURIAL);
        if (burial <= 0) {
            return state.getValue(IN_BED) ? this.type.shape.move(0.0D, -BED_DROP, 0.0D) : this.type.shape;
        }
        var bounds = this.type.shape.bounds();
        double top = Math.max(0.05D, bounds.maxY - burial / (double) MAX_BURIAL * bounds.maxY);
        VoxelShape shape = Block.box(
                bounds.minX * 16.0D,
                bounds.minY * 16.0D,
                bounds.minZ * 16.0D,
                bounds.maxX * 16.0D,
                top * 16.0D,
                bounds.maxZ * 16.0D);
        return state.getValue(IN_BED) ? shape.move(0.0D, -BED_DROP, 0.0D) : shape;
    }

    /**
     * Digging the mine in with a shovel.
     * <p>
     * Progress per use is taken from the shovel's own dig speed against the mine, so a
     * netherite blade buries a charge in a couple of scoops where a wooden one takes
     * several. A buried mine keeps working — this only conceals it.
     */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            net.minecraft.world.InteractionHand hand,
            BlockHitResult hit) {
        if (player != null && player.isShiftKeyDown()) {
            // Sneaking lifts it out whatever is in hand, so a shovel is not a trap.
            return digUp(state, level, pos, player) == net.minecraft.world.InteractionResult.PASS
                    ? net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                    : net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (state.getValue(IN_BED)) {
            // No digging in bedding: a charge in a bed is worked down by hand, and the
            // last click brings it back up so a bad guess can be undone.
            sinkInBedding(state, level, pos);
            return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!(stack.getItem() instanceof net.minecraft.world.item.ShovelItem)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!isDiggableGround(level, pos.below())) {
            // A charge is dug into loose ground, not pressed into stone or floated on
            // water. Without this a mine could be sunk flush anywhere at all, which made
            // the whole burial mechanic a way of hiding one inside a solid floor.
            if (!level.isClientSide) {
                level.playSound(null, pos, SoundEvents.STONE_HIT, SoundSource.BLOCKS, 0.5f, 1.4f);
            }
            return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        int burial = state.getValue(BURIAL);
        if (burial >= MAX_BURIAL) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (!level.isClientSide) {
            int next = Math.min(MAX_BURIAL, burial + shovelStages(stack));
            level.setBlock(pos, state.setValue(BURIAL, next), Block.UPDATE_CLIENTS);

            level.playSound(
                    null,
                    pos,
                    SoundEvents.ROOTED_DIRT_BREAK,
                    SoundSource.BLOCKS,
                    0.7f,
                    0.8f + level.getRandom().nextFloat() * 0.25f);
            if (level instanceof ServerLevel server) {
                server.sendParticles(
                        new net.minecraft.core.particles.BlockParticleOption(
                                net.minecraft.core.particles.ParticleTypes.BLOCK,
                                net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState()),
                        pos.getX() + 0.5D,
                        pos.getY() + 0.15D,
                        pos.getZ() + 0.5D,
                        12,
                        0.3D,
                        0.05D,
                        0.3D,
                        0.02D);
            }
            if (player != null && !player.getAbilities().instabuild) {
                stack.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            }
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isShiftKeyDown()) {
            return digUp(state, level, pos, player);
        }
        if (!state.getValue(IN_BED)) {
            return InteractionResult.PASS;
        }
        sinkInBedding(state, level, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Working a charge back up, one notch at a time — the mirror of burying it, not a
     * way of pocketing it. The same stages, so a charge can be brought back to sitting
     * proud exactly as gradually as it was hidden.
     */
    private InteractionResult digUp(BlockState state, Level level, BlockPos pos, Player player) {
        int burial = state.getValue(BURIAL);
        if (burial <= 0) {
            // Already fully exposed: nothing to uncover, so leave the click alone.
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            int stages = state.getValue(IN_BED) ? 1 : shovelStages(player.getMainHandItem());
            level.setBlock(pos, state.setValue(BURIAL, Math.max(0, burial - stages)), Block.UPDATE_CLIENTS);
            level.playSound(null, pos, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.BLOCKS, 0.7f, 1.15f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * How many burial stages one scoop moves, from the tool's own tier speed. Vanilla
     * shovels run 2 (wood) to 9 (netherite); bare hands always manage one.
     */
    private static int shovelStages(ItemStack stack) {
        if (!(stack.getItem() instanceof net.minecraft.world.item.ShovelItem)) {
            return 1;
        }
        float speed = Math.max(
                1.0f,
                stack.getItem()
                        .getDestroySpeed(stack, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState()));
        return Math.max(1, Math.round(speed / 1.2f));
    }

    /**
     * One notch further into the bedding, wrapping back to sitting proud at the end, so
     * a charge can be worked from plainly visible down to barely a bump and back.
     */
    private static void sinkInBedding(BlockState state, Level level, BlockPos pos) {
        if (level.isClientSide) {
            return;
        }
        int next = (state.getValue(BURIAL) + 1) % (MAX_BURIAL + 1);
        level.setBlock(pos, state.setValue(BURIAL, next), Block.UPDATE_CLIENTS);
        level.playSound(null, pos, SoundEvents.WOOL_HIT, SoundSource.BLOCKS, 0.5f, 0.9f + next * 0.03f);
    }

    /**
     * Anyone turning in sets off a charge laid in either half of the bed. Walking across
     * one is already covered by {@code entityInside}, but a sleeper folds into a box far
     * too small to reach the cell the charge sits in.
     */
    public static boolean detonateBeddedMines(ServerLevel level, BlockPos bedPos) {
        BlockState bed = level.getBlockState(bedPos);
        BlockPos[] halves = bed.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                ? new BlockPos[] {
                    bedPos, bedPos.relative(net.minecraft.world.level.block.BedBlock.getConnectedDirection(bed))
                }
                : new BlockPos[] {bedPos};
        for (BlockPos half : halves) {
            BlockPos above = half.above();
            if (!level.isLoaded(above)) {
                continue;
            }
            BlockState state = level.getBlockState(above);
            if (state.getBlock() instanceof LandMineBlock && state.getValue(IN_BED) && state.getValue(ARMED)) {
                detonate(level, above, state);
                return true;
            }
        }
        return false;
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // Same thin disc — entities actually intersect it (pressure-plate style). One
        // lying in a bed has none at all: the bedding is what you stand on, and a lip
        // above it would give the charge away by tripping anyone walking across.
        return state.getValue(IN_BED) ? net.minecraft.world.phys.shapes.Shapes.empty() : this.type.shape;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        tryInfantryDetonate(level, pos, state, entity);
        super.stepOn(level, pos, state, entity);
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        tryInfantryDetonate(level, pos, state, entity);
        super.entityInside(state, level, pos, entity);
    }

    @Override
    public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, float fallDistance) {
        tryInfantryDetonate(level, pos, state, entity);
        super.fallOn(level, state, pos, entity, fallDistance);
    }

    private void tryInfantryDetonate(Level level, BlockPos pos, BlockState state, Entity entity) {
        if (level.isClientSide || !this.type.infantryTrigger || !state.getValue(ARMED)) {
            return;
        }
        if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isSpectator()) {
            return;
        }
        // Creative flying still safe; walking/sprinting in creative arms the fuze (easier to test).
        if (living instanceof Player player && player.getAbilities().flying) {
            return;
        }
        if (state.getValue(IN_BED) && !standingOnTheBedding(pos, living)) {
            return;
        }
        this.trip((ServerLevel) level, pos, state);
    }

    /**
     * Whether someone is actually on the bed rather than merely beside it.
     * <p>
     * A charge in a bed lives in the air cell above the mattress, and anyone standing on
     * the floor next to that bed is nearly two blocks tall, so their body reaches into
     * the cell and set the thing off from across the room. Only feet at or above the
     * bedding count.
     */
    private static boolean standingOnTheBedding(BlockPos pos, LivingEntity living) {
        double bedding = pos.getY() - BED_DROP;
        return living.getY() >= bedding - 0.1D;
    }

    /** Called from Sable / Offroad soft-compat (physics or wheel raycast). */
    public static void tryVehicleDetonate(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LandMineBlock mine)) {
            // Wheel casts often resolve parent-world coords while the BE lives on a SubLevel.
            if (ModList.get().isLoaded("sable")) {
                ServerLevel parent = SableDropCompat.resolveWorldBlast(level, Vec3.atCenterOf(pos))
                        .level();
                if (parent != level && parent.isLoaded(pos)) {
                    BlockState parentState = parent.getBlockState(pos);
                    if (parentState.getBlock() instanceof LandMineBlock parentMine
                            && parentMine.type.vehicleTrigger
                            && parentState.getValue(ARMED)) {
                        detonate(parent, pos, parentState);
                    }
                }
            }
            return;
        }
        if (!mine.type.vehicleTrigger || !state.getValue(ARMED)) {
            return;
        }
        detonate(level, pos, state);
    }

    public static void scheduleVehicleDetonate(ServerLevel level, BlockPos pos) {
        BlockPos immutable = pos.immutable();
        PendingDetonation pending = new PendingDetonation(level, immutable.asLong());
        if (!PENDING_VEHICLE_DETONATIONS.add(pending)) {
            return;
        }
        level.getServer()
                .tell(new net.minecraft.server.TickTask(level.getServer().getTickCount(), () -> {
                    try {
                        tryVehicleDetonate(level, immutable);
                    } finally {
                        PENDING_VEHICLE_DETONATIONS.remove(pending);
                    }
                }));
    }

    /**
     * Sets the charge off in whatever way it goes off — straight away for most, or by
     * throwing itself clear first for a bounding one.
     */
    private void trip(ServerLevel level, BlockPos pos, BlockState state) {
        if (this.type != MineType.BOUNDING) {
            detonate(level, pos, state);
            return;
        }
        if (!level.isLoaded(pos) || level.getBlockState(pos).getBlock() != this) {
            return;
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        var thrown = com.cbc_more_content.entity.BoundingMineEntity.pop(
                level, Vec3.atBottomCenterOf(pos).add(0.0D, 0.15D, 0.0D));
        level.addFreshEntity(thrown);
        thrown.playPop();
        level.sendParticles(
                net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE,
                pos.getX() + 0.5D,
                pos.getY() + 0.1D,
                pos.getZ() + 0.5D,
                8,
                0.18D,
                0.02D,
                0.18D,
                0.02D);
    }

    public static void detonate(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof LandMineBlock mine)) {
            return;
        }
        // Already gone (double trigger same tick).
        if (!level.isLoaded(pos) || level.getBlockState(pos).getBlock() != mine) {
            return;
        }
        MineType type = mine.type;
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        Vec3 local = Vec3.atCenterOf(pos);
        ServerLevel blastLevel = level;
        Vec3 blastPos = CBCCompatTransformers.transformVec3(level, local);
        if (ModList.get().isLoaded("sable")) {
            SableDropCompat.BlastTarget target = SableDropCompat.resolveWorldBlast(level, local);
            blastLevel = target.level();
            blastPos = target.pos();
        }
        // Mines report their own death cause, so "blown up by a bomb" no longer shows
        // for someone who stepped on an antipersonnel charge.
        if (type == MineType.SMALL) {
            MineExplosionHandler.detonateSmallShrapnel(
                    blastLevel, null, MineDamageSource.create(blastLevel, type), blastPos, type.entityBlastPower);
        } else {
            BombExplosionHandler.detonateAntiTankMine(
                    blastLevel,
                    null,
                    MineDamageSource.create(blastLevel, type),
                    blastPos,
                    type.blockBlastPower,
                    type.entityBlastPower);
        }
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        // Flat disc — nothing useful to rotate.
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level world = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (!(world instanceof ServerLevel)) {
            return InteractionResult.SUCCESS;
        }

        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(world, pos, state, player);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return InteractionResult.SUCCESS;
        }

        if (player != null && !player.isCreative()) {
            player.getInventory().placeItemBackInInventory(new ItemStack(this));
        }
        world.destroyBlock(pos, false);
        AllSoundEvents.WRENCH_REMOVE.playOnServer(
                world, pos, 1.0f, world.getRandom().nextFloat() * 0.5f + 0.5f);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void wasExploded(Level level, BlockPos pos, net.minecraft.world.level.Explosion explosion) {
        if (level instanceof ServerLevel serverLevel) {
            detonate(serverLevel, pos, level.getBlockState(pos));
        }
    }

    private record PendingDetonation(ServerLevel level, long pos) {}
}
