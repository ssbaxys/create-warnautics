package com.cbc_more_content;

import com.cbc_more_content.munitions.FlightTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class FlightTicketGameTests {
    /**
     * A munition's ticket keeps the ground it flies over ticking, and lets it go again: it
     * must never leave chunks forced to tick for good. A forced-tick ticket did exactly that
     * (NeoForge never purges those), and every salvo left a trail of chunks ticking forever,
     * until the server could no longer keep up and the world had to be reloaded.
     */
    @GameTest(template = "empty", batch = "flight_ticket", timeoutTicks = 520)
    public static void flightTicketsLapseWithoutForcingTicks(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos far = helper.absolutePos(new BlockPos(12, 10, 12)).offset(320, 0, 0);
        ChunkPos chunk = new ChunkPos(far);
        var munition = EntityType.ARMOR_STAND.create(level);
        munition.setPos(far.getCenter());
        var tickets = level.getChunkSource().chunkMap.getDistanceManager();
        // Renewed every tick, as by a munition in flight, until the ground there ticks (however
        // long a busy server takes to get it ready), a second more, and then left to lapse.
        int[] renewUntil = {-1};
        for (int t = 0; t < 500; t++) {
            int tick = t;
            helper.runAfterDelay(t, () -> {
                helper.assertFalse(tickets.shouldForceTicks(chunk.toLong()), "A flight ticket never forces ticking");
                if (renewUntil[0] < 0 || tick <= renewUntil[0]) {
                    FlightTicket.keepLoaded(level, munition);
                }
                if (renewUntil[0] < 0 && level.isPositionEntityTicking(far)) {
                    renewUntil[0] = tick + 20;
                }
                if (renewUntil[0] < 0 && tick >= 380) {
                    helper.fail("The ground under a munition in flight never came to tick its entities");
                }
                if (renewUntil[0] >= 0 && tick >= renewUntil[0] + 100 && !level.isPositionEntityTicking(far)) {
                    helper.assertFalse(
                            tickets.shouldForceTicks(chunk.toLong()), "Nothing is left forced after the flight");
                    helper.succeed();
                }
            });
        }
    }
}
