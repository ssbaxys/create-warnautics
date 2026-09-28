package com.cbc_more_content;

import com.cbc_more_content.compat.simulated.ChainConnection;
import com.cbc_more_content.item.ChainCoilItem;
import com.cbc_more_content.registry.ModItems;
import com.mojang.authlib.GameProfile;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.rope_winch.RopeWinchBlockEntity;
import dev.simulated_team.simulated.content.items.rope.RopeItem.RopeItem;
import dev.simulated_team.simulated.index.SimDataComponents;
import dev.simulated_team.simulated.index.SimItems;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class ChainGameTests {
    private static BlockPos anchor(GameTestHelper helper, BlockPos relative, boolean winch) {
        var pos = helper.absolutePos(relative);
        var block = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("simulated", winch ? "rope_winch" : "rope_connector"));
        helper.getLevel().setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(
                RopeItem.getRopeHolder(helper.getLevel(), pos) != null, "Native rope holder must be initialized");
        return pos;
    }

    private static FakePlayer player(GameTestHelper helper) {
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "chain-test"));
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static void click(FakePlayer player, BlockPos pos) {
        var context = new UseOnContext(
                player, InteractionHand.MAIN_HAND, new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
        player.getMainHandItem().getItem().useOn(context);
    }

    private static void connect(FakePlayer player, ItemStack stack, BlockPos first, BlockPos second) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        click(player, first);
        click(player, second);
    }

    private static RopeStrandHolderBehavior holder(GameTestHelper helper, BlockPos pos) {
        return RopeItem.getRopeHolder(helper.getLevel(), pos);
    }

    @GameTest(template = "empty", batch = "chain_coexist", timeoutTicks = 100)
    public static void separateItemsAndConnectionsCoexist(GameTestHelper helper) {
        var a = anchor(helper, new BlockPos(5, 8, 5), false);
        var b = anchor(helper, new BlockPos(11, 8, 5), false);
        var c = anchor(helper, new BlockPos(5, 8, 11), false);
        var d = anchor(helper, new BlockPos(11, 8, 11), false);
        var player = player(helper);
        var chain = new ItemStack(ModItems.CHAIN_COIL.get(), 2);
        connect(player, chain, a, b);
        helper.assertTrue(chain.getCount() == 1, "A successful chain connection consumes one chain coil");
        helper.assertTrue(
                ChainConnection.isChain(holder(helper, a)) && ChainConnection.isChain(holder(helper, b)),
                "Both chain ends carry the material");
        helper.assertTrue(holder(helper, a).getOwnedStrand() != null, "Chain uses native physical strand");
        var rope = new ItemStack(SimItems.ROPE_COUPLING.get(), 2);
        connect(player, rope, c, d);
        helper.assertTrue(rope.getCount() == 1, "Native coil still consumes one rope");
        helper.assertTrue(
                holder(helper, c).isAttached()
                        && !ChainConnection.isChain(holder(helper, c))
                        && !ChainConnection.isChain(holder(helper, d)),
                "Native rope remains rope beside a chain");
        helper.assertTrue(!ChainCoilItem.isPlacingChain(), "Placement scope must not leak to later ropes");
        holder(helper, a).destroyRope(null, null, false);
        holder(helper, c).destroyRope(null, null, false);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chain_save", timeoutTicks = 100)
    public static void materialSurvivesSaveAndClientSync(GameTestHelper helper) {
        var a = anchor(helper, new BlockPos(5, 8, 5), false);
        var b = anchor(helper, new BlockPos(11, 8, 5), false);
        connect(player(helper), new ItemStack(ModItems.CHAIN_COIL.get()), a, b);
        for (var pos : List.of(a, b)) {
            var source = holder(helper, pos);
            for (boolean client : List.of(false, true)) {
                var data = new CompoundTag();
                source.write(data, helper.getLevel().registryAccess(), client);
                var restored = new RopeStrandHolderBehavior(source.blockEntity);
                restored.read(data, helper.getLevel().registryAccess(), client);
                helper.assertTrue(
                        ChainConnection.isChain(restored), "Material must survive save and packet at both ends");
                helper.assertTrue(restored.ownsRope() == source.ownsRope(), "Material must not alter native ownership");
                if (!client && source.ownsRope()) {
                    helper.assertTrue(
                            restored.getOwnedStrand()
                                    .getUUID()
                                    .equals(source.getOwnedStrand().getUUID()),
                            "Saved native strand and UUID survive");
                }
                data.remove("cbc_more_content:chain");
                restored.read(data, helper.getLevel().registryAccess(), client);
                helper.assertTrue(
                        !ChainConnection.isChain(restored), "Legacy saves without a marker are ordinary rope");
            }
        }
        holder(helper, a).destroyRope(null, null, false);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chain_drops", timeoutTicks = 100)
    public static void dismantlingReturnsCorrectItemAndClearsMaterial(GameTestHelper helper) {
        var a = anchor(helper, new BlockPos(5, 8, 5), false);
        var b = anchor(helper, new BlockPos(11, 8, 5), false);
        var player = player(helper);
        connect(player, new ItemStack(ModItems.CHAIN_COIL.get()), a, b);
        holder(helper, a).destroyRope(player, null, true);
        helper.assertTrue(
                player.getInventory().countItem(ModItems.CHAIN_COIL.get()) == 1
                        && player.getInventory().countItem(SimItems.ROPE_COUPLING.get()) == 0,
                "Dismantling returns chain, not rope");
        helper.assertTrue(
                !holder(helper, a).isAttached()
                        && !holder(helper, b).isAttached()
                        && !ChainConnection.isChain(holder(helper, b)),
                "Both ends detach cleanly");
        connect(player, new ItemStack(SimItems.ROPE_COUPLING.get()), a, b);
        helper.assertTrue(
                !ChainConnection.isChain(holder(helper, a)) && !ChainConnection.isChain(holder(helper, b)),
                "Reusing the same anchors with native rope resets the material");
        holder(helper, a).destroyRope(player, null, true);
        helper.assertTrue(
                player.getInventory().countItem(SimItems.ROPE_COUPLING.get()) == 1,
                "Ordinary rope still returns the native coil");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chain_winch", timeoutTicks = 100)
    public static void winchWorksInBothClickOrdersAndCancelDoesNotConsume(GameTestHelper helper) {
        var a = anchor(helper, new BlockPos(5, 8, 5), false);
        var b = anchor(helper, new BlockPos(11, 8, 5), true);
        var player = player(helper);
        for (boolean reverse : List.of(false, true)) {
            connect(player, new ItemStack(ModItems.CHAIN_COIL.get()), reverse ? b : a, reverse ? a : b);
            var winch = (RopeWinchBlockEntity) helper.getLevel().getBlockEntity(b);
            var strand = winch.getRopeHolder().getOwnedStrand();
            helper.assertTrue(
                    strand != null && ChainConnection.isChain(winch.getRopeHolder()),
                    "Winch owns chain in either click order");
            // Let Create finish the initial network attachment before supplying fixture rotation.
            winch.tick();
            double before = strand.getExtension() + strand.getPoints().size();
            winch.setSpeed(64);
            winch.tick();
            double extended = strand.getExtension() + strand.getPoints().size();
            helper.assertTrue(extended > before, "Native powered winch pays out the chain");
            winch.setSpeed(-64);
            winch.tick();
            helper.assertTrue(
                    strand.getExtension() + strand.getPoints().size() < extended,
                    "Native powered winch retracts the chain");
            winch.getRopeHolder().destroyRope(null, null, false);
        }
        var coil = new ItemStack(ModItems.CHAIN_COIL.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, coil);
        click(player, a);
        player.setShiftKeyDown(true);
        click(player, b);
        player.setShiftKeyDown(false);
        helper.assertTrue(
                coil.getCount() == 1
                        && !coil.has(SimDataComponents.ROPE_FIRST_CONNECTION)
                        && !holder(helper, a).isAttached(),
                "Sneak cancellation clears selection without consuming chain");
        var c = anchor(helper, new BlockPos(11, 8, 11), true);
        connect(player, coil, b, c);
        helper.assertTrue(
                coil.getCount() == 1
                        && !holder(helper, b).isAttached()
                        && !holder(helper, c).isAttached(),
                "Two winches are rejected using native rules without consuming chain");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chain_sublevels", timeoutTicks = 100)
    public static void chainConnectsNativeAnchorsOnSubLevels(GameTestHelper helper) {
        var a = anchor(helper, new BlockPos(5, 8, 5), false);
        var b = anchor(helper, new BlockPos(11, 8, 5), false);
        var first = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(
                helper.getLevel(), a, List.of(a), new dev.ryanhcode.sable.companion.math.BoundingBox3i(a, a));
        var second = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(
                helper.getLevel(), b, List.of(b), new dev.ryanhcode.sable.companion.math.BoundingBox3i(b, b));
        a = first.getPlot().getCenterBlock();
        b = second.getPlot().getCenterBlock();
        connect(player(helper), new ItemStack(ModItems.CHAIN_COIL.get()), a, b);
        var strand = holder(helper, a).getOwnedStrand();
        helper.assertTrue(
                strand != null
                        && ChainConnection.isChain(holder(helper, a))
                        && ChainConnection.isChain(holder(helper, b)),
                "Sub-level anchors retain chain material");
        helper.assertTrue(
                strand.getAttachment(
                                        dev.simulated_team.simulated.content.blocks.rope.strand.server
                                                .RopeAttachmentPoint.START)
                                .subLevelID()
                                .equals(first.getUniqueId())
                        && strand.getAttachment(
                                        dev.simulated_team.simulated.content.blocks.rope.strand.server
                                                .RopeAttachmentPoint.END)
                                .subLevelID()
                                .equals(second.getUniqueId()),
                "Native physics links both sub-levels");
        holder(helper, a).destroyRope(null, null, false);
        helper.getLevel().removeBlock(a, false);
        helper.getLevel().removeBlock(b, false);
        helper.succeed();
    }
}
