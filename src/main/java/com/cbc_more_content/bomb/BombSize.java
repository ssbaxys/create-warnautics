package com.cbc_more_content.bomb;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Drop-bomb tiers: size, collision, release impulse, entity hitbox, blast powers.
 * <p>
 * The release impulse pushes the bomb along its nose and slightly <em>down</em>, so it
 * leaves the rack on a visible arc instead of dropping like a stone. There is no upward
 * component anywhere: the old {@code launchUp}/{@code launchArc} pair is what threw
 * side-mounted bombs back into the aircraft, and it is not coming back.
 */
public enum BombSize {
    SMALL(
            Block.box(4.0D, 0.0D, 4.0D, 12.0D, 10.0D, 12.0D),
            Block.box(4.0D, 3.0D, 0.0D, 12.0D, 13.0D, 10.0D),
            Block.box(0.0D, 3.0D, 4.0D, 10.0D, 13.0D, 12.0D),
            0.40D, 0.12D,
            // Weakened 2x: block 3.625f, entity 4.0f
            0.45f, 3.625f, 4.0f),
    /** Between small and medium — water-capable (swim then sink). */
    SEA(
            Block.box(3.5D, 0.0D, 3.5D, 12.5D, 11.0D, 12.5D),
            Block.box(3.5D, 2.5D, 0.0D, 12.5D, 13.5D, 11.0D),
            Block.box(0.0D, 2.5D, 3.5D, 11.0D, 13.5D, 12.5D),
            0.38D, 0.11D,
            0.55f, 5.35f, 6.75f),
    MEDIUM(
            Block.box(3.0D, 0.0D, 3.0D, 13.0D, 12.0D, 13.0D),
            Block.box(3.0D, 2.0D, 0.0D, 13.0D, 14.0D, 12.0D),
            Block.box(0.0D, 2.0D, 3.0D, 12.0D, 14.0D, 13.0D),
            0.36D, 0.10D,
            // Rebalanced from 16.5/21: medium payload weakened exactly 1.5x.
            0.65f, 11.0f, 14.0f),
    LARGE(
            Block.box(2.0D, 0.0D, 2.0D, 14.0D, 14.0D, 14.0D),
            Block.box(2.0D, 1.0D, 0.0D, 14.0D, 15.0D, 14.0D),
            Block.box(0.0D, 1.0D, 2.0D, 14.0D, 15.0D, 14.0D),
            0.30D, 0.08D,
            // Warnautics heavy payload pass: 1.5x block/entity blast power.
            0.90f, 15.6f, 21.45f);

    public final VoxelShape shapeUd;
    public final VoxelShape shapeNs;
    public final VoxelShape shapeEw;
    /** Forward push along the nose on release — the flat part of the arc. */
    public final double launchAlong;
    /** Small downward bias so the arc starts falling immediately, never rising. */
    public final double launchDown;
    public final float entitySize;
    public final float blockBlastPower;
    public final float entityBlastPower;

    BombSize(
            VoxelShape shapeUd,
            VoxelShape shapeNs,
            VoxelShape shapeEw,
            double launchAlong,
            double launchDown,
            float entitySize,
            float blockBlastPower,
            float entityBlastPower) {
        this.shapeUd = shapeUd;
        this.shapeNs = shapeNs;
        this.shapeEw = shapeEw;
        this.launchAlong = launchAlong;
        this.launchDown = launchDown;
        this.entitySize = entitySize;
        this.blockBlastPower = blockBlastPower;
        this.entityBlastPower = entityBlastPower;
    }

    public VoxelShape shapeFor(net.minecraft.core.Direction.Axis axis) {
        return switch (axis) {
            case X -> this.shapeEw;
            case Z -> this.shapeNs;
            case Y -> this.shapeUd;
        };
    }

    public boolean isSeaBomb() {
        return this == SEA;
    }
}
