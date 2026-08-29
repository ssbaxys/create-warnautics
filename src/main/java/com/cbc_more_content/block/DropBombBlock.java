package com.cbc_more_content.block;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.effects.BombSympatheticDetonation;
import com.cbc_more_content.item.DropBombItem;
import com.cbc_more_content.util.DropBombUtil;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import rbasamoyai.createbigcannons.CBCCompatTransformers;

/**
 * Placeable drop bomb. Rising-edge redstone launches; cassette stacks (small only)
 * eject one bomb per tick while powered. Create wrench: rotate / sneak-pickup.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID)
public class DropBombBlock extends Block implements IWrenchable {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    /** Tracks whether we already saw power — prevents place-into-live-wire / neighbor-chain false triggers. */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    /** How many bombs are bundled in this block (1–4). Only {@link BombSize#SMALL} can exceed 1. */
    public static final IntegerProperty CASSETTE = IntegerProperty.create("cassette", 1, 4);

    public static final int MAX_CASSETTE = 4;
    /**
     * Player-selectable release interval. New states store the actual tick count;
     * values 0..5 remain valid solely so worlds made with the old six-preset dial
     * still load without losing their setting.
     */
    public static final int MIN_RELEASE_DELAY_TICKS = 6;

    public static final int MAX_RELEASE_DELAY_TICKS = 100;
    public static final int RELEASE_DELAY_STEP_TICKS = 2;
    public static final int DEFAULT_RELEASE_DELAY = 26;
    private static final int[] LEGACY_RELEASE_DELAY_TICKS = {4, 8, 12, 20, 26, 40};
    public static final IntegerProperty RELEASE_DELAY =
            IntegerProperty.create("release_delay", 0, MAX_RELEASE_DELAY_TICKS);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private static final Map<ProjectileHitKey, ProjectileHitState> PROJECTILE_HITS = new ConcurrentHashMap<>();
    /** A tally that has not been added to in ten seconds is not part of the same attack. */
    private static final int PROJECTILE_HIT_EXPIRY_TICKS = 200;

    private static long lastProjectileHitCleanup = Long.MIN_VALUE;

    public static final MapCodec<DropBombBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    propertiesCodec(), Codec.STRING.fieldOf("bomb_size").forGetter(b -> b.size.name()))
            .apply(instance, (props, sizeName) -> new DropBombBlock(props, BombSize.valueOf(sizeName))));

    private final BombSize size;

    public DropBombBlock(Properties properties, BombSize size) {
        super(properties);
        this.size = size;
        this.registerDefaultState(this.stateDefinition
                .any()
                .setValue(FACING, Direction.DOWN)
                .setValue(POWERED, false)
                .setValue(CASSETTE, 1)
                .setValue(RELEASE_DELAY, DEFAULT_RELEASE_DELAY)
                .setValue(WATERLOGGED, false));
    }

    public BombSize getBombSize() {
        return this.size;
    }

    public boolean allowsCassette() {
        return this.size == BombSize.SMALL;
    }

    public static int clampCassette(int cassette) {
        return Math.max(1, Math.min(MAX_CASSETTE, cassette));
    }

    public static int normalizeReleaseDelayTicks(int ticks) {
        int clamped = Math.max(MIN_RELEASE_DELAY_TICKS, Math.min(MAX_RELEASE_DELAY_TICKS, ticks));
        int steps = Math.round((clamped - MIN_RELEASE_DELAY_TICKS) / (float) RELEASE_DELAY_STEP_TICKS);
        return MIN_RELEASE_DELAY_TICKS + steps * RELEASE_DELAY_STEP_TICKS;
    }

    public static int releaseDelayTicks(int storedValue) {
        if (storedValue >= 0 && storedValue < LEGACY_RELEASE_DELAY_TICKS.length) {
            return LEGACY_RELEASE_DELAY_TICKS[storedValue];
        }
        return normalizeReleaseDelayTicks(storedValue);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED, CASSETTE, RELEASE_DELAY, WATERLOGGED);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        boolean alreadyPowered = isReceivingPower(level, pos);
        int cassette = DropBombItem.getCassette(context.getItemInHand());
        int releaseDelay = DropBombItem.getReleaseDelay(context.getItemInHand());
        if (!this.allowsCassette()) {
            cassette = 1;
        }
        return this.defaultBlockState()
                .setValue(FACING, context.getClickedFace())
                .setValue(POWERED, alreadyPowered)
                .setValue(CASSETTE, clampCassette(cassette))
                .setValue(RELEASE_DELAY, normalizeReleaseDelayTicks(releaseDelay))
                .setValue(WATERLOGGED, level.getFluidState(pos).is(FluidTags.WATER));
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (level.isClientSide || oldState.is(this)) {
            return;
        }
        boolean powered = isReceivingPower(level, pos);
        if (powered != state.getValue(POWERED)) {
            level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
        }
        if (powered) {
            // A rack reloaded under a live wire gets no neighbour update, so the
            // rising-edge test would never fire again. Schedule explicitly.
            level.scheduleTick(pos, this, 1);
        }
        checkHotHazard((ServerLevel) level, pos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.size.shapeFor(state.getValue(FACING).getAxis());
    }

    /**
     * Sea torpedoes are sealed launchers and may be installed in water. The generic
     * bomb block deliberately keeps the normal support rules, but the sea variant
     * must not be destroyed by water replacing its placement space.
     */
    protected boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        return this.size == BombSize.SEA || super.canSurvive(state, level, pos);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighborState,
            net.minecraft.world.level.LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos) {
        // Do not delegate fluid/support updates for sea torpedoes: vanilla's
        // survival update can replace the block with air when water flows into
        // the position, even though the torpedo is intentionally water-safe.
        if (this.size == BombSize.SEA) {
            return state;
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected net.minecraft.world.level.material.FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED)
                ? net.minecraft.world.level.material.Fluids.WATER.getSource(false)
                : super.getFluidState(state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * Applies a release interval to the whole contiguous rack. There is no
     * single-bomb mode: a rack only makes sense with one shared interval.
     */
    public static void applyReleaseDelay(Level level, BlockPos pos, int delayTicks) {
        int changed = applyReleaseDelayToRack(level, pos, normalizeReleaseDelayTicks(delayTicks));
        if (changed > 0) {
            level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.45f, 1.25f);
        }
    }

    /**
     * Survival grouping: right-click a placed small bomb with another small bomb / cassette
     * to merge up to {@link #MAX_CASSETTE}.
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
        if (!this.allowsCassette() || !(state.getBlock() instanceof DropBombBlock target) || !target.allowsCassette()) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(stack.getItem() instanceof DropBombItem heldItem) || heldItem.getBlock() != this) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        int current = state.getValue(CASSETTE);
        if (current >= MAX_CASSETTE) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        int adding = DropBombItem.getCassette(stack);
        int space = MAX_CASSETTE - current;
        int merged = Math.min(space, adding);
        if (merged <= 0) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (!level.isClientSide) {
            level.setBlock(pos, state.setValue(CASSETTE, current + merged), Block.UPDATE_CLIENTS);
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.6f, 1.1f);

            if (!player.getAbilities().instabuild) {
                int left = adding - merged;
                if (left <= 0) {
                    stack.shrink(1);
                } else {
                    DropBombItem.setCassette(stack, left);
                }
            }
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void neighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block neighborBlock,
            BlockPos neighborPos,
            boolean movedByPiston) {
        if (level.isClientSide) {
            return;
        }

        boolean powered = isReceivingPower(level, pos);
        boolean wasPowered = state.getValue(POWERED);

        if (powered != wasPowered) {
            level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
        }
        if (powered) {
            // Scheduled whenever the rack is live, not only on the rising edge:
            // POWERED is persisted, so a chunk unloading mid-cycle could otherwise
            // leave a bomb stored as powered with no pending tick behind it.
            // Duplicate ticks for a position collapse, so a running rack is fine.
            level.scheduleTick(pos, this, 1);
        }
        checkHotHazard((ServerLevel) level, pos);
    }

    @Override
    protected void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
        if (!(level instanceof ServerLevel serverLevel) || state.getBlock() != this) {
            return;
        }
        if (!WarnauticsConfig.externalChainDetonation()) {
            return;
        }

        ProjectileHitKey key =
                new ProjectileHitKey(serverLevel, hit.getBlockPos().asLong());
        int damage = projectile instanceof AbstractArrow ? 1 : 2;
        if (projectile.isOnFire()) {
            damage++;
        }
        final int hitDamage = damage;
        long tick = serverLevel.getServer().getTickCount();
        ProjectileHitState hitState = PROJECTILE_HITS.compute(key, (ignored, previous) -> {
            if (previous == null || tick - previous.lastHitTick > PROJECTILE_HIT_EXPIRY_TICKS) {
                return new ProjectileHitState(hitDamage, tick);
            }
            return new ProjectileHitState(previous.hits + hitDamage, tick);
        });
        int hits = hitState.hits;
        int threshold =
                switch (this.size) {
                    case SMALL -> 3;
                    case SEA -> 4;
                    case MEDIUM -> 5;
                    case LARGE -> 7;
                };

        serverLevel.playSound(
                null,
                hit.getBlockPos(),
                SoundEvents.ANVIL_HIT,
                SoundSource.BLOCKS,
                0.55f,
                1.55f + serverLevel.random.nextFloat() * 0.18f);
        if (hits < threshold) {
            return;
        }

        PROJECTILE_HITS.remove(key);
        int minTicks = projectile.isOnFire() ? 6 : 14;
        int maxTicks = projectile.isOnFire() ? 22 : 48;
        BombSympatheticDetonation.schedulePlacedBombCookoff(serverLevel, hit.getBlockPos(), minTicks, maxTicks);
    }

    /** Sweeps out tallies for bombs nobody is shooting at any more. */
    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (PROJECTILE_HITS.isEmpty()) {
            return;
        }
        long tick = event.getServer().getTickCount();
        if (tick - lastProjectileHitCleanup < 20L) {
            return;
        }
        lastProjectileHitCleanup = tick;
        for (Map.Entry<ProjectileHitKey, ProjectileHitState> entry : PROJECTILE_HITS.entrySet()) {
            if (tick - entry.getValue().lastHitTick > PROJECTILE_HIT_EXPIRY_TICKS) {
                PROJECTILE_HITS.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void checkHotHazard(ServerLevel level, BlockPos pos) {
        if (!WarnauticsConfig.externalChainDetonation() || !isTouchingFireOrLava(level, pos)) {
            return;
        }
        BombSympatheticDetonation.schedulePlacedBombCookoff(level, pos, 12, 45);
    }

    private static boolean isTouchingFireOrLava(Level level, BlockPos pos) {
        if (isFireOrLava(level, pos)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (isFireOrLava(level, pos.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFireOrLava(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || level.getFluidState(pos).is(FluidTags.LAVA);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (state.getBlock() != newState.getBlock() && level instanceof ServerLevel serverLevel) {
            PROJECTILE_HITS.remove(new ProjectileHitKey(serverLevel, pos.asLong()));
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(POWERED)) {
            return;
        }
        if (!isReceivingPower(level, pos)) {
            level.setBlock(pos, state.setValue(POWERED, false), Block.UPDATE_CLIENTS);
            return;
        }

        // Plot redstone dump: defer setBlock+spawn one tick so we do not race Sable physics.
        if (ModList.get().isLoaded("sable") && SableDropCompat.isInsideSubLevel(level, pos)) {
            deferEjectFromPlot(level, pos);
            return;
        }

        performRackEject(level, pos);
    }

    private void deferEjectFromPlot(ServerLevel level, BlockPos pos) {
        BlockPos immutable = pos.immutable();
        int when = level.getServer().getTickCount() + 1;
        level.getServer().tell(new net.minecraft.server.TickTask(when, () -> {
            BlockState current = level.getBlockState(immutable);
            if (!(current.getBlock() instanceof DropBombBlock bomb) || bomb != this) {
                return;
            }
            if (!current.getValue(POWERED) || !isReceivingPower(level, immutable)) {
                if (current.getValue(POWERED)) {
                    level.setBlock(immutable, current.setValue(POWERED, false), Block.UPDATE_CLIENTS);
                }
                return;
            }
            bomb.performRackEject(level, immutable);
        }));
    }

    /**
     * The powered bomb is the rack controller. The lowest contiguous bomb is
     * released first, so one signal can feed a tall rack from bottom to top.
     */
    private void performRackEject(ServerLevel level, BlockPos controllerPos) {
        BlockPos releasePos = findLowestRackBomb(level, controllerPos);
        BlockState releaseState = level.getBlockState(releasePos);
        if (!(releaseState.getBlock() instanceof DropBombBlock releaseBomb)) {
            return;
        }

        releaseBomb.ejectOne(level, releasePos, releaseState);

        BlockState currentController = level.getBlockState(controllerPos);
        if (currentController.getBlock() == this
                && currentController.getValue(POWERED)
                && isReceivingPower(level, controllerPos)) {
            int delay = releaseDelayTicks(currentController.getValue(RELEASE_DELAY));
            level.scheduleTick(controllerPos, this, delay);
        }
    }

    /**
     * Sets {@code delayTicks} on every bomb in the clicked block's contiguous vertical
     * rack and returns how many were changed. One click configures a whole bomb bay.
     */
    private static int applyReleaseDelayToRack(Level level, BlockPos clicked, int delayTicks) {
        int changed = 0;
        for (Direction dir : new Direction[] {Direction.DOWN, Direction.UP}) {
            BlockPos cursor = clicked;
            while (true) {
                BlockState current = level.getBlockState(cursor);
                if (!(current.getBlock() instanceof DropBombBlock)) {
                    break;
                }
                if (current.getValue(RELEASE_DELAY) != delayTicks) {
                    level.setBlock(cursor, current.setValue(RELEASE_DELAY, delayTicks), Block.UPDATE_CLIENTS);
                }
                // The clicked block is walked by the DOWN pass only, so it is counted once.
                if (dir == Direction.DOWN || !cursor.equals(clicked)) {
                    changed++;
                }
                BlockPos nextPos = cursor.relative(dir);
                if (level.isOutsideBuildHeight(nextPos)) {
                    break;
                }
                cursor = nextPos;
            }
        }
        return changed;
    }

    private static BlockPos findLowestRackBomb(ServerLevel level, BlockPos controllerPos) {
        BlockPos lowest = controllerPos;
        while (lowest.getY() > level.getMinBuildHeight()) {
            BlockPos below = lowest.below();
            if (!(level.getBlockState(below).getBlock() instanceof DropBombBlock)) {
                break;
            }
            lowest = below;
        }
        return lowest;
    }

    private void ejectOne(ServerLevel level, BlockPos pos, BlockState state) {
        Direction nose = state.getValue(FACING);
        int cassette = state.getValue(CASSETTE);

        // Update or clear the block before spawning so the projectile cannot clip the
        // remaining cassette, especially for sideways asymmetric models.
        if (cassette <= 1) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            launchAlongNose(level, pos, nose, this.size);
            return;
        }

        BlockState remaining = state.setValue(CASSETTE, cassette - 1);
        level.setBlock(pos, remaining, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        launchAlongNose(level, pos, nose, this.size);
    }

    public static boolean isReceivingPower(Level level, BlockPos pos) {
        return findPoweringSide(level, pos) != null || level.hasNeighborSignal(pos);
    }

    @Nullable
    public static Direction findPoweringSide(Level level, BlockPos pos) {
        Direction best = null;
        int bestSignal = 0;
        for (Direction dir : Direction.values()) {
            int signal = level.getSignal(pos.relative(dir), dir);
            if (signal > bestSignal) {
                bestSignal = signal;
                best = dir;
            }
        }
        return bestSignal > 0 ? best : null;
    }

    /**
     * Releases the bomb into free space and lets gravity do the rest. UP is never a
     * release direction — nothing here may push a live bomb into the airframe above.
     */
    private static void launchAlongNose(ServerLevel level, BlockPos pos, Direction nose, BombSize size) {
        Vec3 noseVec = new Vec3(nose.getStepX(), nose.getStepY(), nose.getStepZ());
        Vec3 spawn = resolveReleasePoint(level, pos, nose, size);

        // Straight along the nose plus a small downward bias, so a nose-up rack lobs
        // the bomb, a nose-down rack drops it, and a side rack sends it out flat and
        // steepening. No upward term at any facing.
        double impulse = WarnauticsConfig.releaseImpulse();
        Vec3 velocity = new Vec3(
                noseVec.x * size.launchAlong * impulse,
                noseVec.y * size.launchAlong * impulse - size.launchDown * impulse,
                noseVec.z * size.launchAlong * impulse);

        // In Sable this local velocity is composed with the carrier's point velocity,
        // so the bomb keeps the aircraft's speed and adds only the arc on top.
        DropBombUtil.spawn(size, level, spawn, velocity, noseVec, null);
        level.playSound(null, pos, SoundEvents.DISPENSER_LAUNCH, SoundSource.BLOCKS, 0.8f, 0.85f);
    }

    /**
     * First candidate direction whose bomb-sized box is actually empty, so the
     * projectile never spawns intersecting the carrier.
     */
    private static Vec3 resolveReleasePoint(ServerLevel level, BlockPos pos, Direction nose, BombSize size) {
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 fallback = null;
        for (Direction dir : releaseOrder(nose)) {
            Vec3 candidate = center.add(
                    dir.getStepX() * clearance(dir, size),
                    dir.getStepY() * clearance(dir, size),
                    dir.getStepZ() * clearance(dir, size));
            if (fallback == null) {
                fallback = candidate;
            }
            double half = size.entitySize * 0.5D;
            AABB box = new AABB(
                    candidate.x - half,
                    candidate.y - half,
                    candidate.z - half,
                    candidate.x + half,
                    candidate.y + half,
                    candidate.z + half);
            if (level.noCollision(box)) {
                return candidate;
            }
        }
        return fallback == null ? center : fallback;
    }

    /**
     * Nose first — that is what makes facing meaningful. The rest only matter when
     * the nose side is walled in, so the bomb leaves through the nearest free side
     * instead of spawning inside the hull.
     */
    private static Direction[] releaseOrder(Direction nose) {
        if (nose == Direction.DOWN) {
            return new Direction[] {Direction.DOWN};
        }
        if (nose == Direction.UP) {
            return new Direction[] {
                Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN
            };
        }
        return new Direction[] {
            nose, Direction.DOWN, nose.getClockWise(), nose.getCounterClockWise(), nose.getOpposite()
        };
    }

    /** Block collision extent toward {@code dir} plus the entity radius and a margin. */
    private static double clearance(Direction dir, BombSize size) {
        var bounds = size.shapeFor(dir.getAxis()).bounds();
        double extentFromCenter =
                switch (dir) {
                    case EAST -> bounds.maxX - 0.5D;
                    case WEST -> 0.5D - bounds.minX;
                    case SOUTH -> bounds.maxZ - 0.5D;
                    case NORTH -> 0.5D - bounds.minZ;
                    case UP -> bounds.maxY - 0.5D;
                    case DOWN -> 0.5D - bounds.minY;
                };
        return extentFromCenter + (size.entitySize * 0.5D) + 0.35D;
    }

    // —— Create wrench ——

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction face = context.getClickedFace();
        BlockState rotated = state.setValue(FACING, state.getValue(FACING).getClockWise(face.getAxis()));
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        level.setBlock(pos, rotated, Block.UPDATE_ALL);
        IWrenchable.playRotateSound(level, pos);
        return InteractionResult.SUCCESS;
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
            ItemStack drop =
                    DropBombItem.withSettings(this.asItem(), state.getValue(CASSETTE), state.getValue(RELEASE_DELAY));
            player.getInventory().placeItemBackInInventory(drop);
        }

        world.destroyBlock(pos, false);
        AllSoundEvents.WRENCH_REMOVE.playOnServer(
                world, pos, 1.0f, world.getRandom().nextFloat() * 0.5f + 0.5f);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void wasExploded(Level level, BlockPos pos, net.minecraft.world.level.Explosion explosion) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // Block#wasExploded is dispatched from Explosion#finalizeExplosion. Warnautics
        // blasts hold the payload guard for that whole call, so a bomb leaving the rack
        // no longer detaches and cooks off the rest of the bay.
        if (!BombSympatheticDetonation.allowsCookoffFrom(explosion)) {
            return;
        }
        BombSympatheticDetonation.scheduleDetachedBombCookoff(serverLevel, pos, this.size, 28, 92);
    }

    /**
     * Instant cook-off while still placed (Sable ship impact / hard world slam).
     * Explosion is spawned in parent-world space (CBC shell pattern) so Sable Destructive
     * can damage the ship without plot-storage vaporize racing heat-map assembly.
     */
    public static void detonateInPlace(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof DropBombBlock bomb)) {
            return;
        }
        BombSize size = bomb.getBombSize();
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        detonateDetached(level, pos, size);
    }

    public static void detonateDetached(ServerLevel level, BlockPos pos, BombSize size) {
        Vec3 local = Vec3.atCenterOf(pos);
        ServerLevel blastLevel = level;
        Vec3 blastPos = CBCCompatTransformers.transformVec3(level, local);
        if (ModList.get().isLoaded("sable")) {
            SableDropCompat.BlastTarget target = SableDropCompat.resolveWorldBlast(level, local);
            blastLevel = target.level();
            blastPos = target.pos();
        }
        DropBombUtil.detonateAsReleasedProjectile(size, blastLevel, blastPos);
    }

    private record ProjectileHitKey(ServerLevel level, long pos) {}

    private record ProjectileHitState(int hits, long lastHitTick) {}
}
