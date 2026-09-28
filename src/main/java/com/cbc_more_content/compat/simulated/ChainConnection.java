package com.cbc_more_content.compat.simulated;

import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;

/** Per-connection material, saved and synchronized in the native holder's block-entity data. */
public interface ChainConnection {
    boolean warnautics$isChain();

    void warnautics$setChain(boolean chain);

    static boolean isChain(RopeStrandHolderBehavior holder) {
        return holder instanceof ChainConnection material && material.warnautics$isChain();
    }
}
