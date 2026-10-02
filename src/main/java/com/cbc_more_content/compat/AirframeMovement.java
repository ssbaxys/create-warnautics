package com.cbc_more_content.compat;

import com.cbc_more_content.block.Aim9Block;
import com.cbc_more_content.block.CruiseMissileBlock;
import com.cbc_more_content.block.MoabBlock;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** The three cells of an airframe are attached to each other, independently of external glue. */
public final class AirframeMovement {
    private AirframeMovement() {}

    public static List<BlockPos> cells(Level level, BlockPos pos, BlockState state) {
        BlockPos body = bodyOf(state, pos);
        if (body == null) {
            return List.of();
        }
        Direction facing = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
        var result = new ArrayList<BlockPos>(3);
        for (int offset = -1; offset <= 1; offset++) {
            BlockPos cell = body.relative(facing, offset);
            BlockState candidate = level.getBlockState(cell);
            if (candidate.is(state.getBlock())
                    && body.equals(bodyOf(candidate, cell))
                    && candidate.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)
                            == facing) {
                result.add(cell);
            }
        }
        return result;
    }

    private static BlockPos bodyOf(BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof Aim9Block) {
            return Aim9Block.bodyOf(state, pos);
        }
        if (state.getBlock() instanceof CruiseMissileBlock) {
            return CruiseMissileBlock.bodyOf(state, pos);
        }
        if (state.getBlock() instanceof MoabBlock) {
            return MoabBlock.bodyOf(state, pos);
        }
        return null;
    }

    public static void register() {
        BlockMovementChecks.registerAttachedCheck(
                (state, level, pos, direction) -> cells(level, pos, state).contains(pos.relative(direction))
                        ? BlockMovementChecks.CheckResult.SUCCESS
                        : BlockMovementChecks.CheckResult.PASS);
    }
}
