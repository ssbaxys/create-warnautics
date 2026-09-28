package com.cbc_more_content.block;

import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A sealed floating charge, assembled into a single-block Sable body after placement. */
public class SeaMineBlock extends BaseEntityBlock implements SimpleWaterloggedBlock, ChainExplosiveBlock {
    @Override
    public void detonateCharge(ServerLevel level, net.minecraft.world.phys.Vec3 worldCenter, BlockState state) {
        var type = com.cbc_more_content.mine.MineType.SEA;
        com.cbc_more_content.effects.BombExplosionHandler.detonateSeaMine(
                level,
                null,
                com.cbc_more_content.damage.MineDamageSource.create(level, type),
                worldCenter,
                type.blockBlastPower,
                type.entityBlastPower);
    }

    public static final MapCodec<SeaMineBlock> CODEC = simpleCodec(SeaMineBlock::new);
    public static final IntegerProperty OXIDATION = IntegerProperty.create("oxidation", 0, 3);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private static final VoxelShape SHAPE = Block.box(1, 1, 1, 15, 15, 15);

    public SeaMineBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(
                this.stateDefinition.any().setValue(OXIDATION, 0).setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OXIDATION, WATERLOGGED);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return context.getLevel().getFluidState(context.getClickedPos()).is(FluidTags.WATER)
                ? this.defaultBlockState().setValue(WATERLOGGED, true)
                : null;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SeaMineBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide
                ? null
                : createTickerHelper(type, ModBlockEntities.SEA_MINE.get(), SeaMineBlockEntity::tick);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide
                && level instanceof ServerLevel server
                && state.getValue(WATERLOGGED)
                && !SableDropCompat.isInsideSubLevel(level, pos)) {
            server.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (SableDropCompat.isInsideSubLevel(level, pos)) {
            return;
        }
        if (!state.is(this) || !state.getValue(WATERLOGGED)) {
            return;
        }
        var host = SubLevelAssemblyHelper.assembleBlocks(level, pos, List.of(pos), new BoundingBox3i(pos, pos));
        BlockPos localPos = host.getPlot().getCenterBlock();
        BlockState localState = level.getBlockState(localPos);
        if (localState.is(this)) {
            level.setBlock(localPos, localState.setValue(WATERLOGGED, false), Block.UPDATE_ALL);
        }
        // Assembly leaves air behind. Restore the displaced water in the world, not inside the storage plot.
        if (level.getBlockState(pos).isAir()) {
            level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
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
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }
}
