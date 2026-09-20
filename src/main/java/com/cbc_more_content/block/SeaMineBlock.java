package com.cbc_more_content.block;

import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * For the record, this is a god awful way of doing this, but I'm tired and don't care
 */
public class SeaMineBlock extends BaseEntityBlock {
    public static final MapCodec<SeaMineBlock> CODEC = simpleCodec(SeaMineBlock::new);
    private static final VoxelShape SHAPE = Block.box(1, 1, 1, 15, 15, 15);

    public SeaMineBlock(Properties properties) {
        super(properties);
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
        return createTickerHelper(type, ModBlockEntities.SEA_MINE.get(), SeaMineBlockEntity::tick);
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
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit) {
        if (!(level instanceof ServerLevel server) || !isRopeTool(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof SeaMineBlockEntity mine)) {
            return ItemInteractionResult.FAIL;
        }
        if (player.isShiftKeyDown()) {
            mine.unanchor(server);
            player.displayClientMessage(Component.translatable("message.cbc_more_content.sea_mine.rope_cut"), true);
            return ItemInteractionResult.SUCCESS;
        }
        BlockPos connector = storedFirstConnection(stack);
        if (connector == null) {
            connector = SeaMineBlockEntity.findAnchor(level, pos);
        }
        if (connector == null || connector.distToCenterSqr(Vec3.atCenterOf(pos)) > 64.0D * 64.0D) {
            player.displayClientMessage(Component.translatable("message.cbc_more_content.sea_mine.rope_far"), true);
            return ItemInteractionResult.FAIL;
        }
        mine.anchor(server, connector);
        spendRopeTool(stack, player);
        player.displayClientMessage(Component.translatable("message.cbc_more_content.sea_mine.rope_linked"), true);
        return ItemInteractionResult.SUCCESS;
    }

    private static boolean isRopeTool(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return id.equals("simulated:rope_coupling") || id.equals("simulated:rope_connector");
    }

    @Nullable
    private static BlockPos storedFirstConnection(ItemStack stack) {
        for (var type : BuiltInRegistries.DATA_COMPONENT_TYPE) {
            var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getResourceKey(type);
            if (key.isPresent()
                    && key.get().location().getNamespace().equals("simulated")
                    && key.get().location().getPath().equals("rope_first_connection")) {
                Object value = stack.get(type);
                return value instanceof BlockPos pos ? pos : null;
            }
        }
        return null;
    }

    private static void spendRopeTool(ItemStack stack, Player player) {
        for (var type : BuiltInRegistries.DATA_COMPONENT_TYPE) {
            var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getResourceKey(type);
            if (key.isPresent()
                    && key.get().location().getNamespace().equals("simulated")
                    && key.get().location().getPath().equals("rope_first_connection")) {
                stack.remove(type);
                break;
            }
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return Fluids.WATER.getSource(false);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)
                || level instanceof Level actual && SableDropCompat.isInsideSubLevel(actual, pos);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide
                && level instanceof ServerLevel server
                && !SableDropCompat.isInsideSubLevel(level, pos)) {
            server.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (SableDropCompat.isInsideSubLevel(level, pos)) {
            return;
        }
        if (!state.is(this)) {
            return;
        }
        SubLevelAssemblyHelper.assembleBlocks(level, pos, List.of(pos), new BoundingBox3i(pos, pos));
    }
}
