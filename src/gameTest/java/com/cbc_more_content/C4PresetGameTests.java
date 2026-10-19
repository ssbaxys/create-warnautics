package com.cbc_more_content;

import com.cbc_more_content.block.C4Block;
import com.cbc_more_content.block.C4BlockEntity;
import com.cbc_more_content.item.C4Preset;
import com.cbc_more_content.item.DetonatorItem;
import com.cbc_more_content.registry.ModItems;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** C4 set up before the throw: live from the throw, started with one click, or paired by itself. */
@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class C4PresetGameTests {
    private static final BlockPos FLOOR = new BlockPos(12, 1, 12);

    /** A player standing over a stone floor, looking straight down, a preset C4 in hand. */
    private static FakePlayer thrower(GameTestHelper helper, C4Preset preset) {
        var level = helper.getLevel();
        for (BlockPos p : BlockPos.betweenClosed(FLOOR.offset(-2, 0, -2), FLOOR.offset(2, 0, 2))) {
            helper.setBlock(p, Blocks.STONE);
        }
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "c4-preset-test"));
        player.setGameMode(GameType.SURVIVAL);
        var at = helper.absoluteVec(FLOOR.above(3).getBottomCenter());
        player.moveTo(at.x, at.y, at.z, 0.0f, 90.0f);
        ItemStack c4 = new ItemStack(ModItems.C4.get(), 2);
        preset.store(c4);
        player.setItemInHand(InteractionHand.MAIN_HAND, c4);
        return player;
    }

    private static void throwIt(GameTestHelper helper, FakePlayer player) {
        player.getMainHandItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
    }

    /** The charge that stuck to the floor, or null while it is still in the air. */
    private static C4BlockEntity landed(GameTestHelper helper) {
        var level = helper.getLevel();
        for (BlockPos p : BlockPos.betweenClosed(FLOOR.offset(-2, 1, -2), FLOOR.offset(2, 2, 2))) {
            if (level.getBlockEntity(helper.absolutePos(p)) instanceof C4BlockEntity c4) {
                return c4;
            }
        }
        return null;
    }

    @GameTest(template = "empty", batch = "c4_preset_throw", timeoutTicks = 80)
    public static void presetChargeIsLiveFromTheThrow(GameTestHelper helper) {
        var player = thrower(helper, new C4Preset(C4Preset.ON_THROW, false, 9, 1234));
        throwIt(helper, player);
        helper.succeedWhen(() -> {
            var c4 = landed(helper);
            helper.assertTrue(c4 != null, "The charge sticks to the floor");
            helper.assertTrue(c4.isArmed(), "Live from the throw, with nobody at the keypad");
            helper.assertTrue(c4.fuseSeconds() == 9, "The preset fuse is set: " + c4.fuseSeconds());
            helper.assertTrue(c4.matchesCode(1234), "The preset code disarms it");
            helper.assertTrue(
                    c4.remaining() > 0 && c4.remaining() < 9 * 20, "The fuse has been running since the throw");
            c4.disarm();
        });
    }

    @GameTest(template = "empty", batch = "c4_preset_manual", timeoutTicks = 80)
    public static void manualPresetLandsReadyAndStartsWithOneClick(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = thrower(helper, new C4Preset(C4Preset.MANUAL, false, 12, 4321));
        throwIt(helper, player);
        helper.succeedWhen(() -> {
            var c4 = landed(helper);
            helper.assertTrue(c4 != null, "The charge sticks to the floor");
            helper.assertFalse(c4.isArmed(), "A hand-started preset lands idle");
            helper.assertTrue(c4.isPrimed(), "It lands set up, waiting for the key");
            helper.assertTrue(c4.matchesCode(4321), "With the preset code already on it");
            // One click of the settings key, and it is running.
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.SETTINGS_KEY.get()));
            BlockPos pos = c4.getBlockPos();
            var hit = new BlockHitResult(pos.getCenter(), Direction.UP, pos, false);
            ModItems.SETTINGS_KEY.get().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            helper.assertTrue(c4.isArmed(), "One click of the key starts it");
            helper.assertTrue(c4.remaining() == 12 * 20, "With the preset fuse: " + c4.remaining());
            helper.assertFalse(c4.isPrimed(), "Started, it is no longer waiting");
            c4.disarm();
            helper.assertTrue(
                    level.getBlockState(pos).getValue(C4Block.STATE) == C4Block.Fuse.IDLE, "Disarmed after the test");
        });
    }

    @GameTest(template = "empty", batch = "c4_preset_remote", timeoutTicks = 100)
    public static void remotePresetPairsWithTheThrowersDetonator(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = thrower(helper, new C4Preset(C4Preset.ON_THROW, true, 15, 1111));
        ItemStack detonator = new ItemStack(ModItems.DETONATOR.get());
        player.getInventory().setItem(5, detonator);
        throwIt(helper, player);
        BlockPos[] at = {null};
        helper.succeedWhen(() -> {
            if (at[0] == null) {
                var c4 = landed(helper);
                helper.assertTrue(c4 != null, "The charge sticks to the floor");
                helper.assertTrue(c4.isWaitingOnRemote(), "A detonator preset lands live and silent");
                helper.assertTrue(
                        DetonatorItem.boundCharges(player.getInventory().getItem(5))
                                .contains(c4.getBlockPos()),
                        "It paired itself with the detonator in the thrower's inventory");
                helper.assertTrue(
                        level.getBlockState(c4.getBlockPos()).getValue(C4Block.STATE) == C4Block.Fuse.LIT,
                        "Paired, its screen lights");
                at[0] = c4.getBlockPos();
                // Press the detonator.
                player.setItemInHand(
                        InteractionHand.MAIN_HAND, player.getInventory().getItem(5));
                player.getMainHandItem().use(level, player, InteractionHand.MAIN_HAND);
            }
            helper.assertFalse(
                    level.getBlockState(at[0]).getBlock() instanceof C4Block, "The detonator sets the charge off");
        });
    }
}
