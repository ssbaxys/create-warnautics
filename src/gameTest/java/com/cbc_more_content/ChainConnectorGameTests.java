package com.cbc_more_content;

import com.cbc_more_content.block.ChainConnectorBlock;
import com.cbc_more_content.compat.simulated.ChainConnection;
import com.cbc_more_content.registry.ModBlockEntities;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModItems;
import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.rope_connector.RopeConnectorBlock;
import dev.simulated_team.simulated.content.blocks.rope.rope_connector.RopeConnectorBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.rope_winch.RopeWinchBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.RopeAttachmentPoint;
import dev.simulated_team.simulated.content.items.rope.RopeItem.RopeItem;
import dev.simulated_team.simulated.index.SimBlocks;
import dev.simulated_team.simulated.index.SimDataComponents;
import dev.simulated_team.simulated.index.SimItems;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class ChainConnectorGameTests {
    private static FakePlayer player(GameTestHelper helper) {
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "chain-connector-test"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(helper.absolutePos(new BlockPos(70, 40, 70)).getCenter());
        return player;
    }

    private static UseOnContext context(FakePlayer player, BlockPos pos, Direction face) {
        return new UseOnContext(
                player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(
                        pos.getCenter().add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5),
                        face,
                        pos,
                        false));
    }

    private static InteractionResult click(FakePlayer player, BlockPos pos) {
        return player.getMainHandItem().getItem().useOn(context(player, pos, Direction.UP));
    }

    private static BlockPos anchor(GameTestHelper helper, BlockPos relative, Block block) {
        var pos = helper.absolutePos(relative);
        helper.getLevel().setBlockAndUpdate(pos, block.defaultBlockState());
        helper.assertTrue(holder(helper, pos) != null, "Connector must have native rope behavior");
        return pos;
    }

    private static RopeStrandHolderBehavior holder(GameTestHelper helper, BlockPos pos) {
        return RopeItem.getRopeHolder(helper.getLevel(), pos);
    }

    @GameTest(template = "empty", batch = "chain_connector_placement", timeoutTicks = 100)
    public static void placementAllFacesKeepsNativeRotationAndOwnDrops(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = player(helper);
        int index = 0;
        for (Direction face : Direction.values()) {
            for (int yaw : List.of(0, 90)) {
                var support = helper.absolutePos(new BlockPos(5 + index++ * 4, 30, 5));
                level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
                player.setYRot(yaw);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CHAIN_CONNECTOR.get(), 2));
                helper.assertTrue(
                        player.getMainHandItem()
                                .getItem()
                                .useOn(context(player, support, face))
                                .consumesAction(),
                        "Connector places on " + face + " at yaw " + yaw);
                var pos = support.relative(face);
                var state = level.getBlockState(pos);
                helper.assertTrue(
                        state.is(ModBlocks.CHAIN_CONNECTOR.get()) && state.getValue(RopeConnectorBlock.FACING) == face,
                        "Own block faces clicked side");
                helper.assertTrue(
                        level.getBlockEntity(pos).getType() == ModBlockEntities.CHAIN_CONNECTOR.get(),
                        "New connector has its own persistent block entity type");
                helper.assertTrue(player.getMainHandItem().getCount() == 1, "Placement consumes one connector");
                var nativeState = SimBlocks.ROPE_CONNECTOR
                        .getDefaultState()
                        .setValue(RopeConnectorBlock.FACING, face)
                        .setValue(
                                RopeConnectorBlock.AXIS_ALONG_FIRST_COORDINATE,
                                state.getValue(RopeConnectorBlock.AXIS_ALONG_FIRST_COORDINATE));
                helper.assertTrue(
                        state.getShape(level, pos)
                                .bounds()
                                .equals(nativeState.getShape(level, pos).bounds()),
                        "Native selection and collision orientation is retained");
                var drops = Block.getDrops(
                        state, level, pos, level.getBlockEntity(pos), player, new ItemStack(Items.IRON_PICKAXE));
                helper.assertTrue(
                        drops.size() == 1 && drops.getFirst().is(ModItems.CHAIN_CONNECTOR.get()),
                        "Breaking drops our connector, not a native rope connector");
                ModBlocks.CHAIN_CONNECTOR.get().onWrenched(state, context(player, pos, face));
                helper.assertTrue(holder(helper, pos) != null, "Wrench rotation preserves behavior");
                level.removeBlock(pos, false);
                level.removeBlock(support, false);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chain_connector_reject", timeoutTicks = 100)
    public static void ropeRejectedInBothOrdersAndAfterFirstSocketIsReplaced(GameTestHelper helper) {
        var chain = anchor(helper, new BlockPos(5, 8, 5), ModBlocks.CHAIN_CONNECTOR.get());
        var nativePos = anchor(helper, new BlockPos(11, 8, 5), SimBlocks.ROPE_CONNECTOR.get());
        var player = player(helper);
        var rope = new ItemStack(SimItems.ROPE_COUPLING.get(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, rope);
        helper.assertTrue(
                click(player, chain) == InteractionResult.FAIL && !rope.has(SimDataComponents.ROPE_FIRST_CONNECTION),
                "Rope cannot select chain socket first");
        click(player, nativePos);
        helper.assertTrue(click(player, chain) == InteractionResult.FAIL, "Rope cannot use chain socket second");
        helper.assertTrue(
                rope.getCount() == 2
                        && !holder(helper, chain).isAttached()
                        && !holder(helper, nativePos).isAttached(),
                "Rejected connection consumes no coil and creates no strand");
        helper.assertTrue(
                !holder(helper, chain).createRope(holder(helper, nativePos), true)
                        && !holder(helper, nativePos).createRope(holder(helper, chain), true),
                "Direct native attachment also obeys the socket material");
        // A saved first point can now refer to a replacement block; validate both ends again.
        rope.set(SimDataComponents.ROPE_FIRST_CONNECTION, chain);
        helper.assertTrue(
                click(player, nativePos) == InteractionResult.FAIL
                        && !rope.has(SimDataComponents.ROPE_FIRST_CONNECTION),
                "Stale first endpoint cannot bypass restriction");
        rope.set(SimDataComponents.ROPE_FIRST_CONNECTION, chain);
        player.setShiftKeyDown(true);
        click(player, chain);
        player.setShiftKeyDown(false);
        helper.assertTrue(
                !rope.has(SimDataComponents.ROPE_FIRST_CONNECTION) && rope.getCount() == 2,
                "Sneak cancellation remains available over chain socket");
        helper.getLevel().removeBlock(chain, false);
        helper.getLevel().removeBlock(nativePos, false);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chain_connector_links", timeoutTicks = 100)
    public static void chainLinksOwnConnectorNativeConnectorAndPoweredWinchInEitherOrder(GameTestHelper helper) {
        var a = anchor(helper, new BlockPos(5, 8, 5), ModBlocks.CHAIN_CONNECTOR.get());
        var player = player(helper);
        for (Block target :
                List.of(ModBlocks.CHAIN_CONNECTOR.get(), SimBlocks.ROPE_CONNECTOR.get(), SimBlocks.ROPE_WINCH.get())) {
            var b = anchor(helper, new BlockPos(11, 8, 5), target);
            for (boolean reverse : List.of(false, true)) {
                var coil = new ItemStack(ModItems.CHAIN_COIL.get(), 2);
                player.setItemInHand(InteractionHand.MAIN_HAND, coil);
                click(player, reverse ? b : a);
                click(player, reverse ? a : b);
                helper.assertTrue(
                        coil.getCount() == 1
                                && ChainConnection.isChain(holder(helper, a))
                                && ChainConnection.isChain(holder(helper, b)),
                        "Both click orders connect as chain and consume one coil");
                if (helper.getLevel().getBlockEntity(b) instanceof RopeWinchBlockEntity winch) {
                    var strand = winch.getRopeHolder().getOwnedStrand();
                    helper.assertTrue(strand != null, "Native winch owns the chain");
                    winch.tick();
                    double before = strand.getExtension() + strand.getPoints().size();
                    winch.setSpeed(64);
                    winch.tick();
                    double extended = strand.getExtension() + strand.getPoints().size();
                    helper.assertTrue(extended > before, "Winch pays out chain from own connector");
                    winch.setSpeed(-64);
                    winch.tick();
                    helper.assertTrue(
                            strand.getExtension() + strand.getPoints().size() < extended, "Winch retracts chain");
                }
                var owner = holder(helper, a).ownsRope() ? holder(helper, a) : holder(helper, b);
                owner.destroyRope(null, null, false);
                helper.assertFalse(holder(helper, b).isAttached(), "Dismantling detaches both endpoints");
            }
            helper.getLevel().removeBlock(b, false);
        }
        helper.getLevel().removeBlock(a, false);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chain_connector_sable", timeoutTicks = 100)
    public static void ownConnectorsKeepTypeAndChainAcrossSaveAndSublevelAssembly(GameTestHelper helper) {
        var level = helper.getLevel();
        var a = anchor(helper, new BlockPos(5, 8, 5), ModBlocks.CHAIN_CONNECTOR.get());
        var b = anchor(helper, new BlockPos(11, 8, 5), ModBlocks.CHAIN_CONNECTOR.get());
        var first = SubLevelAssemblyHelper.assembleBlocks(level, a, List.of(a), new BoundingBox3i(a, a));
        var second = SubLevelAssemblyHelper.assembleBlocks(level, b, List.of(b), new BoundingBox3i(b, b));
        a = first.getPlot().getCenterBlock();
        b = second.getPlot().getCenterBlock();
        var player = player(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CHAIN_COIL.get()));
        click(player, a);
        click(player, b);
        var strand = holder(helper, a).getOwnedStrand();
        helper.assertTrue(
                strand != null
                        && strand.getAttachment(RopeAttachmentPoint.START)
                                .subLevelID()
                                .equals(first.getUniqueId())
                        && strand.getAttachment(RopeAttachmentPoint.END)
                                .subLevelID()
                                .equals(second.getUniqueId()),
                "Native physics attaches both custom connectors in their own sublevels");
        for (var pos : List.of(a, b)) {
            var be = level.getBlockEntity(pos);
            var saved = be.saveWithFullMetadata(level.registryAccess());
            var restored = BlockEntity.loadStatic(pos, be.getBlockState(), saved, level.registryAccess());
            helper.assertTrue(
                    restored instanceof RopeConnectorBlockEntity
                            && restored.getType() == ModBlockEntities.CHAIN_CONNECTOR.get(),
                    "Reload retains custom type");
            var restoredHolder = ((RopeConnectorBlockEntity) restored).getRopeHolder();
            helper.assertTrue(
                    ChainConnection.isChain(restoredHolder)
                            && restoredHolder.ownsRope() == holder(helper, pos).ownsRope(),
                    "Reload preserves chain and native ownership");
        }
        holder(helper, a).destroyRope(null, null, false);
        level.removeBlock(a, false);
        level.removeBlock(b, false);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chain_connector_wrench", timeoutTicks = 100)
    public static void sneakWrenchReturnsOwnConnectorAndChainOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        var a = anchor(helper, new BlockPos(5, 8, 5), ModBlocks.CHAIN_CONNECTOR.get());
        var b = anchor(helper, new BlockPos(11, 8, 5), ModBlocks.CHAIN_CONNECTOR.get());
        var player = player(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CHAIN_COIL.get()));
        click(player, a);
        click(player, b);
        ModBlocks.CHAIN_CONNECTOR.get().onSneakWrenched(level.getBlockState(a), context(player, a, Direction.UP));
        helper.assertTrue(
                level.getBlockState(a).isAir() && !holder(helper, b).isAttached(),
                "Sneak wrench removes socket and detaches other endpoint");
        int chains = player.getInventory().countItem(ModItems.CHAIN_COIL.get());
        int sockets = player.getInventory().countItem(ModItems.CHAIN_CONNECTOR.get());
        for (var item : level.getEntitiesOfClass(ItemEntity.class, new AABB(a.getCenter(), b.getCenter()).inflate(3))) {
            if (item.getItem().is(ModItems.CHAIN_COIL.get())) {
                chains += item.getItem().getCount();
            }
            if (item.getItem().is(ModItems.CHAIN_CONNECTOR.get())) {
                sockets += item.getItem().getCount();
            }
            item.discard();
        }
        helper.assertTrue(chains == 1 && sockets == 1, "Dismantling returns exactly one chain coil and own connector");
        level.removeBlock(b, false);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chain_connector_water", timeoutTicks = 100)
    public static void underwaterPlacementAndBucketsKeepWaterAndChain(GameTestHelper helper) {
        var level = helper.getLevel();
        var block = ModBlocks.CHAIN_CONNECTOR.get();
        var player = player(helper);
        var a = helper.absolutePos(new BlockPos(5, 20, 5));
        level.setBlockAndUpdate(a.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(a, Blocks.WATER.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CHAIN_CONNECTOR.get()));
        helper.assertTrue(click(player, a.below()).consumesAction(), "Connector must place in water");
        var wet = level.getBlockState(a);
        helper.assertTrue(
                wet.is(block)
                        && wet.getValue(ChainConnectorBlock.WATERLOGGED)
                        && wet.getFluidState().isSource(),
                "Underwater placement keeps source water, without air pocket");
        var b = anchor(helper, new BlockPos(11, 20, 5), block);
        var be = level.getBlockEntity(b);
        helper.assertTrue(
                block.placeLiquid(level, b, level.getBlockState(b), Fluids.WATER.getSource(false)),
                "A water bucket can fill a dry connector");
        helper.assertTrue(
                level.getBlockEntity(b) == be && level.getFluidState(b).isSource(),
                "Filling preserves block entity and creates water fluid state");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CHAIN_COIL.get()));
        click(player, a);
        click(player, b);
        helper.assertTrue(
                ChainConnection.isChain(holder(helper, a)) && ChainConnection.isChain(holder(helper, b)),
                "Wet connectors still form native chain connection");
        var bucket = block.pickupBlock(player, level, b, level.getBlockState(b));
        helper.assertTrue(
                bucket.is(Items.WATER_BUCKET)
                        && !level.getBlockState(b).getValue(ChainConnectorBlock.WATERLOGGED)
                        && ChainConnection.isChain(holder(helper, b)),
                "Taking water out preserves the chain");
        helper.assertTrue(
                block.placeLiquid(level, b, level.getBlockState(b), Fluids.WATER.getSource(false)),
                "Connector can be filled again while attached");
        holder(helper, a).destroyRope(null, null, false);
        level.destroyBlock(a, false);
        helper.assertTrue(level.getBlockState(a).is(Blocks.WATER), "Breaking wet connector leaves its water behind");
        level.removeBlock(a, false);
        level.removeBlock(a.below(), false);
        level.removeBlock(b, false);
        helper.succeed();
    }
}
