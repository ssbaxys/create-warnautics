package com.cbc_more_content.compat.simulated;

import com.cbc_more_content.compat.AirframeMovement;
import dev.simulated_team.simulated.index.SimBlockMovementChecks;

/** Uses Simulated's own additional-block hook so collecting any cell collects the complete airframe. */
public final class AirframeAssembly {
    private AirframeAssembly() {}

    public static void register() {
        SimBlockMovementChecks.registerAdditionalBlocks(
                (state, level, pos, visited) -> AirframeMovement.cells(level, pos, state).stream()
                        .filter(cell -> !visited.contains(cell))
                        .toList());
    }
}
