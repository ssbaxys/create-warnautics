package com.cbc_more_content;

import com.cbc_more_content.block.SeaMineBlock;
import com.cbc_more_content.block.SeaMineBlockEntity;
import com.cbc_more_content.effects.WarnauticsExplosion;
import com.cbc_more_content.registry.ModBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class SeaMineMisfireGameTests {
    @GameTest(template = "empty", batch = "sea_misfire_probability", timeoutTicks = 100)
    public static void actualContactFailuresIncreaseWithCorrosion(GameTestHelper helper) {
        var level = helper.getLevel();
        var mine = mine(helper, false);
        int[] failures = new int[4];
        for (int stage = 0; stage < 4; stage++) {
            mine.setCorrosionAge(stage * SeaMineBlockEntity.OXIDATION_TICKS_PER_STAGE);
            level.random.setSeed(9283746L);
            for (int trial = 0; trial < 1000; trial++) {
                var saved = mine.saveWithoutMetadata(level.registryAccess());
                saved.putInt("ArmingTicks", 0);
                saved.putBoolean("Triggered", false);
                saved.remove("LastContactTick");
                mine.loadWithComponents(saved, level.registryAccess());
                mine.trigger();
                if (!mine.saveWithoutMetadata(level.registryAccess()).getBoolean("Triggered")) {
                    failures[stage]++;
                }
            }
        }
        helper.assertTrue(failures[0] == 0, "A fresh armed mine must always work on contact");
        double[] expected = {0, 0.15, 0.35, 0.65};
        for (int stage = 1; stage < 4; stage++) {
            helper.assertTrue(failures[stage] > failures[stage - 1], "Each corrosion stage must be less reliable");
            helper.assertTrue(
                    Math.abs(failures[stage] / 1000.0 - expected[stage]) < 0.06,
                    "Unexpected misfire frequency at stage " + stage + ": " + failures[stage]);
        }
        CBCMoreContent.LOGGER.info(
                "Sea mine contact failures per 1000 trials: {}", java.util.Arrays.toString(failures));
        level.removeBlock(mine.getBlockPos(), false);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "sea_misfire_latch", timeoutTicks = 100)
    public static void misfirePersistsThroughRepeatedContactsAndSaveLoad(GameTestHelper helper) {
        var level = helper.getLevel();
        var mine = mine(helper, false);
        List<Vec3> sounds = new ArrayList<>();
        Consumer<PlayLevelSoundEvent.AtPosition> listener = thuds(sounds);
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            level.random.setSeed(4096L);
            mine.contact(level);
            helper.assertTrue(level.getBlockEntity(mine.getBlockPos()) == mine, "Seeded corroded mine must misfire");
            var saved = mine.saveWithoutMetadata(level.registryAccess());
            mine.loadWithComponents(saved, level.registryAccess());
            // Without the contact latch, this successful roll would explode it immediately.
            level.random.setSeed(0L);
            for (int i = 0; i < 100; i++) {
                mine.contact(level);
            }
            helper.assertTrue(
                    level.getBlockEntity(mine.getBlockPos()) == mine,
                    "One impact must not reroll every physics sub-step");
            helper.assertTrue(sounds.size() == 1, "Misfire plays one dull impact sound, without spam");
            helper.assertFalse(
                    mine.saveWithoutMetadata(level.registryAccess()).getBoolean("Triggered"),
                    "Misfire must not leave a delayed live fuze");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        level.removeBlock(mine.getBlockPos(), false);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "sea_misfire_sable_retry", timeoutTicks = 100)
    public static void separateSableCollisionCanRetryAndThudUsesWorldCoordinates(GameTestHelper helper) {
        var level = helper.getLevel();
        var mine = mine(helper, true);
        var center = SeaMineBlockEntity.worldPosition(level, mine.getBlockPos());
        List<Vec3> sounds = new ArrayList<>();
        Consumer<PlayLevelSoundEvent.AtPosition> listener = thuds(sounds);
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            level.random.setSeed(4096L);
            mine.contact(level);
            helper.assertTrue(
                    sounds.size() == 1 && sounds.getFirst().distanceToSqr(center) < 0.01,
                    "The thud belongs to the mine's world position, never its storage plot");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.runAfterDelay(12, () -> {
            level.random.setSeed(0L);
            mine.contact(level);
            helper.assertFalse(
                    level.getBlockState(mine.getBlockPos()).is(ModBlocks.SEA_MINE.get()),
                    "After separation a new collision gets a new chance and can detonate");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "sea_misfire_explosion", timeoutTicks = 100)
    public static void corrodedMineStillChainsWhenDestroyedByExplosion(GameTestHelper helper) {
        var level = helper.getLevel();
        var mine = mine(helper, false);
        var pos = mine.getBlockPos();
        List<Vec3> blasts = new ArrayList<>();
        Consumer<ExplosionEvent.Detonate> listener = event -> {
            if (event.getExplosion() instanceof WarnauticsExplosion blast) {
                blasts.add(blast.center());
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        var at = pos.getCenter();
        var explosion = new Explosion(level, null, at.x, at.y, at.z, 2, false, Explosion.BlockInteraction.DESTROY);
        explosion.getToBlow().add(pos);
        level.random.setSeed(4096L);
        explosion.finalizeExplosion(false);
        helper.runAfterDelay(12, () -> {
            NeoForge.EVENT_BUS.unregister(listener);
            helper.assertTrue(blasts.size() == 1, "Corrosion only affects the contact fuze, not explosive destruction");
            helper.succeed();
        });
    }

    private static Consumer<PlayLevelSoundEvent.AtPosition> thuds(List<Vec3> sounds) {
        return event -> {
            if (event.getSound() != null && event.getSound().value() == SoundEvents.NETHERITE_BLOCK_HIT) {
                sounds.add(event.getPosition());
            }
        };
    }

    private static SeaMineBlockEntity mine(GameTestHelper helper, boolean assemble) {
        ServerLevel level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(40, 60, 40));
        level.setBlockAndUpdate(
                pos, ModBlocks.SEA_MINE.get().defaultBlockState().setValue(SeaMineBlock.OXIDATION, 3));
        if (assemble) {
            var body = SubLevelAssemblyHelper.assembleBlocks(level, pos, List.of(pos), new BoundingBox3i(pos, pos));
            pos = body.getPlot().getCenterBlock();
        }
        var mine = (SeaMineBlockEntity) level.getBlockEntity(pos);
        CompoundTag saved = mine.saveWithoutMetadata(level.registryAccess());
        saved.putInt("ArmingTicks", 0);
        mine.loadWithComponents(saved, level.registryAccess());
        return mine;
    }
}
