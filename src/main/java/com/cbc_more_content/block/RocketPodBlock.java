package com.cbc_more_content.block;

import com.cbc_more_content.registry.ModBlockEntities;
import com.cbc_more_content.registry.ModItems;
import com.cbc_more_content.ui.Notices;
import com.cbc_more_content.ui.UiTone;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A rocket pod in the manner of the B-8, built up one section at a time.
 * <p>
 * Each section is set down on its own, its tubes facing the way the player looks. Three
 * sections in a line, all facing the same way, join into a pod: the one furthest along the
 * line becomes the front with the open tubes, the next the body that holds the rockets and
 * the firing circuit, the last the rear. Take any section away and the rest fall back to
 * loose sections, the rockets in the body coming out beside them.
 * <p>
 * Loaded by hand or by any item pipe and fired by redstone, a salvo rippling out of the
 * tubes one after another. On a ship or an aircraft it fires the way the hull now points.
 */
public class RocketPodBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<RocketPodBlock> CODEC = simpleCodec(RocketPodBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public RocketPodBlock(Properties properties) {
        super(properties);
        registerDefaultState(
                stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.SINGLE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** A loose section, its tubes facing where the player is looking. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getNearestLookingDirection())
                .setValue(PART, Part.SINGLE);
    }

    @Override
    public void setPlacedBy(
            Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (level instanceof ServerLevel server && tryForm(server, pos) && placer instanceof Player player) {
            Notices.send(player, UiTone.SUCCESS, "message.cbc_more_content.rocket_pod.assembled");
        }
    }

    public static boolean formed(BlockState state) {
        return state.getValue(PART) != Part.SINGLE;
    }

    /** The middle block of a formed pod; a loose section is its own. */
    public static BlockPos bodyOf(BlockState state, BlockPos pos) {
        return pos.relative(state.getValue(FACING), -state.getValue(PART).offset);
    }

    /** The front cell, the one with the tubes. */
    public static BlockPos frontOf(BlockState state, BlockPos pos) {
        return bodyOf(state, pos).relative(state.getValue(FACING), Part.FRONT.offset);
    }

    private static boolean loose(BlockState state, Direction facing) {
        return state.getBlock() instanceof RocketPodBlock
                && state.getValue(PART) == Part.SINGLE
                && state.getValue(FACING) == facing;
    }

    /**
     * Joins the loose section at {@code pos} with two more lined up with it, if there are.
     * Returns whether a pod was formed.
     */
    public static boolean tryForm(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof RocketPodBlock) || formed(state)) {
            return false;
        }
        Direction facing = state.getValue(FACING);
        // The three windows along the line that contain this section; the first that is
        // all loose sections facing the same way becomes the pod.
        for (int rear = 0; rear >= -2; rear--) {
            BlockPos first = pos.relative(facing, rear);
            if (loose(level.getBlockState(first), facing)
                    && loose(level.getBlockState(first.relative(facing, 1)), facing)
                    && loose(level.getBlockState(first.relative(facing, 2)), facing)) {
                BlockPos body = first.relative(facing, 1);
                for (Part part : new Part[] {Part.REAR, Part.BODY, Part.FRONT}) {
                    BlockPos cell = body.relative(facing, part.offset);
                    level.setBlock(cell, state.setValue(PART, part), Block.UPDATE_ALL);
                }
                level.playSound(null, body, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.0f, 0.7f);
                level.playSound(null, body, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.35f, 1.6f);
                return true;
            }
        }
        return false;
    }

    private static boolean belongs(BlockState state, BlockPos pos, BlockPos body, Direction facing) {
        return state.getBlock() instanceof RocketPodBlock
                && formed(state)
                && state.getValue(FACING) == facing
                && bodyOf(state, pos).equals(body);
    }

    /** All the cells of the pod this one belongs to that still stand; a loose section alone. */
    public static List<BlockPos> partsOf(Level level, BlockPos pos, BlockState state) {
        var result = new ArrayList<BlockPos>(3);
        if (!formed(state)) {
            result.add(pos);
            return result;
        }
        var body = bodyOf(state, pos);
        Direction facing = state.getValue(FACING);
        for (Part part : new Part[] {Part.REAR, Part.BODY, Part.FRONT}) {
            var cell = body.relative(facing, part.offset);
            if (belongs(level.getBlockState(cell), cell, body, facing)) {
                result.add(cell);
            }
        }
        return result;
    }

    /**
     * Breaks a pod back into loose sections, the rockets in its body coming out at the body.
     * {@code except} is a cell already on its way out.
     */
    private static void dissolve(Level level, BlockPos body, Direction facing, @Nullable BlockPos except) {
        if (level.getBlockEntity(body) instanceof RocketPodBlockEntity pod && pod.rockets() > 0) {
            Containers.dropItemStack(
                    level,
                    body.getX() + .5,
                    body.getY() + .5,
                    body.getZ() + .5,
                    new ItemStack(ModItems.S8_ROCKET.get(), pod.unloadAll()));
        }
        var loosened = new ArrayList<BlockPos>(3);
        for (Part part : new Part[] {Part.REAR, Part.BODY, Part.FRONT}) {
            BlockPos cell = body.relative(facing, part.offset);
            BlockState current = level.getBlockState(cell);
            if (!cell.equals(except) && belongs(current, cell, body, facing)) {
                level.setBlock(cell, current.setValue(PART, Part.SINGLE), Block.UPDATE_ALL);
                loosened.add(cell);
            }
        }
        if (!loosened.isEmpty()) {
            level.playSound(null, body, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 0.8f, 0.8f);
        }
    }

    /** A section of a pod is gone: the others are loose sections again. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && !moved && formed(state) && !newState.is(this)) {
            dissolve(level, bodyOf(state, pos), state.getValue(FACING), pos);
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    /** A redstone signal on any of the three blocks is the firing button. */
    @Override
    protected void neighborChanged(
            BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos neighborPos, boolean moving) {
        super.neighborChanged(state, level, pos, neighbor, neighborPos, moving);
        if (level instanceof ServerLevel server
                && formed(state)
                && server.getBlockEntity(bodyOf(state, pos)) instanceof RocketPodBlockEntity pod) {
            pod.checkSignal();
        }
    }

    @Nullable
    private static RocketPodBlockEntity podAt(Level level, BlockState state, BlockPos pos) {
        return formed(state) && level.getBlockEntity(bodyOf(state, pos)) instanceof RocketPodBlockEntity pod
                ? pod
                : null;
    }

    /** Rockets load one per click, or as many as fit while sneaking. */
    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit) {
        if (!stack.is(ModItems.S8_ROCKET.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        var pod = podAt(level, state, pos);
        if (pod == null) {
            Notices.send(player, UiTone.MUTED, "message.cbc_more_content.rocket_pod.unassembled");
            return ItemInteractionResult.CONSUME;
        }
        int wanted = player.isShiftKeyDown() ? stack.getCount() : 1;
        int loaded = pod.load(wanted);
        if (loaded == 0) {
            Notices.send(player, UiTone.MUTED, "message.cbc_more_content.rocket_pod.full", pod.rockets());
            return ItemInteractionResult.CONSUME;
        }
        if (!player.isCreative()) {
            stack.shrink(loaded);
        }
        level.playSound(null, pos, SoundEvents.CROSSBOW_LOADING_END.value(), SoundSource.BLOCKS, 0.8f, 0.8f);
        Notices.send(
                player,
                UiTone.SUCCESS,
                "message.cbc_more_content.rocket_pod.loaded",
                pod.rockets(),
                com.cbc_more_content.config.WarnauticsConfig.rocketPodCapacity());
        return ItemInteractionResult.CONSUME;
    }

    /** Empty hand: how many are loaded. Sneaking with an empty hand unloads them. */
    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        var pod = podAt(level, state, pos);
        if (pod == null) {
            Notices.send(player, UiTone.MUTED, "message.cbc_more_content.rocket_pod.unassembled");
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown() && pod.rockets() > 0) {
            int count = pod.unloadAll();
            player.getInventory().placeItemBackInInventory(new ItemStack(ModItems.S8_ROCKET.get(), count));
            Notices.send(player, UiTone.MUTED, "message.cbc_more_content.rocket_pod.unloaded", count);
            return InteractionResult.CONSUME;
        }
        Notices.send(
                player,
                pod.rockets() > 0 ? UiTone.INFO : UiTone.MUTED,
                pod.rockets() > 0
                        ? "message.cbc_more_content.rocket_pod.status"
                        : "message.cbc_more_content.rocket_pod.empty",
                pod.rockets(),
                com.cbc_more_content.config.WarnauticsConfig.rocketPodCapacity());
        return InteractionResult.CONSUME;
    }

    /**
     * Wrench: turns a section. Turning one that is part of a pod takes the pod apart first,
     * and a turned section may join up with others it now lines up with.
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        var level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        var pos = context.getClickedPos();
        if (formed(state)) {
            dissolve(level, bodyOf(state, pos), state.getValue(FACING), null);
            state = level.getBlockState(pos);
        }
        var rotated = getRotatedBlockState(state, context.getClickedFace());
        level.setBlock(pos, rotated, Block.UPDATE_ALL);
        IWrenchable.playRotateSound(level, pos);
        if (level instanceof ServerLevel server) {
            tryForm(server, pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // Every section carries one, so a section that becomes the body already has its
        // magazine; only the body's is ever used.
        return new RocketPodBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || state.getValue(PART) != Part.BODY
                ? null
                : createTickerHelper(type, ModBlockEntities.ROCKET_POD.get(), RocketPodBlockEntity::serverTick);
    }

    public enum Part implements StringRepresentable {
        /** A section on its own, not (yet) part of a pod. */
        SINGLE(0),
        FRONT(1),
        BODY(0),
        REAR(-1);
        public final int offset;

        Part(int offset) {
            this.offset = offset;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
