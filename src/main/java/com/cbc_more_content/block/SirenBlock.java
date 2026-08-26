package com.cbc_more_content.block;

import javax.annotation.Nullable;

import com.cbc_more_content.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Air-raid siren on a post. Wails on a redstone signal, and on its own for whatever it has
 * been told to watch for; the settings key opens what that is.
 */
public class SirenBlock extends BaseEntityBlock {
    public static final MapCodec<SirenBlock> CODEC = simpleCodec(SirenBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** Lit and sounding. Drives the model, so nothing extra has to be synced for it. */
    public static final BooleanProperty SOUNDING = BooleanProperty.create("sounding");

    private static final VoxelShape POST = Block.box(6.0D, 0.0D, 6.0D, 10.0D, 10.0D, 10.0D);
    private static final VoxelShape HORN = Block.box(3.0D, 9.0D, 3.0D, 13.0D, 16.0D, 13.0D);
    private static final VoxelShape SHAPE = Shapes.or(POST, HORN);

    public SirenBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(SOUNDING, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SOUNDING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SirenBlockEntity(pos, state);
    }

    /**
     * Always ticking, even while silent: the post has to be watching for something to
     * sound at, and a siren that only woke up once already told is no warning at all.
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.SIREN.get(), SirenBlockEntity::serverTick);
    }

    // No neighborChanged override on purpose. It used to call raise() when a signal
    // appeared, which is the sighting path — so throwing a lever started the full linger
    // and the post went on wailing for three quarters of a minute after the line was cut.
    // The post reads the signal for itself every tick, and a signal only ever holds it.
}
