package com.cbc_more_content.mine;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Land mine tiers.
 * <ul>
 *   <li>{@link #SMALL} — antipersonnel: living entities stepping on it</li>
 *   <li>{@link #LARGE} — antivehicle: Sable sub-levels and Offroad (Aeronautics stack) wheels</li>
 *   <li>{@link #BOUNDING} — antipersonnel, but it jumps before it goes off</li>
 * </ul>
 */
public enum MineType {
    SMALL(Block.box(4.0D, 0.0D, 4.0D, 12.0D, 2.0D, 12.0D), true, false, true, 0.0D, 4.8f, 6.2f),
    LARGE(
            // Full footprint so Offroad wheel rays cannot slip past the disc into dirt below.
            Block.box(0.0D, 0.0D, 0.0D, 16.0D, 3.0D, 16.0D), false, true, false, 0.0D, 3.4f, 4.5f),
    /**
     * Trips from a prod rather than from weight, throws itself to about waist height and
     * bursts there — which is why it reaches over cover and why its trigger reads half a
     * block past its own cell rather than only the square it sits on.
     */
    BOUNDING(Block.box(6.0D, 0.0D, 6.0D, 10.0D, 2.5D, 10.0D), true, false, false, 0.5D, 0.0f, 8.5f);

    public final VoxelShape shape;
    /** Trigger when a living entity steps on the mine. */
    public final boolean infantryTrigger;
    /** Trigger on Sable hull contact / Offroad wheel raycast. */
    public final boolean vehicleTrigger;
    /** Whether this charge can be laid into bedding rather than into ground. */
    public final boolean beddable;
    /** How far past its own cell the charge notices someone, in blocks. 0 = contact only. */
    public final double triggerReach;

    public final float blockBlastPower;
    public final float entityBlastPower;

    MineType(
            VoxelShape shape,
            boolean infantryTrigger,
            boolean vehicleTrigger,
            boolean beddable,
            double triggerReach,
            float blockBlastPower,
            float entityBlastPower) {
        this.shape = shape;
        this.infantryTrigger = infantryTrigger;
        this.vehicleTrigger = vehicleTrigger;
        this.beddable = beddable;
        this.triggerReach = triggerReach;
        this.blockBlastPower = blockBlastPower;
        this.entityBlastPower = entityBlastPower;
    }
}
