package com.cbc_more_content.effects;

import javax.annotation.Nullable;

import com.cbc_more_content.event.WarnauticsBlockChipEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import rbasamoyai.createbigcannons.CreateBigCannons;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.index.CBCEntityTypes;
import rbasamoyai.createbigcannons.munitions.big_cannon.shrapnel.ShrapnelBurst;
import rbasamoyai.createbigcannons.munitions.big_cannon.shrapnel.ShrapnelExplosion;
import rbasamoyai.createbigcannons.munitions.fragment_burst.CBCProjectileBurst;

/**
 * Mine-specific blast profiles. Infantry mines use CBC shrapnel instead of an
 * HE crater: the burst's fragment collision code applies CBC partial block
 * damage, so nearby soil can be chipped without deleting a circular volume.
 */
public final class MineExplosionHandler {
    private static final int SMALL_MINE_FRAGMENT_COUNT = 36;
    private static final double SMALL_MINE_FRAGMENT_SPREAD = 2.15D;

    public static final String MINE_BURST_TAG = "warnautics_mine_burst";

    private MineExplosionHandler() {
    }

    public static void detonateSmallShrapnel(
            ServerLevel level,
            @Nullable Entity source,
            DamageSource damageSource,
            Vec3 pos,
            float entityPower) {
        ShrapnelExplosion pressure = new ShrapnelExplosion(
                level,
                source,
                damageSource,
                pos.x,
                pos.y,
                pos.z,
                0.0f,
                entityPower,
                CBCConfigs.server().munitions.damageRestriction.get().explosiveInteraction());
        CreateBigCannons.handleCustomExplosion(level, pressure);

        // CBC uses the zero block radius above for its entity lookup too, so the
        // pressure explosion itself cannot find the player standing on the mine.
        BombExplosionHandler.applySmallMinePressureDamage(
                level, damageSource, pos, entityPower);
        
        ShrapnelBurst burst = CBCProjectileBurst.spawnConeBurst(
                level,
                CBCEntityTypes.SHRAPNEL_BURST.get(),
                pos.add(0.0D, 0.18D, 0.0D),
                new Vec3(0.0D, 0.62D, 0.0D),
                SMALL_MINE_FRAGMENT_COUNT,
                SMALL_MINE_FRAGMENT_SPREAD);
        burst.getPersistentData().putBoolean(MINE_BURST_TAG, true);

        // Keep CBC's shrapnel physics untouched; these two short metallic layers
        // only make the fragment fan readable instead of sounding like plain HE.
        level.playSound(null, pos.x, pos.y + 0.15D, pos.z,
                SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 2.2f, 1.42f);
        level.playSound(null, pos.x, pos.y + 0.15D, pos.z,
                SoundEvents.IRON_GOLEM_DAMAGE, SoundSource.BLOCKS, 1.15f, 1.72f);
    }

    public static boolean mayChipBlock(ServerLevel level, BlockPos pos, Vec3 origin) {
        WarnauticsBlockChipEvent event = new WarnauticsBlockChipEvent(level, pos, origin);
        NeoForge.EVENT_BUS.post(event);
        return !event.isCanceled();
    }
}
