package com.cbc_more_content.block;

import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.munitions.Aim9Projectile;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.registry.ModBlockEntities;
import java.util.Comparator;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class Aim9BlockEntity extends BlockEntity {
    public static final int MIN_RANGE = 40;
    public static final int MAX_RANGE = 220;
    private boolean enabled;
    private boolean interceptCruise = true;
    private int range = 120;
    private long lastScan = Long.MIN_VALUE;
    private Aim9Block.Readiness readiness = Aim9Block.Readiness.READY;

    public Aim9BlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AIM9.get(), pos, state);
    }

    public boolean isLiveAirframe() {
        return !isRemoved()
                && level != null
                && level.getBlockEntity(worldPosition) == this
                && level.getBlockState(worldPosition).getBlock() instanceof Aim9Block
                && level.getBlockState(worldPosition).getValue(Aim9Block.PART) == Aim9Block.Part.BODY;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean interceptCruise() {
        return interceptCruise;
    }

    public int range() {
        return range;
    }

    public Aim9Block.Readiness readiness() {
        return readiness;
    }

    private void report(Aim9Block.Readiness now) {
        if (now != readiness) {
            readiness = now;
            if (level != null) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    public void configure(boolean enabled, boolean interceptCruise, int range) {
        this.lastScan = Long.MIN_VALUE;
        this.enabled = enabled;
        this.interceptCruise = interceptCruise;
        this.range = Math.clamp(range, MIN_RANGE, MAX_RANGE);
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, Aim9BlockEntity be) {
        if (!(level instanceof ServerLevel server)
                || !be.enabled
                || !be.interceptCruise
                || !be.isLiveAirframe()
                || be.lastScan == level.getGameTime()
                || Math.floorMod(level.getGameTime() + pos.asLong(), 10) != 0) {
            return;
        }
        if (state.getValue(Aim9Block.WATERLOGGED)) {
            be.report(Aim9Block.Readiness.FLOODED);
            return;
        }
        be.lastScan = level.getGameTime();
        // Say what is wrong before there is anything to shoot at: a rack under its own
        // superstructure would otherwise just sit there without a word.
        var sky = Aim9Block.skyCheck(server, pos);
        if (sky != Aim9Block.Readiness.READY) {
            be.report(sky);
            return;
        }
        var frame = SableDropCompat.resolveLaunch(server, pos.getCenter(), Vec3.ZERO, new Vec3(0, 1, 0));
        Vec3 origin = frame.pos();
        var claimed = new HashSet<java.util.UUID>();
        for (var interceptor : frame.level()
                .getEntitiesOfClass(
                        Aim9Projectile.class,
                        new AABB(origin, origin).inflate(be.range + MAX_RANGE),
                        e -> e.isAlive())) {
            if (interceptor.targetId() != null) {
                claimed.add(interceptor.targetId());
            }
        }
        var candidates = frame.level()
                .getEntitiesOfClass(
                        CruiseMissileProjectile.class,
                        new AABB(origin, origin).inflate(be.range),
                        missile -> missile.isAlive()
                                && !missile.isEjecting()
                                && !missile.isInWater()
                                && !claimed.contains(missile.getUUID())
                                && missile.position().distanceToSqr(origin) <= be.range * be.range);
        var sorted = candidates.stream()
                .sorted(Comparator.comparingDouble(e -> e.position().distanceToSqr(origin)))
                .toList();
        for (var target : sorted) {
            if (Aim9Block.canLaunch(server, pos, target)) {
                be.report(Aim9Block.Readiness.READY);
                Aim9Block.launch(server, pos, state, target);
                return;
            }
        }
        be.report(sorted.isEmpty() ? Aim9Block.Readiness.READY : Aim9Block.Readiness.NO_SIGHT);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("Enabled", enabled);
        tag.putBoolean("InterceptCruise", interceptCruise);
        tag.putInt("Range", range);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        enabled = tag.getBoolean("Enabled");
        interceptCruise = !tag.contains("InterceptCruise") || tag.getBoolean("InterceptCruise");
        range = tag.contains("Range") ? Math.clamp(tag.getInt("Range"), MIN_RANGE, MAX_RANGE) : 120;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        tag.putString("Readiness", readiness.name());
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        readReadiness(tag);
    }

    @Override
    public void onDataPacket(
            net.minecraft.network.Connection connection,
            ClientboundBlockEntityDataPacket packet,
            HolderLookup.Provider registries) {
        super.onDataPacket(connection, packet, registries);
        readReadiness(packet.getTag());
    }

    private void readReadiness(CompoundTag tag) {
        try {
            readiness = Aim9Block.Readiness.valueOf(tag.getString("Readiness"));
        } catch (IllegalArgumentException e) {
            readiness = Aim9Block.Readiness.READY;
        }
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
