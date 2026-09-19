package com.cbc_more_content.effects;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * A blast calculator that lets an explosion pass through open fluid.
 * <p>
 * Vanilla charges every wet cell {@code (resistance + 0.3) * 0.3} per sweep step — and
 * water is resistance 100, so one wet cell ends an entire crater ray. That is why blasts
 * fired in water left no crater: the water column itself was eating every ray before it
 * could reach the seabed. Open water transmits a blast; it does not stop one.
 * <p>
 * Only a fluid that cannot hold a shape (open water, lava, flowing) passes free. A
 * waterlogged solid keeps its own resistance — its collision shape is what the blast
 * has to defeat. Seabed beyond the water charges vanilla resistance as usual; the only
 * refusal is open fluid, which must never be listed as a block to break. Nothing else
 * changes, so claim protection and Sable's filters stay untouched.
 */
public final class FluidTransparentBlastCalculator extends ExplosionDamageCalculator {
    public static final FluidTransparentBlastCalculator INSTANCE = new FluidTransparentBlastCalculator();

    private FluidTransparentBlastCalculator() {}

    @Override
    public Optional<Float> getBlockExplosionResistance(
            Explosion explosion, BlockGetter reader, BlockPos pos, BlockState state, FluidState fluid) {
        if (isOpenFluid(reader, pos, state)) {
            return Optional.empty();
        }
        return super.getBlockExplosionResistance(explosion, reader, pos, state, fluid);
    }

    /**
     * Refuses open fluid as a block to break, so the water column itself never lands in
     * the crater list — the sweep would otherwise happily zero-cost march and then
     * setBlock air where water was, punching the exact air pockets this mod once fixed.
     */
    @Override
    public boolean shouldBlockExplode(
            Explosion explosion, BlockGetter reader, BlockPos pos, BlockState state, float power) {
        if (isOpenFluid(reader, pos, state)) {
            return false;
        }
        return super.shouldBlockExplode(explosion, reader, pos, state, power);
    }

    /** Open fluid: a fluid cell whose block shape holds nothing — water, not a sponge. */
    private static boolean isOpenFluid(BlockGetter reader, BlockPos pos, BlockState state) {
        return !state.getFluidState().isEmpty()
                && state.getCollisionShape(reader, pos).isEmpty();
    }
}
