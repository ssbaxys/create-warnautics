package com.cbc_more_content.bombsight;

import com.cbc_more_content.registry.ModBlockEntities;
import com.cbc_more_content.util.ReflectiveDispatcher;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A bombsight. Set on an aircraft or a ship near its bomb racks, it shows the crew where a
 * bomb released now would land, and can drop on a target by itself: it gives out a redstone
 * signal, on every side, for exactly as long as the drop should last.
 */
public class BombsightBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<BombsightBlock> CODEC = simpleCodec(BombsightBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** Set for as long as the sight is signalling a release. */
    public static final BooleanProperty RELEASING = BooleanProperty.create("releasing");
    /**
     * What it is fixed to: standing on a floor, hung under a ceiling (or a ship's deck), or
     * on a wall. On a wall, {@link #FACING} is the way it faces out of the wall.
     */
    public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;

    private static final VoxelShape FLOOR_SHAPE = Block.box(5, 0, 5, 11, 2, 11);
    private static final VoxelShape CEILING_SHAPE = Block.box(5, 14, 5, 11, 16, 11);
    private static final VoxelShape WALL_NORTH = Block.box(5, 5, 14, 11, 11, 16);
    private static final VoxelShape WALL_SOUTH = Block.box(5, 5, 0, 11, 11, 2);
    private static final VoxelShape WALL_EAST = Block.box(0, 5, 5, 2, 11, 11);
    private static final VoxelShape WALL_WEST = Block.box(14, 5, 5, 16, 11, 11);

    public BombsightBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition
                .any()
                .setValue(FACING, Direction.NORTH)
                .setValue(RELEASING, false)
                .setValue(FACE, AttachFace.FLOOR));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, RELEASING, FACE);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Fixed to whatever face it was put against, as a button or a lever is.
        Direction face = context.getClickedFace();
        if (face.getAxis().isHorizontal()) {
            return defaultBlockState().setValue(FACE, AttachFace.WALL).setValue(FACING, face);
        }
        return defaultBlockState()
                .setValue(FACE, face == Direction.DOWN ? AttachFace.CEILING : AttachFace.FLOOR)
                .setValue(FACING, context.getHorizontalDirection());
    }

    /** Use it with an empty hand to put your eye to it: the view through the sight. */
    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            ReflectiveDispatcher.invoke(
                    "com.cbc_more_content.client.BombsightView", "enter", new Class<?>[] {BlockPos.class}, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACE)) {
            case FLOOR -> FLOOR_SHAPE;
            case CEILING -> CEILING_SHAPE;
            case WALL -> switch (state.getValue(FACING)) {
                case SOUTH -> WALL_SOUTH;
                case EAST -> WALL_EAST;
                case WEST -> WALL_WEST;
                default -> WALL_NORTH;
            };
        };
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(RELEASING) ? 15 : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(RELEASING) ? 15 : 0;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BombsightBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide
                ? null
                : createTickerHelper(type, ModBlockEntities.BOMBSIGHT.get(), BombsightBlockEntity::serverTick);
    }
}
