package com.cbc_more_content.mine;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Land mine tiers.
 * <ul>
 *   <li>{@link #SMALL} — antipersonnel: living entities stepping on it</li>
 *   <li>{@link #LARGE} — antivehicle: Sable sub-levels and Offroad (Aeronautics stack) wheels</li>
 * </ul>
 */
public enum MineType {
    SMALL(
            Block.box(4.0D, 0.0D, 4.0D, 12.0D, 2.0D, 12.0D),
            true,
            false,
            4.8f,
            6.2f),
    LARGE(
            // Full footprint so Offroad wheel rays cannot slip past the disc into dirt below.
            Block.box(0.0D, 0.0D, 0.0D, 16.0D, 3.0D, 16.0D),
            false,
            true,
            3.4f,
            4.5f);

    public final VoxelShape shape;
    /** Trigger when a living entity steps on the mine. */
    public final boolean infantryTrigger;
    /** Trigger on Sable hull contact / Offroad wheel raycast. */
    public final boolean vehicleTrigger;
    public final float blockBlastPower;
    public final float entityBlastPower;

    MineType(
            VoxelShape shape,
            boolean infantryTrigger,
            boolean vehicleTrigger,
            float blockBlastPower,
            float entityBlastPower) {
        this.shape = shape;
        this.infantryTrigger = infantryTrigger;
        this.vehicleTrigger = vehicleTrigger;
        this.blockBlastPower = blockBlastPower;
        this.entityBlastPower = entityBlastPower;
    }
}
