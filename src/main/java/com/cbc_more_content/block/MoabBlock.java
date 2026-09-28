package com.cbc_more_content.block;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.item.DropBombItem;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

public class MoabBlock extends DropBombBlock {
    public static final MapCodec<MoabBlock> CODEC = simpleCodec(props -> new MoabBlock(props, BombSize.MOAB));
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    private static final Map<ShapeRotation, VoxelShape> ROTATED_SHAPES = new ConcurrentHashMap<>();

    private static final VoxelShape SHAPE_NOSE_UP = Shapes.or(
            Block.box(7.75D, 8.0D, 8.7929D, 8.25D, 16.0D, 10.2071D),
            Block.box(7.2929D, 8.0D, 9.25D, 8.7071D, 16.0D, 9.75D),
            Block.box(7.75D, 8.0D, 5.7929D, 8.25D, 16.0D, 7.2071D),
            Block.box(7.2929D, 8.0D, 6.25D, 8.7071D, 16.0D, 6.75D),
            Block.box(7.75D, 0.0D, -2.0D, 8.25D, 2.0D, 18.0D),
            Block.box(5.0D, 0.0D, 5.0D, 11.0D, 10.0D, 11.0D),
            Block.box(5.5D, 8.0D, 5.4D, 10.5D, 12.0D, 10.4D),
            Block.box(4.5D, 0.0D, 4.5D, 11.5D, 8.0D, 11.5D));

    private static final VoxelShape SHAPE_BODY_UP = Shapes.or(
            Block.box(7.75D, 0.0D, -2.0D, 8.25D, 16.0D, 18.0D),
            Block.box(5.0D, 0.0D, 5.0D, 11.0D, 16.0D, 11.0D),
            Block.box(4.5D, 0.0D, 4.5D, 11.5D, 16.0D, 11.5D));

    private static final VoxelShape SHAPE_TAIL_UP = Shapes.or(
            Block.box(10.8D, 0.0D, 5.0D, 11.3D, 10.0D, 11.0D),
            Block.box(4.7D, 0.0D, 5.0D, 5.2D, 10.0D, 11.0D),
            Block.box(5.0D, 0.0D, 10.8D, 11.0D, 10.0D, 11.3D),
            Block.box(5.0D, 0.0D, 4.7D, 11.0D, 10.0D, 5.2D),
            Block.box(7.75D, 13.0D, -2.0D, 8.25D, 16.0D, 18.0D),
            Block.box(5.0D, 0.0D, 5.0D, 11.0D, 16.0D, 11.0D),
            Block.box(4.5D, 12.0D, 4.5D, 11.5D, 16.0D, 11.5D));

    public MoabBlock(Properties properties, BombSize size) {
        super(properties, size);
        this.registerDefaultState(this.defaultBlockState().setValue(PART, Part.BODY));
    }

    @Override
    protected MapCodec<? extends DropBombBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PART);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState base = super.getStateForPlacement(context);
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        CollisionContext collision =
                context.getPlayer() == null ? CollisionContext.empty() : CollisionContext.of(context.getPlayer());
        // Keep the requested direction. Move the centre when a floor, ceiling or
        // wall occupies one end of the centred footprint.
        for (Part anchor : new Part[] {Part.BODY, Part.TAIL, Part.NOSE}) {
            BlockState candidate = base.setValue(PART, anchor);
            BlockPos body = bodyOf(candidate, pos);
            boolean fits = true;
            for (var entry : airframeStates(level, body, candidate).entrySet()) {
                BlockPos cell = entry.getKey();
                if (level.isOutsideBuildHeight(cell)
                        || !level.getBlockState(cell)
                                .canBeReplaced(BlockPlaceContext.at(context, cell, context.getClickedFace()))
                        || !level.isUnobstructed(entry.getValue(), cell, collision)) {
                    fits = false;
                    break;
                }
            }
            if (fits) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    protected Direction placementFacing(BlockPlaceContext context) {
        return context.getPlayer() != null && context.getPlayer().isShiftKeyDown()
                ? context.getHorizontalDirection()
                : context.getClickedFace();
    }

    @Override
    public void setPlacedBy(
            Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        writeAirframe(level, airframeStates(level, bodyOf(state, pos), state));
    }

    public static BlockPos bodyOf(BlockState state, BlockPos pos) {
        Direction nose = state.getValue(FACING);
        return switch (state.getValue(PART)) {
            case NOSE -> pos.relative(nose.getOpposite());
            case TAIL -> pos.relative(nose);
            case BODY -> pos;
        };
    }

    private static Map<BlockPos, BlockState> airframeStates(Level level, BlockPos body, BlockState state) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (Part part : new Part[] {Part.BODY, Part.NOSE, Part.TAIL}) {
            BlockPos cell = body.relative(state.getValue(FACING), part == Part.NOSE ? 1 : part == Part.TAIL ? -1 : 0);
            cells.put(
                    cell,
                    state.setValue(PART, part)
                            .setValue(WATERLOGGED, level.getFluidState(cell).is(FluidTags.WATER))
                            .setValue(POWERED, isReceivingPower(level, cell)));
        }
        return cells;
    }

    private static void writeAirframe(Level level, Map<BlockPos, BlockState> cells) {
        // Complete the structure on both sides before sending neighbour updates.
        cells.forEach((cell, state) -> level.setBlock(cell, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        cells.keySet().forEach(cell -> {
            BlockState state = level.getBlockState(cell);
            state.updateNeighbourShapes(level, cell, Block.UPDATE_ALL);
            level.updateNeighborsAt(cell, state.getBlock());
        });
    }

    private static boolean belongsTo(BlockState state, BlockPos cell, BlockPos body, BlockState bodyState) {
        return state.is(bodyState.getBlock())
                && state.getValue(FACING) == bodyState.getValue(FACING)
                && bodyOf(state, cell).equals(body);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighborState,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos) {
        if (this.getBombSize() == BombSize.SEA) {
            return state;
        }
        if (state.getBlock() == this
                && state.getValue(PART) != Part.BODY
                && neighborPos.equals(bodyOf(state, pos))
                && (!neighborState.is(this.asBlock())
                        || neighborState.getValue(PART) != Part.BODY
                        || neighborState.getValue(FACING) != state.getValue(FACING))) {
            return state.getFluidState().createLegacyBlock();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && state.getValue(PART) != Part.BODY) {
            BlockPos body = bodyOf(state, pos);
            if (level.getBlockState(body).is(this.asBlock())) {
                level.destroyBlock(body, !player.isCreative(), player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape up =
                switch (state.getValue(PART)) {
                    case NOSE -> SHAPE_NOSE_UP;
                    case BODY -> SHAPE_BODY_UP;
                    case TAIL -> SHAPE_TAIL_UP;
                };
        return rotateFromUp(up, state.getValue(FACING));
    }

    private static VoxelShape rotateFromUp(VoxelShape shape, Direction facing) {
        if (facing == Direction.UP) {
            return shape;
        }
        return ROTATED_SHAPES.computeIfAbsent(
                new ShapeRotation(shape, facing), key -> rotateShape(key.shape(), key.facing()));
    }

    private record ShapeRotation(VoxelShape shape, Direction facing) {}

    private static VoxelShape rotateShape(VoxelShape shape, Direction facing) {
        VoxelShape result = Shapes.empty();
        for (var box : shape.toAabbs()) {
            double[] a = corner(facing, box.minX, box.minY, box.minZ);
            double[] b = corner(facing, box.maxX, box.maxY, box.maxZ);
            result = Shapes.joinUnoptimized(
                    result,
                    Shapes.box(
                            Math.min(a[0], b[0]),
                            Math.min(a[1], b[1]),
                            Math.min(a[2], b[2]),
                            Math.max(a[0], b[0]),
                            Math.max(a[1], b[1]),
                            Math.max(a[2], b[2])),
                    BooleanOp.OR);
        }
        // Optimizing after every small box fragments and re-merges the entire shape at each step.
        return result.optimize();
    }

    private static double[] corner(Direction facing, double x, double y, double z) {
        return switch (facing) {
            case UP -> new double[] {x, y, z};
            case DOWN -> new double[] {x, 1.0D - y, 1.0D - z};
            case NORTH -> new double[] {x, z, 1.0D - y};
            case SOUTH -> new double[] {1.0D - x, z, y};
            case EAST -> new double[] {y, z, x};
            case WEST -> new double[] {1.0D - y, z, 1.0D - x};
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // The model covers all three cells; the body draws it, the ends are invisible.
        return state.getValue(PART) == Part.BODY ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    @Override
    protected void ejectOne(ServerLevel level, BlockPos pos, BlockState state) {
        BlockPos body = bodyOf(state, pos);
        var launch = prepareLaunchAlongNose(level, body, state.getValue(FACING), this.getBombSize());
        clearAirframe(level, state, body);
        this.launchPrepared(this.getBombSize(), launch);
    }

    private static void clearAirframe(Level level, BlockState anyCellState, BlockPos body) {
        Direction nose = anyCellState.getValue(FACING);
        for (BlockPos cell : new BlockPos[] {body.relative(nose), body, body.relative(nose.getOpposite())}) {
            BlockState current = level.getBlockState(cell);
            if (belongsTo(current, cell, body, anyCellState)) {
                level.setBlock(
                        cell,
                        current.getFluidState().createLegacyBlock(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    @Override
    public BlockPos explosionAnchor(BlockState state, BlockPos pos) {
        return bodyOf(state, pos);
    }

    @Override
    public java.util.List<BlockPos> explosionParts(BlockState state, BlockPos anchor) {
        Direction facing = state.getValue(FACING);
        return java.util.List.of(anchor, anchor.relative(facing), anchor.relative(facing.getOpposite()));
    }

    @Override
    protected BlockPos detonationAnchor(ServerLevel level, BlockPos pos, BlockState state) {
        BlockPos body = bodyOf(state, pos);
        clearAirframe(level, state, body);
        return body;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level world = context.getLevel();
        if (!(world instanceof ServerLevel)) {
            return InteractionResult.SUCCESS;
        }
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        BlockState target = state;
        BlockPos targetPos = pos;
        if (state.getValue(PART) != Part.BODY) {
            BlockPos body = bodyOf(state, pos);
            BlockState bodyState = world.getBlockState(body);
            if (bodyState.is(this.asBlock())) {
                target = bodyState;
                targetPos = body;
            }
        }
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(world, targetPos, target, player);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return InteractionResult.SUCCESS;
        }
        if (player != null && !player.isCreative()) {
            ItemStack drop =
                    DropBombItem.withSettings(this.asItem(), target.getValue(CASSETTE), target.getValue(RELEASE_DELAY));
            player.getInventory().placeItemBackInInventory(drop);
        }
        world.destroyBlock(targetPos, false);
        AllSoundEvents.WRENCH_REMOVE.playOnServer(
                world, targetPos, 1.0f, world.getRandom().nextFloat() * 0.5f + 0.5f);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockPos body = bodyOf(state, pos);
        BlockState bodyState = level.getBlockState(body);
        if (!bodyState.is(this.asBlock()) || bodyState.getValue(PART) != Part.BODY) {
            return InteractionResult.SUCCESS;
        }
        Direction oldNose = bodyState.getValue(FACING);
        Direction newFacing = oldNose.getClockWise(context.getClickedFace().getAxis());
        if (newFacing == oldNose) {
            return InteractionResult.SUCCESS;
        }
        Map<BlockPos, BlockState> rotated = airframeStates(level, body, bodyState.setValue(FACING, newFacing));
        CollisionContext collision =
                context.getPlayer() == null ? CollisionContext.empty() : CollisionContext.of(context.getPlayer());
        // Validate the entire destination before touching the existing bomb.
        for (var entry : rotated.entrySet()) {
            BlockPos cell = entry.getKey();
            BlockState current = level.getBlockState(cell);
            if (level.isOutsideBuildHeight(cell)
                    || (!belongsTo(current, cell, body, bodyState) && !current.canBeReplaced())
                    || !level.isUnobstructed(entry.getValue(), cell, collision)) {
                return InteractionResult.SUCCESS;
            }
        }
        Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
        for (Direction side : new Direction[] {oldNose, oldNose.getOpposite()}) {
            BlockPos cell = body.relative(side);
            BlockState current = level.getBlockState(cell);
            if (belongsTo(current, cell, body, bodyState)) {
                changes.put(cell, current.getFluidState().createLegacyBlock());
            }
        }
        changes.putAll(rotated);
        writeAirframe(level, changes);
        IWrenchable.playRotateSound(level, pos);
        return InteractionResult.SUCCESS;
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
