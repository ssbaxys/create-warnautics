package com.cbc_more_content.block;

import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.damage.MineDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.effects.MineExplosionHandler;
import com.cbc_more_content.mine.MineType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import rbasamoyai.createbigcannons.CBCCompatTransformers;

import javax.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flat land mine. Arms after a short delay so the placer is safe.
 * Small = infantry {@link #stepOn}; large = Sable / Offroad wheel (via callbacks).
 */
public class LandMineBlock extends Block implements IWrenchable {
    public static final BooleanProperty ARMED = BooleanProperty.create("armed");
    /** How far the mine has been dug in: 0 sitting proud, {@link #MAX_BURIAL} flush. */
    public static final IntegerProperty BURIAL = IntegerProperty.create("burial", 0, 8);
    public static final int MAX_BURIAL = 8;
    /** Ticks after place before the mine can detonate. */
    public static final int ARM_DELAY_TICKS = 20;
    /**
     * Physics and wheel casts may report the same mine several times per sub-step.
     * Keep at most one server task per mine until that task has run.
     */
    private static final Set<PendingDetonation> PENDING_VEHICLE_DETONATIONS =
            ConcurrentHashMap.newKeySet();

    public static final MapCodec<LandMineBlock> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    propertiesCodec(),
                    Codec.STRING.fieldOf("mine_type").forGetter(b -> b.type.name())
            ).apply(instance, (props, name) -> new LandMineBlock(props, MineType.valueOf(name))));

    private final MineType type;

    public LandMineBlock(Properties properties, MineType type) {
        super(properties);
        this.type = type;
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(ARMED, false)
                .setValue(BURIAL, 0));
    }

    public MineType getMineType() {
        return this.type;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ARMED, BURIAL);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(ARMED, false).setValue(BURIAL, 0);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide && !state.getValue(ARMED)) {
            level.scheduleTick(pos, this, ARM_DELAY_TICKS);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(ARMED)) {
            level.setBlock(pos, state.setValue(ARMED, true), Block.UPDATE_CLIENTS);
            level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.35f, 0.6f);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.buriedShape(state);
    }

    /**
     * The outline sinks with the mine, so a fully dug-in charge is flush with the ground
     * and no longer catches the cursor from across the field.
     */
    private VoxelShape buriedShape(BlockState state) {
        int burial = state.getValue(BURIAL);
        if (burial <= 0) {
            return this.type.shape;
        }
        var bounds = this.type.shape.bounds();
        double top = Math.max(0.05D, bounds.maxY - burial / (double) MAX_BURIAL * bounds.maxY);
        return Block.box(bounds.minX * 16.0D, bounds.minY * 16.0D, bounds.minZ * 16.0D,
                bounds.maxX * 16.0D, top * 16.0D, bounds.maxZ * 16.0D);
    }

    /**
     * Digging the mine in with a shovel.
     * <p>
     * Progress per use is taken from the shovel's own dig speed against the mine, so a
     * netherite blade buries a charge in a couple of scoops where a wooden one takes
     * several. A buried mine keeps working — this only conceals it.
     */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            net.minecraft.world.InteractionHand hand,
            BlockHitResult hit) {
        if (!(stack.getItem() instanceof net.minecraft.world.item.ShovelItem)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        int burial = state.getValue(BURIAL);
        if (burial >= MAX_BURIAL) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (!level.isClientSide) {
            // getDestroySpeed is the shovel's own tier speed: 2 for wood up to 9 for
            // netherite. One stage always lands, so even the worst tool makes progress.
            // Vanilla shovel speeds run 2 (wood) to 9 (netherite). Dividing by 1.2
            // spreads them across the eight burial stages so the tiers actually differ
            // in how many scoops a charge takes, instead of collapsing into two groups.
            float speed = Math.max(1.0f, stack.getItem().getDestroySpeed(
                    stack, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState()));
            int stages = Math.max(1, Math.round(speed / 1.2f));
            int next = Math.min(MAX_BURIAL, burial + stages);
            level.setBlock(pos, state.setValue(BURIAL, next), Block.UPDATE_CLIENTS);

            level.playSound(null, pos, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.BLOCKS,
                    0.7f, 0.8f + level.getRandom().nextFloat() * 0.25f);
            if (level instanceof ServerLevel server) {
                server.sendParticles(
                        new net.minecraft.core.particles.BlockParticleOption(
                                net.minecraft.core.particles.ParticleTypes.BLOCK,
                                net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState()),
                        pos.getX() + 0.5D, pos.getY() + 0.15D, pos.getZ() + 0.5D,
                        12, 0.3D, 0.05D, 0.3D, 0.02D);
            }
            if (player != null && !player.getAbilities().instabuild) {
                stack.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            }
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // Same thin disc — entities actually intersect it (pressure-plate style).
        return this.type.shape;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        tryInfantryDetonate(level, pos, state, entity);
        super.stepOn(level, pos, state, entity);
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        tryInfantryDetonate(level, pos, state, entity);
        super.entityInside(state, level, pos, entity);
    }

    @Override
    public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, float fallDistance) {
        tryInfantryDetonate(level, pos, state, entity);
        super.fallOn(level, state, pos, entity, fallDistance);
    }

    private void tryInfantryDetonate(Level level, BlockPos pos, BlockState state, Entity entity) {
        if (level.isClientSide || !this.type.infantryTrigger || !state.getValue(ARMED)) {
            return;
        }
        if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isSpectator()) {
            return;
        }
        // Creative flying still safe; walking/sprinting in creative arms the fuze (easier to test).
        if (living instanceof Player player && player.getAbilities().flying) {
            return;
        }
        detonate((ServerLevel) level, pos, state);
    }

    /** Called from Sable / Offroad soft-compat (physics or wheel raycast). */
    public static void tryVehicleDetonate(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LandMineBlock mine)) {
            // Wheel casts often resolve parent-world coords while the BE lives on a SubLevel.
            if (ModList.get().isLoaded("sable")) {
                ServerLevel parent = SableDropCompat.resolveWorldBlast(level, Vec3.atCenterOf(pos)).level();
                if (parent != level && parent.isLoaded(pos)) {
                    BlockState parentState = parent.getBlockState(pos);
                    if (parentState.getBlock() instanceof LandMineBlock parentMine
                            && parentMine.type.vehicleTrigger
                            && parentState.getValue(ARMED)) {
                        detonate(parent, pos, parentState);
                    }
                }
            }
            return;
        }
        if (!mine.type.vehicleTrigger || !state.getValue(ARMED)) {
            return;
        }
        detonate(level, pos, state);
    }

    public static void scheduleVehicleDetonate(ServerLevel level, BlockPos pos) {
        BlockPos immutable = pos.immutable();
        PendingDetonation pending = new PendingDetonation(level, immutable.asLong());
        if (!PENDING_VEHICLE_DETONATIONS.add(pending)) {
            return;
        }
        level.getServer().tell(new net.minecraft.server.TickTask(level.getServer().getTickCount(), () -> {
            try {
                tryVehicleDetonate(level, immutable);
            } finally {
                PENDING_VEHICLE_DETONATIONS.remove(pending);
            }
        }));
    }

    public static void detonate(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof LandMineBlock mine)) {
            return;
        }
        // Already gone (double trigger same tick).
        if (!level.isLoaded(pos) || level.getBlockState(pos).getBlock() != mine) {
            return;
        }
        MineType type = mine.type;
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        Vec3 local = Vec3.atCenterOf(pos);
        ServerLevel blastLevel = level;
        Vec3 blastPos = CBCCompatTransformers.transformVec3(level, local);
        if (ModList.get().isLoaded("sable")) {
            SableDropCompat.BlastTarget target = SableDropCompat.resolveWorldBlast(level, local);
            blastLevel = target.level();
            blastPos = target.pos();
        }
        // Mines report their own death cause, so "blown up by a bomb" no longer shows
        // for someone who stepped on an antipersonnel charge.
        if (type == MineType.SMALL) {
            MineExplosionHandler.detonateSmallShrapnel(
                    blastLevel,
                    null,
                    MineDamageSource.create(blastLevel, type),
                    blastPos,
                    type.entityBlastPower);
        } else {
            BombExplosionHandler.detonateAntiTankMine(
                    blastLevel,
                    null,
                    MineDamageSource.create(blastLevel, type),
                    blastPos,
                    type.blockBlastPower,
                    type.entityBlastPower);
        }
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        // Flat disc — nothing useful to rotate.
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level world = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (!(world instanceof ServerLevel)) {
            return InteractionResult.SUCCESS;
        }

        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(world, pos, state, player);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return InteractionResult.SUCCESS;
        }

        if (player != null && !player.isCreative()) {
            player.getInventory().placeItemBackInInventory(new ItemStack(this));
        }
        world.destroyBlock(pos, false);
        AllSoundEvents.WRENCH_REMOVE.playOnServer(world, pos, 1.0f, world.getRandom().nextFloat() * 0.5f + 0.5f);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void wasExploded(Level level, BlockPos pos, net.minecraft.world.level.Explosion explosion) {
        if (level instanceof ServerLevel serverLevel) {
            detonate(serverLevel, pos, level.getBlockState(pos));
        }
    }

    private record PendingDetonation(ServerLevel level, long pos) {
    }
}
