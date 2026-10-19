package com.cbc_more_content;

import com.cbc_more_content.block.ClaymoreBlock;
import com.cbc_more_content.item.DetonatorItem;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModItems;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class ClaymoreGameTests {
    private static final TicketType<Long> FIXTURE = TicketType.create("claymore_test", Long::compareTo, 200);
    private static final float TOUGH = 400.0f;
    /** Facing east from the west side of the plot, so there is room in front and some behind. */
    private static final BlockPos MINE = new BlockPos(6, 2, 12);
    /**
     * Range tests stand above the plot: a test plot is walled in with barrier blocks
     * twenty-odd blocks out, which stopped every ball at the edge of the plot.
     */
    private static final BlockPos FIELD = new BlockPos(6, 40, 12);

    private static void ground(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(0, 1, 6), new BlockPos(24, 1, 18))) {
            helper.setBlock(pos, Blocks.STONE);
        }
        BlockPos at = helper.absolutePos(MINE);
        level.getChunkSource().addRegionTicket(FIXTURE, new ChunkPos(at), 3, at.asLong(), true);
    }

    private static BlockState armed(Direction facing) {
        return ModBlocks.CLAYMORE
                .get()
                .defaultBlockState()
                .setValue(ClaymoreBlock.FACING, facing)
                .setValue(ClaymoreBlock.ARMED, true);
    }

    /** A cow that can soak up the whole fan and still be counted. */
    private static Cow target(GameTestHelper helper, BlockPos at) {
        Cow cow = helper.spawn(EntityType.COW, at);
        cow.setNoAi(true);
        cow.setNoGravity(true);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TOUGH);
        cow.setHealth(TOUGH);
        return cow;
    }

    /**
     * Fires an east-facing claymore over open stone and reports what each cow at the given
     * offsets (forward, sideways) lost. {@code build} adds cover before the targets arrive.
     */
    private static void measure(
            GameTestHelper helper,
            String name,
            int[][] spots,
            Runnable build,
            java.util.function.Consumer<float[]> check) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(FIELD);
        // The whole field, out to the farthest cow, has to be ticking its entities.
        for (int x = -1; x <= 4; x++) {
            for (int z = -2; z <= 2; z++) {
                level.setChunkForced((at.getX() >> 4) + x, (at.getZ() >> 4) + z, true);
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(FIELD.offset(-14, -1, -26), FIELD.offset(46, -1, 26))) {
            helper.setBlock(pos, Blocks.STONE);
        }
        build.run();
        Cow[] cows = new Cow[spots.length];
        // Chunks list their entities only once they tick them: give the far ones a moment.
        helper.runAfterDelay(8, () -> {
            helper.setBlock(FIELD, armed(Direction.EAST));
            for (int i = 0; i < spots.length; i++) {
                cows[i] = target(helper, FIELD.offset(spots[i][0], 0, spots[i][1]));
            }
        });
        helper.runAfterDelay(18, () -> helper.setBlock(FIELD.north(), Blocks.REDSTONE_BLOCK));
        helper.runAfterDelay(60, () -> {
            float[] lost = new float[spots.length];
            StringBuilder out = new StringBuilder(name).append(':');
            for (int i = 0; i < spots.length; i++) {
                lost[i] = TOUGH - cows[i].getHealth();
                out.append(String.format(" [%d,%d]=%.0f", spots[i][0], spots[i][1], lost[i]));
                cows[i].discard();
            }
            CBCMoreContent.LOGGER.info(out.toString());
            for (int x = -1; x <= 4; x++) {
                for (int z = -2; z <= 2; z++) {
                    level.setChunkForced((at.getX() >> 4) + x, (at.getZ() >> 4) + z, false);
                }
            }
            check.accept(lost);
            helper.succeed();
        });
    }

    /**
     * Across the open field: deadly well out in front and still dangerous at long range.
     * Every cow stands on its own bearing, so none shelters behind another.
     */
    @GameTest(template = "empty", batch = "claymore_survey", timeoutTicks = 160)
    public static void fanCoversTheFieldInFront(GameTestHelper helper) {
        int[][] spots = {
            {4, -2}, {8, 0}, {12, 3}, {16, -2}, {20, 7}, {25, -6}, {30, 3}, {40, -8}, {20, 11}, {8, 7}, {-4, 0}, {-2, 4}
        };
        measure(helper, "Claymore survey", spots, () -> {}, lost -> {
            for (int i = 0; i <= 4; i++) {
                helper.assertTrue(lost[i] >= 16, "Deadly at " + spots[i][0] + " blocks out, lost " + lost[i]);
            }
            helper.assertTrue(lost[5] >= 8 && lost[6] >= 8, "Twenty-five and thirty blocks out still wound");
            helper.assertTrue(lost[9] <= 4, "Outside the sixty degrees is nearly safe, lost " + lost[9]);
            helper.assertTrue(lost[10] <= 2 && lost[11] <= 4, "Behind it only the back-blast reaches");
        });
    }

    /** Cover works: a stone wall stops the balls, and the open lane beside it does not. */
    @GameTest(template = "empty", batch = "claymore_cover", timeoutTicks = 160)
    public static void wallsStopTheBalls(GameTestHelper helper) {
        int[][] spots = {{10, -4}, {10, 0}, {10, 4}};
        measure(
                helper,
                "Claymore cover",
                spots,
                () -> {
                    for (int y = 0; y <= 2; y++) {
                        for (int z = -8; z <= -1; z++) {
                            helper.setBlock(FIELD.offset(5, y, z), Blocks.STONE_BRICKS);
                        }
                        for (int z = 1; z <= 8; z++) {
                            helper.setBlock(FIELD.offset(5, y, z), Blocks.OAK_PLANKS);
                        }
                    }
                },
                lost -> {
                    helper.assertTrue(lost[0] <= 2, "Behind a stone wall is safe, lost " + lost[0]);
                    helper.assertTrue(lost[1] >= 16, "In the open lane between, lost " + lost[1]);
                    helper.assertTrue(lost[2] <= 2, "Behind a plank wall is safe too, lost " + lost[2]);
                });
    }

    @GameTest(template = "empty", batch = "claymore_fan", timeoutTicks = 120)
    public static void ballsGoForwardNotBack(GameTestHelper helper) {
        ground(helper);
        helper.setBlock(MINE, armed(Direction.EAST));
        Cow front = target(helper, MINE.east(9));
        Cow beside = target(helper, MINE.south(7));
        Cow behind = target(helper, MINE.west(5));
        // The firing wire: any redstone reaching it.
        helper.runAfterDelay(5, () -> helper.setBlock(MINE.north(), Blocks.REDSTONE_BLOCK));
        helper.runAfterDelay(40, () -> {
            helper.assertBlockNotPresent(ModBlocks.CLAYMORE.get(), MINE);
            float frontLoss = TOUGH - front.getHealth();
            float sideLoss = TOUGH - beside.getHealth();
            float rearLoss = TOUGH - behind.getHealth();
            helper.assertTrue(
                    frontLoss >= 20.0f, "Something nine blocks in front takes several balls, lost " + frontLoss);
            helper.assertTrue(rearLoss <= 2.0f, "Five blocks behind is safe, lost " + rearLoss);
            helper.assertTrue(sideLoss <= 2.0f, "Seven blocks off to the side is outside the fan, lost " + sideLoss);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "claymore_arming", timeoutTicks = 160)
    public static void armsAfterTheDelayAndOnlyThenFires(GameTestHelper helper) {
        ground(helper);
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "claymore-test"));
        player.setGameMode(GameType.SURVIVAL);
        player.setYRot(-90.0f);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CLAYMORE.get()));
        BlockPos floor = helper.absolutePos(MINE.below());
        player.getMainHandItem()
                .useOn(new UseOnContext(
                        player,
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(floor.getCenter(), Direction.UP, floor, false)));
        helper.assertBlockPresent(ModBlocks.CLAYMORE.get(), MINE);
        BlockState placed = helper.getBlockState(MINE);
        helper.assertTrue(
                placed.getValue(ClaymoreBlock.FACING) == Direction.EAST,
                "Front toward the enemy: the way the player looked, got " + placed.getValue(ClaymoreBlock.FACING));
        helper.assertFalse(placed.getValue(ClaymoreBlock.ARMED), "Not live the moment it is set down");
        // A signal while it is still safe does nothing.
        helper.setBlock(MINE.north(), Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(10, () -> {
            helper.assertBlockPresent(ModBlocks.CLAYMORE.get(), MINE);
            helper.setBlock(MINE.north(), Blocks.AIR);
        });
        helper.runAfterDelay(ClaymoreBlock.ARM_DELAY_TICKS + 5, () -> {
            helper.assertTrue(helper.getBlockState(MINE).getValue(ClaymoreBlock.ARMED), "Armed after the delay");
            helper.setBlock(MINE.north(), Blocks.REDSTONE_BLOCK);
            helper.assertBlockNotPresent(ModBlocks.CLAYMORE.get(), MINE);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "claymore_detonator", timeoutTicks = 100)
    public static void detonatorPairsAndFiresIt(GameTestHelper helper) {
        ground(helper);
        helper.setBlock(MINE, armed(Direction.EAST));
        BlockPos at = helper.absolutePos(MINE);
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "clacker-test"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(MINE.west(4).getCenter()));
        ItemStack detonator = new ItemStack(ModItems.DETONATOR.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, detonator);
        detonator.useOn(new UseOnContext(
                player, InteractionHand.MAIN_HAND, new BlockHitResult(at.getCenter(), Direction.UP, at, false)));
        helper.assertTrue(DetonatorItem.boundCharges(detonator).contains(at), "Claymore pairs with the detonator");
        detonator.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertBlockNotPresent(ModBlocks.CLAYMORE.get(), MINE);
        helper.assertTrue(DetonatorItem.boundCharges(detonator).isEmpty(), "Fired charges leave the ring");
        helper.succeed();
    }

    /**
     * Pressed while the claymore is still arming, the detonator does not fire it and does not
     * forget it either: it stays paired and goes off on the next press once armed.
     */
    @GameTest(template = "empty", batch = "claymore_detonator_arming", timeoutTicks = 120)
    public static void detonatorKeepsAClaymoreThatIsStillArming(GameTestHelper helper) {
        ground(helper);
        helper.setBlock(
                MINE, ModBlocks.CLAYMORE.get().defaultBlockState().setValue(ClaymoreBlock.FACING, Direction.EAST));
        BlockPos at = helper.absolutePos(MINE);
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "early-clacker"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(MINE.west(4).getCenter()));
        ItemStack detonator = new ItemStack(ModItems.DETONATOR.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, detonator);
        detonator.useOn(new UseOnContext(
                player, InteractionHand.MAIN_HAND, new BlockHitResult(at.getCenter(), Direction.UP, at, false)));
        detonator.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertBlockPresent(ModBlocks.CLAYMORE.get(), MINE);
        helper.assertTrue(DetonatorItem.boundCharges(detonator).contains(at), "Still paired while it arms");
        helper.runAfterDelay(ClaymoreBlock.ARM_DELAY_TICKS + 5, () -> {
            player.getCooldowns().removeCooldown(detonator.getItem());
            detonator.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertBlockNotPresent(ModBlocks.CLAYMORE.get(), MINE);
            helper.succeed();
        });
    }

    /**
     * Someone in a full suit of iron fifteen blocks in front of it does not walk away: the
     * balls land as one blow, which armour only takes a little off.
     */
    @GameTest(template = "empty", batch = "claymore_armour", timeoutTicks = 100)
    public static void ironArmourDoesNotSaveYouAtFifteenBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(FIELD);
        for (int x = -1; x <= 2; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setChunkForced((at.getX() >> 4) + x, (at.getZ() >> 4) + z, true);
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(FIELD.offset(-4, -1, -6), FIELD.offset(20, -1, 6))) {
            helper.setBlock(pos, Blocks.STONE);
        }
        net.minecraft.world.entity.monster.Zombie[] soldier = {null};
        helper.runAfterDelay(8, () -> {
            helper.setBlock(FIELD, armed(Direction.EAST));
            var zombie = helper.spawn(EntityType.ZOMBIE, FIELD.east(15));
            zombie.setNoAi(true);
            zombie.setNoGravity(true);
            zombie.setItemSlot(
                    net.minecraft.world.entity.EquipmentSlot.HEAD,
                    new ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
            zombie.setItemSlot(
                    net.minecraft.world.entity.EquipmentSlot.CHEST,
                    new ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
            zombie.setItemSlot(
                    net.minecraft.world.entity.EquipmentSlot.LEGS,
                    new ItemStack(net.minecraft.world.item.Items.IRON_LEGGINGS));
            zombie.setItemSlot(
                    net.minecraft.world.entity.EquipmentSlot.FEET,
                    new ItemStack(net.minecraft.world.item.Items.IRON_BOOTS));
            soldier[0] = zombie;
        });
        helper.runAfterDelay(14, () -> helper.setBlock(FIELD.north(), Blocks.REDSTONE_BLOCK));
        helper.runAfterDelay(50, () -> {
            helper.assertTrue(
                    !soldier[0].isAlive() || soldier[0].getHealth() <= 0,
                    "An armoured man at fifteen blocks is killed, health left " + soldier[0].getHealth());
            for (int x = -1; x <= 2; x++) {
                for (int z = -1; z <= 1; z++) {
                    level.setChunkForced((at.getX() >> 4) + x, (at.getZ() >> 4) + z, false);
                }
            }
            helper.succeed();
        });
    }

    /** A line of claymores set and then fired together has to stay inside a tick budget. */
    @GameTest(template = "empty", batch = "claymore_cost", timeoutTicks = 120)
    public static void aRowFiredTogetherStaysCheap(GameTestHelper helper) {
        ground(helper);
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "row-test"));
        player.setGameMode(GameType.CREATIVE);
        player.setYRot(-90.0f);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CLAYMORE.get(), 16));
        int count = 12;
        long placeStart = System.nanoTime();
        for (int i = 0; i < count; i++) {
            BlockPos floor = helper.absolutePos(new BlockPos(4, 1, 6 + i));
            player.getMainHandItem()
                    .useOn(new UseOnContext(
                            player,
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(floor.getCenter(), Direction.UP, floor, false)));
        }
        double placeMs = (System.nanoTime() - placeStart) / 1.0e6 / count;
        for (int i = 0; i < count; i++) {
            helper.assertBlockPresent(ModBlocks.CLAYMORE.get(), new BlockPos(4, 2, 6 + i));
        }
        helper.runAfterDelay(ClaymoreBlock.ARM_DELAY_TICKS + 2, () -> {
            ServerLevel level = helper.getLevel();
            long fireStart = System.nanoTime();
            for (int i = 0; i < count; i++) {
                ClaymoreBlock.fireRemote(level, helper.absolutePos(new BlockPos(4, 2, 6 + i)));
            }
            double fireMs = (System.nanoTime() - fireStart) / 1.0e6;
            long flightStart = System.nanoTime();
            helper.runAfterDelay(1, () -> {
                // One tick of every burst in flight, measured as the time the whole tick took.
                double tickMs = (System.nanoTime() - flightStart) / 1.0e6;
                CBCMoreContent.LOGGER.info(
                        "Claymore cost: place {} ms each, fire {} together {} ms, first flight tick {} ms",
                        String.format("%.3f", placeMs),
                        count,
                        String.format("%.2f", fireMs),
                        String.format("%.2f", tickMs));
                helper.assertTrue(placeMs < 5.0D, "Setting one down costs " + placeMs + " ms");
                helper.assertTrue(fireMs < 150.0D, "Firing the row costs " + fireMs + " ms");
                helper.succeed();
            });
        });
    }

    /** A small deck afloat in a basin, as a ship sits in the game. Returns the ship. */
    private static dev.ryanhcode.sable.sublevel.ServerSubLevel afloat(GameTestHelper helper, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(relative);
        level.getChunkSource().addRegionTicket(FIXTURE, new ChunkPos(center), 4, center.asLong(), true);
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-8, -5, -8), center.offset(8, -1, 8))) {
            boolean wall = Math.abs(p.getX() - center.getX()) == 8
                    || Math.abs(p.getZ() - center.getZ()) == 8
                    || p.getY() == center.getY() - 5;
            level.setBlock(p, wall ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState(), 2);
        }
        var cells = new java.util.ArrayList<BlockPos>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-2, 0, -2), center.offset(2, 0, 2))) {
            level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), 2);
            cells.add(p.immutable());
        }
        return dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(
                level,
                center,
                cells,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(center.offset(-2, 0, -2), center.offset(2, 0, 2)));
    }

    private static Cow worldTarget(GameTestHelper helper, net.minecraft.world.phys.Vec3 at) {
        Cow cow = EntityType.COW.create(helper.getLevel());
        cow.setPos(at);
        cow.setNoAi(true);
        cow.setNoGravity(true);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TOUGH);
        cow.setHealth(TOUGH);
        helper.getLevel().addFreshEntity(cow);
        return cow;
    }

    /**
     * On a ship turned a quarter round, the balls go where the ship now points the plate,
     * not where the block's own facing says in the ship's grid.
     */
    @GameTest(template = "empty", batch = "claymore_ship_turned", timeoutTicks = 200)
    public static void onATurnedShipItFiresWhereTheShipFaces(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var ship = afloat(helper, new BlockPos(12, 8, 12));
        BlockPos deck = ship.getPlot().getCenterBlock();
        Cow[] cows = new Cow[2];
        helper.runAfterDelay(10, () -> {
            var at = ship.logicalPose().transformPosition(deck.getCenter());
            dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(ship)
                    .teleport(new org.joml.Vector3d(at.x, at.y, at.z), new org.joml.Quaterniond().rotateY(Math.PI / 2));
        });
        helper.runAfterDelay(16, () -> {
            BlockPos mine = deck.above();
            level.setBlock(mine, armed(Direction.EAST), 3);
            var frame = com.cbc_more_content.compat.SableDropCompat.resolveLaunch(
                    level,
                    mine.getCenter(),
                    net.minecraft.world.phys.Vec3.ZERO,
                    new net.minecraft.world.phys.Vec3(1, 0, 0));
            var foot = frame.pos().subtract(0, 0.5, 0);
            var turned = new net.minecraft.world.phys.Vec3(frame.orientation().x, 0, frame.orientation().z).normalize();
            helper.assertTrue(
                    Math.abs(turned.x) < 0.2, "The ship really is turned off east, facing " + frame.orientation());
            cows[0] = worldTarget(helper, foot.add(turned.scale(10)));
            cows[1] = worldTarget(helper, foot.add(10, 0, 0));
        });
        helper.runAfterDelay(
                22, () -> level.setBlock(deck.above().north(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3));
        helper.runAfterDelay(60, () -> {
            float front = TOUGH - cows[0].getHealth();
            float stale = TOUGH - cows[1].getHealth();
            CBCMoreContent.LOGGER.info("Claymore on a turned ship: front {} old-east {}", front, stale);
            helper.assertTrue(front >= 16, "Whoever the turned plate faces is hit, lost " + front);
            helper.assertTrue(stale <= 2, "The ship-grid east is no longer in front, lost " + stale);
            cows[0].discard();
            cows[1].discard();
            helper.succeed();
        });
    }

    /** The firing device reaches a claymore on a ship by where the ship is, not by its plot. */
    @GameTest(template = "empty", batch = "claymore_ship_detonator", timeoutTicks = 120)
    public static void detonatorReachesAClaymoreOnAShip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var ship = afloat(helper, new BlockPos(12, 8, 12));
        BlockPos deck = ship.getPlot().getCenterBlock();
        helper.runAfterDelay(10, () -> {
            BlockPos mine = deck.above();
            level.setBlock(mine, armed(Direction.EAST), 3);
            FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "deck-clacker"));
            player.setGameMode(GameType.SURVIVAL);
            var world = ship.logicalPose().transformPosition(mine.getCenter());
            player.setPos(world.add(-4, 0, 0));
            ItemStack detonator = new ItemStack(ModItems.DETONATOR.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, detonator);
            detonator.useOn(new UseOnContext(
                    player,
                    InteractionHand.MAIN_HAND,
                    new BlockHitResult(mine.getCenter(), Direction.UP, mine, false)));
            helper.assertTrue(DetonatorItem.boundCharges(detonator).contains(mine), "Pairs with the claymore on deck");
            detonator.use(level, player, InteractionHand.MAIN_HAND);
            helper.assertFalse(
                    level.getBlockState(mine).getBlock() instanceof ClaymoreBlock,
                    "The claymore on the ship went off, not out of range");
            helper.succeed();
        });
    }
}
