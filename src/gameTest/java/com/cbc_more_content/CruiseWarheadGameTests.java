package com.cbc_more_content;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.effects.WarnauticsExplosion;
import com.cbc_more_content.event.WarnauticsBlockDetonateEvent;
import com.cbc_more_content.registry.ModEntityTypes;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class CruiseWarheadGameTests {
    @GameTest(template = "empty", batch = "cruise_warhead", timeoutTicks = 120)
    public static void actualMissileUsesMoabProfileWithLowerPowerAndDamage(GameTestHelper helper) {
        var level = helper.getLevel();
        var top = helper.absolutePos(new BlockPos(100, 80, 100));
        for (var p : BlockPos.betweenClosed(top.offset(-12, -4, -12), top.offset(12, 0, 12))) {
            level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        var origin = top.getCenter().add(0, 0.8, 0);
        var target = EntityType.COW.create(level);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        target.setHealth(1000);
        target.setNoAi(true);
        var targetPos = origin.add(8, 6, 0);
        target.setPos(targetPos);
        level.getChunkAt(BlockPos.containing(targetPos));
        level.addFreshEntity(target);
        var missile = ModEntityTypes.CRUISE_MISSILE.get().create(level);
        missile.setPos(origin);
        level.addFreshEntity(missile);
        Set<BlockPos> destroyed = new HashSet<>();
        int[] events = {0};
        Consumer<WarnauticsBlockDetonateEvent> listener = event -> {
            if (event.getExplosion().getDirectSourceEntity() != missile) {
                return;
            }
            events[0]++;
            helper.assertTrue(event.getSize() == BombSize.MOAB, "Missile must use MOAB crater, smoke and wave profile");
            var blast = (WarnauticsExplosion) event.getExplosion();
            helper.assertTrue(
                    blast.affectedEntities().contains(target), "Test target must be present in the loaded blast scene");
            float moabRadius = BombSize.MOAB.blastVolume().shellPowerForSameVolume(BombSize.MOAB.blockBlastPower);
            helper.assertTrue(
                    Math.abs(blast.radius() / moabRadius - 0.85) < 0.0001, "Block power is 15 percent below MOAB");
            helper.assertTrue(
                    Math.abs(blast.getEntityRadius() / BombSize.MOAB.entityBlastPower - 0.85) < 0.0001,
                    "Entity power is 15 percent below MOAB");
            destroyed.addAll(blast.destroyedBlocks());
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            level.random.setSeed(193847L);
            missile.hurt(level.damageSources().generic(), 1);
            missile.hurt(level.damageSources().generic(), 1);
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.assertTrue(events[0] == 1 && missile.isRemoved(), "Missile detonates once and is removed");
        helper.assertFalse(destroyed.isEmpty(), "Real missile removes terrain");
        for (var pos : destroyed) {
            helper.assertTrue(level.getBlockState(pos).isAir(), "Crater is actually applied to the world");
        }
        float missileDamage = 1000 - target.getHealth();
        target.setHealth(1000);
        target.invulnerableTime = 0;
        target.setPos(targetPos);
        BombExplosionHandler.detonate(
                level,
                null,
                BombDamageSource.create(level),
                origin,
                BombSize.MOAB.blockBlastPower,
                BombSize.MOAB.entityBlastPower,
                BombSize.MOAB);
        float moabDamage = 1000 - target.getHealth();
        helper.assertTrue(
                missileDamage > 0 && missileDamage < moabDamage,
                "Missile damage must remain below MOAB at the same range: missile=" + missileDamage + " MOAB="
                        + moabDamage);
        target.discard();
        for (var p : BlockPos.betweenClosed(top.offset(-12, -4, -12), top.offset(12, 0, 12))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        helper.succeed();
    }
}
