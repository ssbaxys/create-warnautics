package com.cbc_more_content;

import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.effects.WarnauticsExplosion;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModItems;
import com.cbc_more_content.util.DropBombUtil;
import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.CreateBigCannons;
import rbasamoyai.createbigcannons.munitions.ShellExplosion;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class CassettePlacementBlastGameTests {
    private static final TicketType<Long> FIXTURE =
            TicketType.create("warnautics_cassette_fixture", Long::compareTo, 80);

    @GameTest(template = "empty", batch = "cassette_item_tnt", timeoutTicks = 100)
    public static void placedInventoryPacksChainFromTnt(GameTestHelper helper) {
        check(helper, false, 0);
    }

    @GameTest(template = "empty", batch = "cassette_item_bomb", timeoutTicks = 100)
    public static void placedInventoryPacksChainFromBomb(GameTestHelper helper) {
        check(helper, false, 1);
    }

    @GameTest(template = "empty", batch = "cassette_item_cbc", timeoutTicks = 100)
    public static void placedInventoryPacksChainFromCbcShell(GameTestHelper helper) {
        check(helper, false, 2);
    }

    @GameTest(template = "empty", batch = "cassette_item_sable_tnt", timeoutTicks = 100)
    public static void placedInventoryPacksChainFromTntOnSable(GameTestHelper helper) {
        check(helper, true, 0);
    }

    @GameTest(template = "empty", batch = "cassette_item_sable_bomb", timeoutTicks = 100)
    public static void placedInventoryPacksChainFromBombOnSable(GameTestHelper helper) {
        check(helper, true, 1);
    }

    @GameTest(template = "empty", batch = "cassette_item_sable_cbc", timeoutTicks = 100)
    public static void placedInventoryPacksChainFromCbcShellOnSable(GameTestHelper helper) {
        check(helper, true, 2);
    }

    private static void check(GameTestHelper helper, boolean assemble, int trigger) {
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(80, 70, 80));
        var items = List.of(
                ModItems.SMALL_BOMB.get(),
                ModItems.SMALL_BOMB_2.get(),
                ModItems.SMALL_BOMB_3.get(),
                ModItems.SMALL_BOMB_4.get());
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "cassette-test"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(base.getCenter().add(0, 30, 0));
        List<Vec3> centers = new ArrayList<>();
        List<BlockPos> blocks = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            var pos = base.east(i * 48);
            level.getChunkSource().addRegionTicket(FIXTURE, new ChunkPos(pos), 2, pos.asLong());
            level.setBlockAndUpdate(pos.below(), Blocks.BEDROCK.defaultBlockState());
            // Raw inventory stacks exercise the item's default, not a preconfigured blockstate.
            var stack = new ItemStack(items.get(i));
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            var result = items.get(i)
                    .useOn(new UseOnContext(
                            player,
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(
                                    pos.below().getCenter().add(0, 0.5, 0), Direction.UP, pos.below(), false)));
            helper.assertTrue(result.consumesAction(), "Inventory pack must place successfully");
            helper.assertTrue(
                    level.getBlockState(pos).getValue(DropBombBlock.CASSETTE) == i + 1,
                    "Item must place the correct number of bombs");
            Vec3 center = pos.getCenter();
            if (assemble) {
                var body = SubLevelAssemblyHelper.assembleBlocks(level, pos, List.of(pos), new BoundingBox3i(pos, pos));
                pos = body.getPlot().getCenterBlock();
                center = body.logicalPose().transformPosition(pos.getCenter());
            }
            blocks.add(pos);
            centers.add(center);
        }
        helper.runAfterDelay(5, () -> {
            for (int i = 0; i < blocks.size(); i++) {
                centers.set(
                        i,
                        SableDropCompat.resolveWorldBlastChecked(
                                        level, blocks.get(i).getCenter())
                                .pos());
            }
            List<Blast> blasts = new ArrayList<>();
            Consumer<ExplosionEvent.Detonate> listener = event -> {
                if (event.getLevel() == level && event.getExplosion() instanceof WarnauticsExplosion explosion) {
                    blasts.add(new Blast(explosion.center(), level.getGameTime()));
                }
            };
            NeoForge.EVENT_BUS.addListener(listener);
            try {
                for (int i = 0; i < centers.size(); i++) {
                    var at = centers.get(i).add(-2, 0, 0);
                    if (trigger == 0) {
                        level.explode(null, at.x, at.y, at.z, 4, Level.ExplosionInteraction.TNT);
                    } else if (trigger == 1) {
                        DropBombUtil.detonateAsReleasedProjectile(BombSize.SMALL, level, at);
                    } else {
                        CreateBigCannons.handleCustomExplosion(
                                level,
                                new ShellExplosion(
                                        level,
                                        null,
                                        null,
                                        at.x,
                                        at.y,
                                        at.z,
                                        4,
                                        4,
                                        false,
                                        Explosion.BlockInteraction.DESTROY));
                    }
                    helper.assertFalse(
                            level.getBlockState(blocks.get(i)).is(ModBlocks.SMALL_BOMB.get()),
                            "Initiating blast must actually destroy pack " + (i + 1));
                }
            } catch (RuntimeException error) {
                NeoForge.EVENT_BUS.unregister(listener);
                blocks.forEach(pos -> level.removeBlock(pos, false));
                throw error;
            }
            helper.runAfterDelay(65, () -> {
                NeoForge.EVENT_BUS.unregister(listener);
                for (int i = 0; i < centers.size(); i++) {
                    Vec3 center = centers.get(i);
                    var sequence = blasts.stream()
                            .filter(blast -> blast.center().distanceToSqr(center) < 0.01)
                            .toList();
                    helper.assertTrue(
                            sequence.size() == i + 1,
                            "Pack " + (i + 1) + " disappeared but produced " + sequence.size() + " blasts; all="
                                    + blasts);
                    for (int j = 1; j < sequence.size(); j++) {
                        helper.assertTrue(
                                sequence.get(j).tick() - sequence.get(j - 1).tick() >= 8,
                                "Each bomb must explode in a later tick");
                    }
                    level.removeBlock(base.east(i * 48).below(), false);
                }
                helper.succeed();
            });
        });
    }

    private record Blast(Vec3 center, long tick) {}
}
