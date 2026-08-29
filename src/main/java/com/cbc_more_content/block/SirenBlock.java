package com.cbc_more_content.block;

import com.cbc_more_content.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
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
 * Air-raid siren on a driven post.
 * <p>
 * A siren is a rotor in a housing — the note is the rotor, which is why a real one winds
 * up and dies away rather than switching. So it takes a shaft from below like anything
 * else on a Create network, and how fast it is turning is how loud it is. Redstone and
 * everything it watches for decide only whether it is trying to sound; without drive it
 * is a lump of metal on a stand.
 */
public class SirenBlock extends KineticBlock implements IBE<SirenBlockEntity> {
    public static final MapCodec<SirenBlock> CODEC = simpleCodec(SirenBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /**
     * Lit: the post has been told to sound, drive or no drive.
     * <p>
     * Separate from {@link #SOUNDING} on purpose. The lamp is the control circuit, and a
     * signal reaches that whether or not there is a shaft turning underneath — a post
     * standing lit and silent is telling you it is armed and waiting for drive, which is
     * far more use than a block that looks dead until someone finds the gearbox.
     */
    public static final BooleanProperty POWERED = BooleanProperty.create("powered");
    /** Actually making a note. Needs the lamp and a turning rotor both. */
    public static final BooleanProperty SOUNDING = BooleanProperty.create("sounding");

    private static final VoxelShape BASE = Block.box(3.0D, 0.0D, 1.0D, 13.0D, 3.0D, 15.0D);
    private static final VoxelShape POST = Block.box(5.0D, 1.0D, 5.0D, 11.0D, 14.0D, 11.0D);
    private static final VoxelShape HORNS = Block.box(3.0D, 3.0D, 0.0D, 13.0D, 15.0D, 16.0D);
    private static final VoxelShape SHAPE = Shapes.or(BASE, POST, HORNS);

    public SirenBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition
                .any()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWERED, false)
                .setValue(SOUNDING, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED, SOUNDING);
        super.createBlockStateDefinition(builder);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    // —— kinetics ——

    /** The rotor stands upright, so the drive comes up the post. */
    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }

    /**
     * Driven from below only.
     * <p>
     * Not both ends: the housing sits on top of the post, and a shaft coming down into the
     * horns would have to pass through them. It also keeps the block readable — one face
     * takes drive, and it is the one under the stand.
     */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == Direction.DOWN;
    }

    @Override
    public Class<SirenBlockEntity> getBlockEntityClass() {
        return SirenBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends SirenBlockEntity> getBlockEntityType() {
        return ModBlockEntities.SIREN.get();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    // No neighborChanged override on purpose. It used to call raise() when a signal
    // appeared, which is the sighting path — so throwing a lever started the full linger
    // and the post went on wailing for three quarters of a minute after the line was cut.
    // The post reads the signal for itself every tick, and a signal only ever holds it.
}
