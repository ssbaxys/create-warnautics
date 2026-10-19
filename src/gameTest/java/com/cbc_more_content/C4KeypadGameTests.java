package com.cbc_more_content;

import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.block.C4BlockEntity.Outcome;
import com.cbc_more_content.registry.ModBlocks;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class C4KeypadGameTests {
    private static final BlockPos CHARGE = new BlockPos(4, 2, 4);
    private static final int CODE = 4271;

    private static C4BlockEntity armedCharge(GameTestHelper helper) {
        helper.setBlock(CHARGE.below(), Blocks.STONE);
        helper.setBlock(CHARGE, ModBlocks.C4.get());
        var c4 = (C4BlockEntity) helper.getBlockEntity(CHARGE);
        // Parked on a detonator, so the test's own clock never runs into the fuse.
        c4.setRemote(true);
        c4.setCode(CODE);
        c4.arm();
        helper.assertTrue(c4.isArmed(), "Charge is live");
        return c4;
    }

    private static void expect(GameTestHelper helper, C4BlockEntity.CodeVerdict verdict, Outcome outcome, int value) {
        helper.assertTrue(
                verdict.outcome() == outcome && verdict.value() == value,
                "Expected " + outcome + " " + value + ", got " + verdict);
    }

    /**
     * Three wrong codes lock the keypad for ten seconds; while locked even the right code is
     * refused, and for everyone, so guessing faster or taking turns does not help.
     */
    @GameTest(template = "empty", batch = "c4_keypad", timeoutTicks = 300)
    public static void threeWrongCodesLockTheKeypad(GameTestHelper helper) {
        var c4 = armedCharge(helper);
        UUID sapper = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        expect(helper, c4.tryCode(sapper, 1111), Outcome.WRONG, 2);
        expect(helper, c4.tryCode(sapper, 2222), Outcome.WRONG, 1);
        expect(helper, c4.tryCode(sapper, 3333), Outcome.LOCKED, 10);
        expect(helper, c4.tryCode(sapper, CODE), Outcome.LOCKED, 10);
        expect(helper, c4.tryCode(friend, CODE), Outcome.LOCKED, 10);
        helper.assertTrue(
                c4.saveWithoutMetadata(helper.getLevel().registryAccess()).contains("Keypads"),
                "The lockout is saved with the charge, so relogging does not clear it");
        helper.runAfterDelay(120, () -> {
            var verdict = c4.tryCode(sapper, CODE);
            helper.assertTrue(
                    verdict.outcome() == Outcome.LOCKED && verdict.value() <= 5,
                    "Still locked half-way, counting down: " + verdict);
        });
        helper.runAfterDelay(205, () -> {
            expect(helper, c4.tryCode(sapper, CODE), Outcome.ACCEPTED, 0);
            helper.succeed();
        });
    }

    /** The right code at once is accepted and clears the record of failures. */
    @GameTest(template = "empty", batch = "c4_keypad_ok", timeoutTicks = 40)
    public static void rightCodeIsAcceptedBeforeTheLimit(GameTestHelper helper) {
        var c4 = armedCharge(helper);
        UUID sapper = UUID.randomUUID();
        expect(helper, c4.tryCode(sapper, 9999), Outcome.WRONG, 2);
        expect(helper, c4.tryCode(sapper, CODE), Outcome.ACCEPTED, 0);
        helper.succeed();
    }
}
