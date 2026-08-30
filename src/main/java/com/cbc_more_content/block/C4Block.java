package com.cbc_more_content.block;

import com.cbc_more_content.munitions.C4Projectile;
import com.cbc_more_content.registry.ModBlockEntities;
import com.cbc_more_content.registry.ModItems;
import com.mojang.serialization.MapCodec;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A C4 charge stuck to a surface. {@link #FACING} points away from the face it clings to,
 * so the charge lies flat against walls, floors and ceilings alike.
 */
public class C4Block extends BaseEntityBlock {
    public static final MapCodec<C4Block> CODEC = simpleCodec(C4Block::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final EnumProperty<Fuse> STATE = EnumProperty.create("state", Fuse.class);
    /**
     * Quarter turns about the face it is stuck to, so a charge lands pointing at whoever
     * threw it. Only floors and ceilings use it: a blockstate can express the turn as a
     * y rotation there, but on a wall it would be a roll about a horizontal axis, which
     * blockstate JSON cannot represent at all.
     */
    public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, 3);
    /**
     * Whether the aerial is fitted. Set from the charge's trigger mode, and in the block
     * state rather than only in the block entity because it is what picks the model.
     */
    public static final BooleanProperty RECEIVER = BooleanProperty.create("receiver");

    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    /**
     * The charge as it sits on a floor, taken from the authored model: casing slab,
     * screen cage above it, and the detonator torch. Everything else in the model is a
     * zero-thickness plane and gets no collision.
     */
    private static final double[][] PARTS_FLAT = {
        {3.0D, 0.0D, 2.0D, 13.0D, 3.0D, 14.0D},
        {3.8D, 1.8D, 3.8D, 12.2D, 6.2D, 11.2D},
        {1.0D, 0.0D, 10.0D, 4.0D, 11.0D, 13.0D},
    };
    /**
     * The same charge on a wall. Casing and cage sit where they do on the floor, but the
     * authored wall model stands its torch off to the side and runs it past the edge of
     * the block, so that part is its own box and is clipped back to the cell.
     */
    private static final double[][] PARTS_WALL = {
        {3.0D, 0.0D, 2.0D, 13.0D, 3.0D, 14.0D},
        {3.8D, 1.8D, 3.8D, 12.2D, 6.2D, 11.2D},
        {12.0D, 1.0D, 9.0D, 15.0D, 4.0D, 16.0D},
    };

    private static final Map<Long, VoxelShape> SHAPES = buildShapes();

    public C4Block(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition
                .any()
                .setValue(FACING, Direction.UP)
                .setValue(STATE, Fuse.IDLE)
                .setValue(RECEIVER, false)
                .setValue(WATERLOGGED, false)
                .setValue(ROTATION, 0));
    }

    /** Floors and ceilings can be turned; a wall-mounted charge keeps one orientation. */
    public static int usableRotation(Direction facing, int rotation) {
        return facing.getAxis().isVertical() ? Mth.clamp(rotation, 0, 3) : 0;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, STATE, RECEIVER, WATERLOGGED, ROTATION);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new C4BlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || state.getValue(STATE) == Fuse.IDLE) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.C4.get(), C4BlockEntity::serverTick);
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
        if (direction == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)) {
            level.scheduleTick(pos, this, 1);
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(shapeKey(state.getValue(FACING), state.getValue(ROTATION)));
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.getShape(state, level, pos, context);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(ModItems.C4.get());
    }

    /**
     * Hitting a live charge sets it off partway through the break. Prising the detonator
     * out of ticking plastic explosive is not a way to make it safe, and letting it be
     * mined away cleanly removed every reason to care about the timer.
     */
    @Override
    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
        if (level.isClientSide
                || state.getValue(STATE) == Fuse.IDLE
                || !(level.getBlockEntity(pos) instanceof C4BlockEntity c4)) {
            return;
        }
        float perTick = state.getDestroyProgress(player, level, pos);
        int breakTicks = perTick <= 0.0f ? 40 : Mth.ceil(1.0f / perTick);
        c4.beginTamper(Math.max(2, breakTicks / 2));
    }

    /** A live charge broken outright — in creative, say — goes off rather than dropping. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && state.getValue(STATE) != Fuse.IDLE && level instanceof ServerLevel server) {
            level.removeBlock(pos, false);
            C4BlockEntity.explode(server, Vec3.atCenterOf(pos));
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * No drop from a charge that just detonated. The return value of
     * {@link #playerWillDestroy} is discarded by the server game mode, so suppressing the
     * loot has to happen here or breaking a live charge would hand back a free one.
     */
    @Override
    public void playerDestroy(
            Level level,
            Player player,
            BlockPos pos,
            BlockState state,
            @Nullable BlockEntity blockEntity,
            ItemStack tool) {
        if (state.getValue(STATE) != Fuse.IDLE) {
            return;
        }
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
    }

    /** Falls off when the surface it is stuck to disappears. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction face = state.getValue(FACING);
        BlockPos support = pos.relative(face.getOpposite());
        BlockState behind = level.getBlockState(support);
        if (behind.isFaceSturdy(level, support, face)) {
            return true;
        }
        // Stairs, slabs, beds and the rest have no sturdy face to offer, but there is
        // plenty of block there to press a charge against. The model is allowed to sit
        // a little way into the surface rather than refuse the placement outright.
        return !behind.isAir()
                && !behind.canBeReplaced()
                && !behind.getCollisionShape(level, support).isEmpty();
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.canSurvive(level, pos)) {
            C4Projectile.dislodge(level, pos, state);
        }
    }

    // —— shapes ——

    private static long shapeKey(Direction facing, int rotation) {
        return facing.ordinal() * 4L + rotation;
    }

    /**
     * Rotates the authored parts into every facing and turn using the same transform
     * {@code blockstates/c4.json} applies, so outline, collision and model agree.
     */
    private static Map<Long, VoxelShape> buildShapes() {
        Map<Long, VoxelShape> shapes = new HashMap<>();
        for (Direction facing : Direction.values()) {
            double[][] parts = facing.getAxis().isVertical() ? PARTS_FLAT : PARTS_WALL;
            for (int rotation = 0; rotation < 4; rotation++) {
                VoxelShape shape = Shapes.empty();
                for (double[] part : parts) {
                    double[] a = place(facing, rotation, part[0], part[1], part[2]);
                    double[] b = place(facing, rotation, part[3], part[4], part[5]);
                    shape = Shapes.or(
                            shape,
                            Block.box(
                                    clip(Math.min(a[0], b[0])), clip(Math.min(a[1], b[1])),
                                    clip(Math.min(a[2], b[2])), clip(Math.max(a[0], b[0])),
                                    clip(Math.max(a[1], b[1])), clip(Math.max(a[2], b[2]))));
                }
                shapes.put(shapeKey(facing, rotation), shape);
            }
        }
        return shapes;
    }

    /** Model geometry may run past the cell; a collision box may not. */
    private static double clip(double value) {
        return Mth.clamp(value, 0.0D, 16.0D);
    }

    /** Facing tilt first, then the quarter turns — the order the blockstate composes in. */
    private static double[] place(Direction facing, int rotation, double x, double y, double z) {
        double[] p = tilt(facing, x, y, z);
        for (int i = 0; i < usableRotation(facing, rotation); i++) {
            // One blockstate y=90 step: (x, z) -> (16 - z, x).
            p = new double[] {16.0D - p[2], p[1], p[0]};
        }
        return p;
    }

    private static double[] tilt(Direction facing, double x, double y, double z) {
        return switch (facing) {
            case UP -> new double[] {x, y, z};
            case DOWN -> new double[] {x, 16.0D - y, 16.0D - z};
            case NORTH -> new double[] {x, z, 16.0D - y};
            case SOUTH -> new double[] {16.0D - x, z, y};
            case WEST -> new double[] {16.0D - y, z, 16.0D - x};
            case EAST -> new double[] {y, z, x};
        };
    }

    /** Screen and detonator lamp. The last two alternate while the fuse ticks. */
    public enum Fuse implements StringRepresentable {
        /** Nothing lit — the charge is placed but not armed. */
        IDLE("idle"),
        /** Detonator lamp lit, screen dark. */
        ARMED("armed"),
        /** Lamp and screen both lit; the peak of each countdown blink. */
        LIT("lit");

        private final String name;

        Fuse(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}
