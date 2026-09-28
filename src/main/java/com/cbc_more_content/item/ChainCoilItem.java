package com.cbc_more_content.item;

import dev.simulated_team.simulated.content.items.rope.RopeItem.RopeItem;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;

/** A distinct item using Simulated's endpoint selection, range checks and winch rules. */
public final class ChainCoilItem extends RopeItem {
    private static final ThreadLocal<Boolean> PLACING_CHAIN = ThreadLocal.withInitial(() -> false);

    public ChainCoilItem(Properties properties) {
        super(properties);
    }

    public static boolean isPlacingChain() {
        return PLACING_CHAIN.get();
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        boolean previous = PLACING_CHAIN.get();
        PLACING_CHAIN.set(true);
        try {
            return super.useOn(context);
        } finally {
            PLACING_CHAIN.set(previous);
        }
    }
}
