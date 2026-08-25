package com.cbc_more_content.event;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.LandMineBlock;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;

/**
 * Turning in on a charge.
 * <p>
 * Walking over a bedded mine already sets it off through the block's own contact
 * handling, but a sleeper folds into a box a fifth of a block tall and never reaches the
 * cell the charge is in. This is the other way to find one.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID)
public final class BeddedMineHandler {
    private BeddedMineHandler() {
    }

    @SubscribeEvent
    public static void onSleep(CanPlayerSleepEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        // Both halves are checked: it is the same bed either way, and a charge under the
        // pillow is no less lethal than one under the feet.
        if (LandMineBlock.detonateBeddedMines(level, event.getPos())) {
            // Nothing to refuse — whoever lay down is no longer in a position to sleep.
            event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
        }
    }
}
