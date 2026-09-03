package com.cbc_more_content.block;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.item.DropBombItem;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

public class MoabBlock extends DropBombBlock {
    public static final MapCodec<MoabBlock> CODEC = simpleCodec(props -> new MoabBlock(props, BombSize.MOAB));
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

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
        Direction nose = this.placementFacing(context);
        BlockPos pos = context.getClickedPos();
        if (!canOccupy(context.getLevel(), pos.relative(nose))
                || !canOccupy(context.getLevel(), pos.relative(nose.getOpposite()))) {
            return null;
        }
        return super.getStateForPlacement(context);
    }

    private static boolean canOccupy(LevelReader level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getBlockState(pos).canBeReplaced();
    }

    @Override
    public void setPlacedBy(
            Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) {
            return;
        }
        Direction nose = state.getValue(FACING);
        level.setBlock(pos, state.setValue(PART, Part.BODY), Block.UPDATE_ALL);
        level.setBlock(pos.relative(nose), state.setValue(PART, Part.NOSE), Block.UPDATE_ALL);
        level.setBlock(pos.relative(nose.getOpposite()), state.setValue(PART, Part.TAIL), Block.UPDATE_ALL);
    }

    public static BlockPos bodyOf(BlockState state, BlockPos pos) {
        Direction nose = state.getValue(FACING);
        return switch (state.getValue(PART)) {
            case NOSE -> pos.relative(nose.getOpposite());
            case TAIL -> pos.relative(nose);
            case BODY -> pos;
        };
    }

    private List<BlockPos> airframeCells(BlockState state, BlockPos pos) {
        BlockPos body = bodyOf(state, pos);
        Direction nose = state.getValue(FACING);
        return List.of(body.relative(nose), body, body.relative(nose.getOpposite()));
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
                && !neighborState.is(this.asBlock())) {
            return Blocks.AIR.defaultBlockState();
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
                // x:0: identity.
            case UP -> new double[] {x, y, z};
                // x:180: (x, y, z) -> (x, 16-y, 16-z).
            case DOWN -> new double[] {x, 1.0D - y, 1.0D - z};
                // x:90: (x, y, z) -> (x, z, 16-y); the nose (+Y) lands on north (-Z).
            case NORTH -> new double[] {x, z, 1.0D - y};
                // x:90 then y:180: (x, y, z) -> (16-x, z, y); nose lands on south (+Z).
            case SOUTH -> new double[] {1.0D - x, z, y};
                // x:90 then y:90: (x, y, z) -> (y, z, x); nose lands on east (+X).
            case EAST -> new double[] {y, z, x};
                // x:90 then y:270: (x, y, z) -> (16-y, z, 16-x); nose lands on west (-X).
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
        clearAirframe(level, state, body);
        this.launchAlongNose(level, body, state.getValue(FACING), this.getBombSize());
    }

    private static void clearAirframe(Level level, BlockState anyCellState, BlockPos body) {
        Direction nose = anyCellState.getValue(FACING);
        for (BlockPos cell : new BlockPos[] {body.relative(nose), body, body.relative(nose.getOpposite())}) {
            if (level.getBlockState(cell).is(anyCellState.getBlock())) {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    @Override
    protected void onExplosionHit(
            BlockState state,
            Level level,
            BlockPos pos,
            net.minecraft.world.level.Explosion explosion,
            java.util.function.BiConsumer<ItemStack, BlockPos> dropConsumer) {
        if (this.getBombSize() != BombSize.MOAB || state.getValue(PART) == Part.BODY) {
            super.onExplosionHit(state, level, pos, explosion, dropConsumer);
            return;
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        if (level instanceof ServerLevel serverLevel
                && com.cbc_more_content.effects.BombSympatheticDetonation.allowsCookoffFrom(explosion)) {
            BlockPos body = bodyOf(state, pos);
            if (level.getBlockState(body).is(this.asBlock())) {
                com.cbc_more_content.effects.BombSympatheticDetonation.schedulePlacedBombCookoff(
                        serverLevel, body, 28, 92);
            }
        }
    }

    @Override
    protected BlockPos detonationAnchor(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos body = bodyOf(state, pos);
        if (!body.equals(pos)) {
            clearAirframe(level, state, body);
        }
        return body;
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (this.getBombSize() != BombSize.MOAB || state.getValue(PART) == Part.BODY) {
            super.tick(state, level, pos, random);
            return;
        }
        BlockPos body = bodyOf(state, pos);
        BlockState bodyState = level.getBlockState(body);
        if (bodyState.is(this.asBlock()) && bodyState.getValue(PART) == Part.BODY && isReceivingPower(level, body)) {
            if (!bodyState.getValue(POWERED)) {
                level.setBlock(body, bodyState.setValue(POWERED, true), Block.UPDATE_CLIENTS);
            }
            this.performRackEject(level, body);
        }
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
        Direction face = context.getClickedFace();
        Direction newFacing = state.getValue(FACING).getClockWise(face.getAxis());
        BlockPos body = bodyOf(state, pos);
        BlockState bodyState = level.getBlockState(body);
        if (!bodyState.is(this.asBlock())) {
            return InteractionResult.SUCCESS;
        }
        Direction oldNose = bodyState.getValue(FACING);
        for (Direction side : new Direction[] {oldNose, oldNose.getOpposite()}) {
            BlockPos cell = body.relative(side);
            if (!cell.equals(body) && level.getBlockState(cell).is(this.asBlock())) {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
        BlockState rotated = bodyState.setValue(FACING, newFacing);
        level.setBlock(body, rotated.setValue(PART, Part.BODY), Block.UPDATE_ALL);
        for (Direction side : new Direction[] {newFacing, newFacing.getOpposite()}) {
            BlockPos cell = body.relative(side);
            if (level.getBlockState(cell).canBeReplaced()) {
                level.setBlock(
                        cell, rotated.setValue(PART, side == newFacing ? Part.NOSE : Part.TAIL), Block.UPDATE_ALL);
            }
        }
        IWrenchable.playRotateSound(level, pos);
        return InteractionResult.SUCCESS;
    }

    public enum Part implements net.minecraft.util.StringRepresentable {
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
