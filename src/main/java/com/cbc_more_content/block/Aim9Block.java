package com.cbc_more_content.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Placeable AIM-9 airframe. Flight and fusing are intentionally not connected yet. */
public class Aim9Block extends BaseEntityBlock implements SimpleWaterloggedBlock, IWrenchable {
    public static final MapCodec<Aim9Block> CODEC = simpleCodec(Aim9Block::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    // Bounds of the supplied model's rotated elements, clipped into its three occupied cells.
    private static final VoxelShape NOSE = Shapes.or(
            Block.box(7.05, 7.05, 4.6, 8.95, 8.95, 16),
            Block.box(7.15, 7.15, 2.4, 8.85, 8.85, 4.6),
            Block.box(7.25, 7.25, 1.4, 8.75, 8.75, 2.4),
            Block.box(5.28153, 8.94929, 6, 7.04929, 10.71706, 9),
            Block.box(8.95071, 8.9493, 6, 10.71848, 10.71706, 9),
            Block.box(5.28223, 5.28224, 6, 7.05, 7.05, 9),
            Block.box(8.95, 5.28223, 6, 10.71776, 7.05, 9));
    private static final VoxelShape BODY = Block.box(7.05, 7.05, 0, 8.95, 8.95, 16);
    private static final VoxelShape TAIL = Shapes.or(
            Block.box(7.05, 7.05, 0, 8.95, 8.95, 14.6),
            Block.box(8.95, 4.57512, 3.9, 11.42487, 7.05, 14.2),
            Block.box(8.95071, 8.9493, 3.9, 11.42559, 11.42417, 14.2),
            Block.box(4.57512, 4.57513, 3.9, 7.05, 7.05, 14.2),
            Block.box(4.57442, 8.94929, 3.9, 7.04929, 11.42417, 14.2));
    private static final VoxelShape[][] SHAPES = new VoxelShape[3][6];

    static {
        for (Part part : Part.values()) {
            for (Direction facing : Direction.values()) {
                VoxelShape source =
                        switch (part) {
                            case NOSE -> NOSE;
                            case BODY -> BODY;
                            case TAIL -> TAIL;
                        };
                VoxelShape result = Shapes.empty();
                for (var box : source.toAabbs()) {
                    double[] a = rotate(box.minX, box.minY, box.minZ, facing);
                    double[] b = rotate(box.maxX, box.maxY, box.maxZ, facing);
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
                SHAPES[part.ordinal()][facing.ordinal()] = result;
            }
        }
    }

    public Aim9Block(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition
                .any()
                .setValue(FACING, Direction.NORTH)
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

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction nose = context.getPlayer() != null && context.getPlayer().isShiftKeyDown()
                ? context.getHorizontalDirection()
                : context.getClickedFace();
        var level = context.getLevel();
        var collision =
                context.getPlayer() == null ? CollisionContext.empty() : CollisionContext.of(context.getPlayer());
        for (Part anchor : new Part[] {Part.BODY, Part.TAIL, Part.NOSE}) {
            var state = defaultBlockState()
                    .setValue(FACING, nose)
                    .setValue(PART, anchor)
                    .setValue(
                            WATERLOGGED,
                            level.getFluidState(context.getClickedPos()).is(FluidTags.WATER));
            var cells = cells(level, bodyOf(state, context.getClickedPos()), state);
            if (cells.entrySet().stream()
                    .allMatch(e -> !level.isOutsideBuildHeight(e.getKey())
                            && level.getBlockState(e.getKey())
                                    .canBeReplaced(BlockPlaceContext.at(context, e.getKey(), context.getClickedFace()))
                            && level.isUnobstructed(e.getValue(), e.getKey(), collision))) {
                return state;
            }
        }
        return null;
    }

    public static BlockPos bodyOf(BlockState state, BlockPos pos) {
        return pos.relative(state.getValue(FACING), -state.getValue(PART).offset);
    }

    private static Map<BlockPos, BlockState> cells(Level level, BlockPos body, BlockState state) {
        var result = new LinkedHashMap<BlockPos, BlockState>();
        for (Part part : new Part[] {Part.BODY, Part.NOSE, Part.TAIL}) {
            var pos = body.relative(state.getValue(FACING), part.offset);
            result.put(
                    pos,
                    state.setValue(PART, part)
                            .setValue(WATERLOGGED, level.getFluidState(pos).is(FluidTags.WATER)));
        }
        return result;
    }

    private static void write(Level level, Map<BlockPos, BlockState> cells) {
        cells.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        cells.keySet().forEach(pos -> {
            var state = level.getBlockState(pos);
            state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
            level.updateNeighborsAt(pos, state.getBlock());
        });
    }

    @Override
    public void setPlacedBy(
            Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        write(level, cells(level, bodyOf(state, pos), state));
    }

    private static boolean belongs(BlockState state, BlockPos pos, BlockPos body, BlockState bodyState) {
        return state.is(bodyState.getBlock())
                && state.getValue(FACING) == bodyState.getValue(FACING)
                && bodyOf(state, pos).equals(body);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighbor,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        if (state.getValue(PART) != Part.BODY
                && bodyOf(state, pos).equals(neighborPos)
                && (!neighbor.is(this)
                        || neighbor.getValue(PART) != Part.BODY
                        || neighbor.getValue(FACING) != state.getValue(FACING))) {
            return state.getFluidState().createLegacyBlock();
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && state.getValue(PART) != Part.BODY) {
            var body = bodyOf(state, pos);
            if (belongs(level.getBlockState(body), body, body, state)) {
                level.destroyBlock(body, !player.isCreative(), player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        var level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        var body = bodyOf(state, context.getClickedPos());
        var bodyState = level.getBlockState(body);
        if (!belongs(bodyState, body, body, state)) {
            return InteractionResult.PASS;
        }
        Direction oldFacing = state.getValue(FACING);
        Direction next = oldFacing.getClockWise(context.getClickedFace().getAxis());
        if (next == oldFacing) {
            return InteractionResult.SUCCESS;
        }
        var destination = cells(level, body, bodyState.setValue(FACING, next));
        var collision =
                context.getPlayer() == null ? CollisionContext.empty() : CollisionContext.of(context.getPlayer());
        for (var entry : destination.entrySet()) {
            var pos = entry.getKey();
            var current = level.getBlockState(pos);
            if (level.isOutsideBuildHeight(pos)
                    || (!belongs(current, pos, body, state) && !current.canBeReplaced())
                    || !level.isUnobstructed(entry.getValue(), pos, collision)) {
                return InteractionResult.SUCCESS;
            }
        }
        var changes = clearCells(level, body, bodyState);
        changes.putAll(destination);
        write(level, changes);
        IWrenchable.playRotateSound(level, context.getClickedPos());
        return InteractionResult.SUCCESS;
    }

    private static Map<BlockPos, BlockState> clearCells(Level level, BlockPos body, BlockState state) {
        var changes = new LinkedHashMap<BlockPos, BlockState>();
        for (Part part : Part.values()) {
            var pos = body.relative(state.getValue(FACING), part.offset);
            var current = level.getBlockState(pos);
            if (belongs(current, pos, body, state)) {
                changes.put(pos, current.getFluidState().createLegacyBlock());
            }
        }
        return changes;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        var level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        var body = bodyOf(state, context.getClickedPos());
        var bodyState = level.getBlockState(body);
        if (!belongs(bodyState, body, body, state)) {
            return InteractionResult.PASS;
        }
        var event = new BlockEvent.BreakEvent(level, body, bodyState, context.getPlayer());
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return InteractionResult.SUCCESS;
        }
        write(level, clearCells(level, body, bodyState));
        if (context.getPlayer() != null && !context.getPlayer().isCreative()) {
            context.getPlayer().getInventory().placeItemBackInInventory(new ItemStack(asItem()));
        }
        com.simibubi.create.AllSoundEvents.WRENCH_REMOVE.playOnServer(level, body, 1, 1);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(PART).ordinal()][state.getValue(FACING).ordinal()];
    }

    private static double[] rotate(double x, double y, double z, Direction facing) {
        return switch (facing) {
            case NORTH -> new double[] {x, y, z};
            case SOUTH -> new double[] {1 - x, y, 1 - z};
            case EAST -> new double[] {1 - z, y, x};
            case WEST -> new double[] {z, y, 1 - x};
            case UP -> new double[] {x, 1 - z, y};
            case DOWN -> new double[] {x, z, 1 - y};
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return state.getValue(PART) == Part.BODY ? RenderShape.ENTITYBLOCK_ANIMATED : RenderShape.INVISIBLE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.BODY ? new Aim9BlockEntity(pos, state) : null;
    }

    public enum Part implements StringRepresentable {
        NOSE(1),
        BODY(0),
        TAIL(-1);
        private final int offset;

        Part(int offset) {
            this.offset = offset;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
