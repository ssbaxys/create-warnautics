package com.cbc_more_content.block;

import com.cbc_more_content.block.CruiseMissileBlockEntity.Guidance;
import com.mojang.serialization.MapCodec;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Three-block cruise missile airframe. Inert for now: it places, breaks and drops, and
 * has no warhead, guidance or launch behaviour.
 * <p>
 * The authored model is three blocks long along its own X axis, so only the middle
 * segment draws it; the nose and tail segments are invisible and exist to carry the
 * collision box and to keep the three cells bound together.
 */
public class CruiseMissileBlock extends BaseEntityBlock {
    public static final MapCodec<CruiseMissileBlock> CODEC = simpleCodec(CruiseMissileBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty WATERLOGGED =
            BlockStateProperties.WATERLOGGED;

    /**
     * Slices of the model, measured from it, for a missile whose nose points WEST.
     * <p>
     * The fuselage sits at y 4.5–11.5 throughout. The nose cell is bare tube; the middle
     * and tail cells also carry the wings and fins, which are flat planes at y 8 running
     * the full depth of their cell.
     */
    private static final VoxelShape NOSE_WEST = Block.box(0.0D, 4.5D, 4.5D, 16.0D, 11.5D, 11.5D);

    private static final VoxelShape BODY_WEST = Block.box(0.0D, 4.5D, 0.0D, 16.0D, 11.5D, 16.0D);
    private static final VoxelShape TAIL_WEST = Block.box(0.0D, 4.5D, 0.0D, 16.0D, 11.5D, 16.0D);

    public CruiseMissileBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition
                .any()
                .setValue(FACING, Direction.WEST)
                .setValue(PART, Part.BODY)
                .setValue(WATERLOGGED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, WATERLOGGED);
    }

    /**
     * {@link #FACING} is the way the nose points, and it may be vertical: looking at the
     * ground stands the missile on its tail, looking up hangs it nose-down.
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction nose = noseFor(context);

        // All three cells have to be free, or the missile would place half-formed.
        for (Direction axis : new Direction[] {nose, nose.getOpposite()}) {
            // Straddling the clicked cell, for a click into open space.
            if (canOccupy(level, pos.relative(axis)) && canOccupy(level, pos.relative(axis.getOpposite()))) {
                return this.defaultBlockState().setValue(FACING, axis).setValue(PART, Part.BODY);
            }
            // Standing out of the clicked cell, tail first. Clicking the top of a block
            // puts the cell behind the body inside that block, so an upright missile could
            // never be placed at all — which is why it only ever went up while sneaking,
            // where the airframe was laid flat into open air instead.
            if (canOccupy(level, pos.relative(axis)) && canOccupy(level, pos.relative(axis, 2))) {
                return this.defaultBlockState().setValue(FACING, axis).setValue(PART, Part.TAIL);
            }
        }
        return null;
    }

    /**
     * Which way the nose points.
     * <p>
     * Read off the clicked face rather than off where the player happens to be looking:
     * the old rule took the nearest looking direction and reversed it, which pointed the
     * nose back at the player on a level click and flipped between upright and flat on a
     * few degrees of pitch. Clicking the ground stands the missile up, clicking a wall
     * sends it out of that wall, and sneaking lays it flat along the way the player faces.
     */
    private static Direction noseFor(BlockPlaceContext context) {
        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
            return context.getHorizontalDirection();
        }
        return context.getClickedFace();
    }

    private static boolean canOccupy(LevelReader level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getBlockState(pos).canBeReplaced();
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighborState,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        if (state.getValue(PART) != Part.BODY && bodyPos(pos, state).equals(neighborPos) && !neighborState.is(this)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public void setPlacedBy(
            Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        Direction nose = state.getValue(FACING);
        BlockPos body = bodyPos(pos, state);
        boolean waterlogged = level.getFluidState(body).is(FluidTags.WATER);
        // The middle goes down first. Every other cell deletes itself the moment it finds
        // no body beside it, so writing an end before the middle exists would wipe it out
        // on the very update that placed it.
        BlockState placed = state.setValue(WATERLOGGED, waterlogged);
        level.setBlock(body, placed.setValue(PART, Part.BODY), Block.UPDATE_ALL);
        level.setBlock(body.relative(nose), placed.setValue(PART, Part.NOSE), Block.UPDATE_ALL);
        level.setBlock(body.relative(nose.getOpposite()), placed.setValue(PART, Part.TAIL), Block.UPDATE_ALL);
    }

    /** Position of the middle segment, whichever part was clicked. */
    public static BlockPos bodyOf(BlockState state, BlockPos pos) {
        return bodyPos(pos, state);
    }

    /** Position of the middle segment, whichever part this is. */
    private static BlockPos bodyPos(BlockPos pos, BlockState state) {
        Direction nose = state.getValue(FACING);
        return switch (state.getValue(PART)) {
            case NOSE -> pos.relative(nose.getOpposite());
            case TAIL -> pos.relative(nose);
            case BODY -> pos;
        };
    }

    /**
     * Any segment losing its middle takes the whole airframe with it, so breaking one
     * cell cannot leave floating fragments behind.
     */
    /**
     * Breaking any segment yields exactly one missile.
     * <p>
     * Only the middle segment carries a drop — its loot table is gated on
     * {@code part=body} — so hitting a nose or tail destroys the middle properly rather
     * than clearing it silently. Previously the middle was removed with a raw setBlock
     * while the struck segment also ran its own loot table, which handed out two missiles
     * for one.
     */
    @Override
    public BlockState playerWillDestroy(
            Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.player.Player player) {
        if (!level.isClientSide && state.getValue(PART) != Part.BODY) {
            BlockPos body = bodyPos(pos, state);
            if (level.getBlockState(body).is(this)) {
                level.destroyBlock(body, !player.isCreative(), player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape base =
                switch (state.getValue(PART)) {
                    case NOSE -> NOSE_WEST;
                    case BODY -> BODY_WEST;
                    case TAIL -> TAIL_WEST;
                };
        return rotateFromWest(base, state.getValue(FACING));
    }

    /**
     * Rotates a WEST-nosed shape onto any facing, vertical included. Works in the 0..1
     * space {@code VoxelShape} uses, so the maps are the block-space ones halved.
     */
    private static VoxelShape rotateFromWest(VoxelShape shape, Direction facing) {
        if (facing == Direction.WEST) {
            return shape;
        }
        VoxelShape result = Shapes.empty();
        for (var box : shape.toAabbs()) {
            double[] a = corner(facing, box.minX, box.minY, box.minZ);
            double[] b = corner(facing, box.maxX, box.maxY, box.maxZ);
            result = Shapes.or(
                    result,
                    Shapes.box(
                            Math.min(a[0], b[0]),
                            Math.min(a[1], b[1]),
                            Math.min(a[2], b[2]),
                            Math.max(a[0], b[0]),
                            Math.max(a[1], b[1]),
                            Math.max(a[2], b[2])));
        }
        return result;
    }

    private static double[] corner(Direction facing, double x, double y, double z) {
        return switch (facing) {
            case WEST -> new double[] {x, y, z};
            case NORTH -> new double[] {1.0D - z, y, x};
            case EAST -> new double[] {1.0D - x, y, 1.0D - z};
            case SOUTH -> new double[] {z, y, 1.0D - x};
            case UP -> new double[] {y, 1.0D - x, z};
            case DOWN -> new double[] {1.0D - y, x, z};
        };
    }

    /**
     * Any segment picking up a signal launches the whole airframe. The check runs on a
     * scheduled tick so that a redstone line built around the missile settles first.
     */
    @Override
    protected void neighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block neighborBlock,
            BlockPos neighborPos,
            boolean movedByPiston) {
        if (!level.isClientSide && level.hasNeighborSignal(pos)) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(
            BlockState state,
            net.minecraft.server.level.ServerLevel level,
            BlockPos pos,
            net.minecraft.util.RandomSource random) {
        if (level.hasNeighborSignal(pos)) {
            launch(level, pos, state);
        }
    }

    /** Clears all three cells and puts a missile entity in their place. */
    public static void launch(net.minecraft.server.level.ServerLevel level, BlockPos pos, BlockState state) {
        BlockPos body = bodyPos(pos, state);
        if (level.getFluidState(body).is(FluidTags.WATER)) {
            return;
        }
        BlockState bodyState = level.getBlockState(body);
        if (!bodyState.is(state.getBlock())) {
            return;
        }
        Direction nose = bodyState.getValue(FACING);

        // Read the flight plan first. Clearing the cells destroys the block entity that
        // holds it, so doing this afterwards left every missile unguided no matter what
        // it had been told.
        Guidance mode = Guidance.NONE;
        BlockPos aim = null;
        int lock = -1;
        BlockPos radar = null;
        if (level.getBlockEntity(body) instanceof CruiseMissileBlockEntity guidance) {
            mode = guidance.guidance();
            aim = guidance.target();
            lock = guidance.lockedSubLevel();
            radar = guidance.controller();
        }

        // Remove the airframe before spawning, so the missile cannot collide with the
        // rack it just left.
        for (BlockPos cell : new BlockPos[] {body.relative(nose), body, body.relative(nose.getOpposite())}) {
            if (level.getBlockState(cell).is(state.getBlock())) {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }

        var missile = com.cbc_more_content.registry.ModEntityTypes.CRUISE_MISSILE
                .get()
                .create(level);
        if (missile == null) {
            return;
        }
        var centre = net.minecraft.world.phys.Vec3.atCenterOf(body);
        var heading = new net.minecraft.world.phys.Vec3(nose.getStepX(), nose.getStepY(), nose.getStepZ());
        var spawnLevel = level;

        // A rack on a Sable hull points along the hull's own axes, so both the spawn
        // point and the heading are mapped into world space before launch. Without this
        // a missile on a pitched deck flies flat instead of along the rail it left.
        if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
            var frame = com.cbc_more_content.compat.SableDropCompat.resolveLaunch(level, centre, heading, heading);
            spawnLevel = frame.level();
            centre = frame.pos();
            heading = frame.orientation() == null ? frame.vel() : frame.orientation();
        }

        missile.setGuidance(mode, aim, lock);
        missile.setController(radar);
        missile.setPos(centre);
        if (nose == Direction.UP) {
            // Cold launch. A rack standing on end throws the airframe clear on gas and
            // lights the motor well above whatever it was standing in.
            missile.ejectUpward();
        } else {
            missile.launch(heading);
        }
        spawnLevel.addFreshEntity(missile);

        level.playSound(
                null,
                body,
                com.cbc_more_content.registry.ModSounds.CRUISE_MISSILE_LAUNCH.get(),
                net.minecraft.sounds.SoundSource.BLOCKS,
                4.0f,
                1.0f);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.BODY ? new CruiseMissileBlockEntity(pos, state) : null;
    }

    /** Only an intercept round has anything to do while it sits on the rack. */
    @Nullable
    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (level.isClientSide || state.getValue(PART) != Part.BODY) {
            return null;
        }
        return createTickerHelper(
                type,
                com.cbc_more_content.registry.ModBlockEntities.CRUISE_MISSILE.get(),
                CruiseMissileBlockEntity::serverTick);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // Only the middle segment draws; the model already covers all three cells.
        return state.getValue(PART) == Part.BODY ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    public enum Part implements StringRepresentable {
        NOSE("nose"),
        BODY("body"),
        TAIL("tail");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}
