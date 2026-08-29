package com.cbc_more_content.event;

import com.cbc_more_content.bomb.BombSize;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;

public class WarnauticsBlockDetonateEvent extends Event {
    private final ServerLevel level;
    private final Explosion explosion;
    private final Vec3 center;
    private final BombSize size;
    private final List<BlockPos> toBlow;

    public WarnauticsBlockDetonateEvent(ServerLevel level, Explosion explosion, Vec3 center, BombSize size) {
        this.level = level;
        this.explosion = explosion;
        this.center = center;
        this.size = size;
        this.toBlow = explosion.getToBlow();
    }

    public ServerLevel getLevel() {
        return this.level;
    }

    /** The CBC {@code ShellExplosion} backing this blast. */
    public Explosion getExplosion() {
        return this.explosion;
    }

    public Vec3 getCenter() {
        return this.center;
    }

    public BombSize getSize() {
        return this.size;
    }

    /**
     * Live, mutable list of positions the blast is about to destroy. Remove entries
     * to spare individual blocks; the crater cap, core vaporization and
     * {@code finalizeExplosion} all honor the edited list, and the client explosion
     * packet reflects it too.
     */
    public List<BlockPos> getToBlow() {
        return this.toBlow;
    }

    /** Removes {@code pos} from the blast, if present. */
    public boolean veto(BlockPos pos) {
        return this.toBlow.remove(pos);
    }

    /**
     * Removes every listed position the blast contains.
     *
     * @return how many positions were spared
     */
    public int vetoAll(Collection<BlockPos> positions) {
        int vetoed = 0;
        for (BlockPos pos : positions) {
            if (this.toBlow.remove(pos)) {
                vetoed++;
            }
        }
        return vetoed;
    }
}
