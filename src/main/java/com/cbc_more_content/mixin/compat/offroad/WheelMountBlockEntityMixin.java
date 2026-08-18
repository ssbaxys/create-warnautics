package com.cbc_more_content.mixin.compat.offroad;

import java.lang.reflect.Method;

import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.cbc_more_content.block.LandMineBlock;

import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

/**
 * Offroad tire raycast: large land mines arm on wheel contact.
 * <p>
 * The cast often reports the block <em>under</em> a thin mine (rays slip through the
 * inset collision disc), so we also probe {@code hit.above()}. When the wheel lives on a
 * Sable sub-level, the mine may sit in the parent world — probe both levels.
 */
@Mixin(WheelMountBlockEntity.class)
public class WheelMountBlockEntityMixin {
    @Inject(
            method = "computeMaxExtensionToTerrain",
            at = @At("RETURN"))
    private void cbc_more_content$onTerrainCast(
            Vector3dc normalD,
            Pose3dc pose,
            CallbackInfoReturnable<?> cir) {
        Object result = cir.getReturnValue();
        if (result == null) {
            return;
        }

        BlockPos hit = readMinInteractingBlock(result);
        if (hit == null) {
            return;
        }

        WheelMountBlockEntity self = (WheelMountBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (!(level instanceof ServerLevel serverLevel) || level.isClientSide) {
            return;
        }

        probeMine(serverLevel, hit);
        probeMine(serverLevel, hit.above());

        ServerLevel parent = parentWorld(serverLevel);
        if (parent != null && parent != serverLevel) {
            probeMine(parent, hit);
            probeMine(parent, hit.above());
        }
    }

    private static void probeMine(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof LandMineBlock mine
                && mine.getMineType().vehicleTrigger
                && state.getValue(LandMineBlock.ARMED)) {
            LandMineBlock.scheduleVehicleDetonate(level, pos);
        }
    }

    private static ServerLevel parentWorld(ServerLevel level) {
        if (!ModList.get().isLoaded("sable")) {
            return null;
        }
        try {
            // SubLevel#getLevel() → parent ServerLevel
            Method getLevel = level.getClass().getMethod("getLevel");
            Object parent = getLevel.invoke(level);
            if (parent instanceof ServerLevel serverParent && parent != level) {
                return serverParent;
            }
        } catch (ReflectiveOperationException ignored) {
            // Not a SubLevel (or API changed) — ignore.
        }
        return null;
    }

    private static BlockPos readMinInteractingBlock(Object terrainCastResult) {
        try {
            Object hit = terrainCastResult.getClass().getMethod("minInteractingBlock").invoke(terrainCastResult);
            return hit instanceof BlockPos pos ? pos : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
