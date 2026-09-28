package com.cbc_more_content.block;

import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.effects.BombSympatheticDetonation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.extensions.IBlockExtension;

/** Vanilla's destruction callback runs after explosion protection has filtered the affected blocks. */
public interface ChainExplosiveBlock extends IBlockExtension {
    void detonateCharge(ServerLevel level, Vec3 worldCenter, BlockState state);

    default int chargeCount(BlockState state) {
        return 1;
    }

    default BlockPos explosionAnchor(BlockState state, BlockPos pos) {
        return pos;
    }

    default List<BlockPos> explosionParts(BlockState state, BlockPos anchor) {
        return List.of(anchor);
    }

    @Override
    default boolean canDropFromExplosion(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
        return false;
    }

    @Override
    default void onBlockExploded(BlockState state, Level level, BlockPos pos, Explosion explosion) {
        if (!(level instanceof ServerLevel server)
                || explosion.getBlockInteraction() == Explosion.BlockInteraction.KEEP
                || explosion.getBlockInteraction() == Explosion.BlockInteraction.TRIGGER_BLOCK
                || !level.getBlockState(pos).is(state.getBlock())) {
            return;
        }
        BlockPos anchor = explosionAnchor(state, pos);
        // Capture the pose while the Sable body still exists, including when this is its last block.
        var target = SableDropCompat.resolveWorldBlastChecked(server, anchor.getCenter());
        Map<BlockPos, BlockState> parts = new LinkedHashMap<>();
        for (BlockPos cell : explosionParts(state, anchor)) {
            BlockState current = level.getBlockState(cell);
            if (current.is(state.getBlock()) && explosionAnchor(current, cell).equals(anchor)) {
                parts.put(cell.immutable(), current);
            }
        }
        // Consume the complete charge before neighbour updates or another callback:
        // a three-cell MOAB/missile has one warhead and cannot also drop a live item.
        parts.forEach((cell, current) -> level.setBlock(
                cell, current.getFluidState().createLegacyBlock(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        int count = chargeCount(state);
        Runnable detonation = () -> detonateCharge(target.level(), target.pos(), state);
        if (count > 1) {
            BombSympatheticDetonation.scheduleDestroyedCharges(target.level(), target.pos(), count, detonation);
        } else {
            BombSympatheticDetonation.scheduleDestroyedCharge(target.level(), target.pos(), detonation);
        }
        parts.forEach((cell, current) -> {
            level.getBlockState(cell).updateNeighbourShapes(level, cell, Block.UPDATE_ALL);
            level.updateNeighborsAt(cell, current.getBlock());
        });
    }
}
