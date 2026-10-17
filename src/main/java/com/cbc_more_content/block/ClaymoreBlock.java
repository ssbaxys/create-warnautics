package com.cbc_more_content.block;

import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.damage.MineDamageSource;
import com.cbc_more_content.effects.MineExplosionHandler;
import com.cbc_more_content.event.TripwireSignal;
import com.cbc_more_content.mine.MineType;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * M18A1 Claymore: a directional antipersonnel mine.
 * <p>
 * It stands on its scissor legs with the convex face — FRONT TOWARD ENEMY — turned the
 * way the person setting it was looking. Fired, it throws its steel balls forward in a
 * sixty-degree fan at about knee-to-chest height; behind it there is only a short
 * back-blast. Like the real thing it has no pressure fuse of its own: it goes off when it
 * is told to, by the firing device (the detonator, paired the same way as a remote
 * charge), by a tripwire tied to it, or by any redstone signal reaching it the way a
 * firing wire would. It arms a few seconds after it is set, so the person laying it can
 * step back behind it.
 */
public class ClaymoreBlock extends HorizontalDirectionalBlock implements ChainExplosiveBlock, IWrenchable {
    public static final MapCodec<ClaymoreBlock> CODEC = simpleCodec(ClaymoreBlock::new);
    public static final BooleanProperty ARMED = BooleanProperty.create("armed");
    /** Time to step back behind it before the blasting cap is live. */
    public static final int ARM_DELAY_TICKS = 60;

    // The body is a curved slab a hand's width thick standing on two legs: thin across its
    // facing, wide along it.
    private static final VoxelShape FACING_NORTH_SOUTH = Block.box(4.0D, 0.0D, 6.5D, 12.0D, 7.0D, 9.5D);
    private static final VoxelShape FACING_EAST_WEST = Block.box(6.5D, 0.0D, 4.0D, 9.5D, 7.0D, 12.0D);

    public ClaymoreBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(
                this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ARMED, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ARMED);
    }

    /** Front toward the enemy: the way the player setting it is looking. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection())
                .setValue(ARMED, false);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    /** The legs go into something firm. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                && level.getBlockState(below).getFluidState().isEmpty();
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighborState,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos) {
        if (direction == Direction.DOWN && !state.canSurvive(level, pos)) {
            level.scheduleTick(pos, this, 1);
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? FACING_NORTH_SOUTH : FACING_EAST_WEST;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide && !state.getValue(ARMED)) {
            level.scheduleTick(pos, this, ARM_DELAY_TICKS);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
            return;
        }
        if (!state.getValue(ARMED)) {
            level.setBlock(pos, state.setValue(ARMED, true), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.TRIPWIRE_ATTACH, SoundSource.BLOCKS, 0.6f, 1.5f);
            // A wire already live when the cap goes in fires it at once.
            if (signalled(level, pos)) {
                detonate(level, pos, level.getBlockState(pos));
            }
        }
    }

    /** A firing wire: any redstone reaching it, or a tripwire post beside it going live. */
    @Override
    protected void neighborChanged(
            BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos neighborPos, boolean moving) {
        super.neighborChanged(state, level, pos, neighbor, neighborPos, moving);
        if (level instanceof ServerLevel server && state.getValue(ARMED) && signalled(level, pos)) {
            detonate(server, pos, state);
        }
    }

    private static boolean signalled(Level level, BlockPos pos) {
        return level.hasNeighborSignal(pos) || TripwireSignal.strength(level, pos) > 0;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return false;
    }

    /** A tripwire tied to the claymore itself has parted. */
    public static void tripped(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ClaymoreBlock && state.getValue(ARMED)) {
            detonate(level, pos, state);
        }
    }

    /** Fired by the detonator. Returns whether there was an armed claymore to fire. */
    public static boolean fireRemote(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ClaymoreBlock) || !state.getValue(ARMED)) {
            return false;
        }
        detonate(level, pos, state);
        return true;
    }

    public static void detonate(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ClaymoreBlock claymore)
                || !level.isLoaded(pos)
                || level.getBlockState(pos).getBlock() != claymore) {
            return;
        }
        // On a ship the charge faces wherever the ship does now, and the balls keep its speed.
        // Captured before the block goes: it may be the last one holding the ship together.
        var frame = aim(level, pos, state);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        claymore.fire(frame.level(), frame.pos(), frame.orientation(), frame.vel());
    }

    /** Where it stands and which way it faces in the world, on a ship or not. */
    private static SableDropCompat.LaunchFrame aim(ServerLevel level, BlockPos pos, BlockState state) {
        Vec3 facing = Vec3.atLowerCornerOf(state.getValue(FACING).getNormal());
        var frame = SableDropCompat.resolveLaunch(level, pos.getCenter(), Vec3.ZERO, facing);
        return frame.orientation() == null
                ? new SableDropCompat.LaunchFrame(frame.level(), frame.pos(), frame.vel(), facing)
                : frame;
    }

    private void fire(ServerLevel level, Vec3 center, Vec3 forward, Vec3 carrierVelocity) {
        MineExplosionHandler.detonateClaymore(
                level, null, MineDamageSource.create(level, MineType.CLAYMORE), center, forward, carrierVelocity);
    }

    /** Caught in another blast with no ship to ask: it goes off facing the way it was set. */
    @Override
    public void detonateCharge(ServerLevel level, Vec3 worldCenter, BlockState state) {
        this.fire(
                level, worldCenter, Vec3.atLowerCornerOf(state.getValue(FACING).getNormal()), Vec3.ZERO);
    }

    /**
     * Caught in another blast. Unlike a round charge it has a front, so on a ship the facing
     * is read off the ship while the ship is still there, the same way a fired one does.
     */
    @Override
    public void onBlockExploded(
            BlockState state, Level level, BlockPos pos, net.minecraft.world.level.Explosion explosion) {
        if (!(level instanceof ServerLevel server)
                || explosion.getBlockInteraction() == net.minecraft.world.level.Explosion.BlockInteraction.KEEP
                || explosion.getBlockInteraction() == net.minecraft.world.level.Explosion.BlockInteraction.TRIGGER_BLOCK
                || !level.getBlockState(pos).is(this)) {
            return;
        }
        var frame = aim(server, pos, state);
        level.setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        com.cbc_more_content.effects.BombSympatheticDetonation.scheduleDestroyedCharge(
                frame.level(),
                frame.pos(),
                () -> this.fire(frame.level(), frame.pos(), frame.orientation(), frame.vel()));
        level.getBlockState(pos).updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
        level.updateNeighborsAt(pos, this);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    /** Sneak with a wrench to take it up again, as with the other mines. */
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
}
