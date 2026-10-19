package com.cbc_more_content.network;

import com.cbc_more_content.CBCMoreContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * What a bombsight works out, sent a few times a second to the crew around it: where a bomb
 * released now comes down, and, with a target set, how far off the run is and when to drop.
 *
 * @param sight      the sight's block position, which is how the client tells sights apart
 * @param sightWorld where the sight is in the world (on a ship, where the ship has it)
 * @param mode       0 impact point only, 1 run on a target
 * @param rack       whether a bomb rack was found to work the drop out from
 * @param landed     false when the fall leaves the loaded ground, so the point is a guess
 * @param velocity   the carrier's velocity, blocks per tick
 * @param along      blocks still to go along the track to the release point (target mode)
 * @param cross      blocks the target lies to the right of the track (negative: left)
 * @param untilDrop  ticks until release, or -1 when there is no release ahead
 */
public record BombsightSolutionPayload(
        BlockPos sight,
        Vec3 sightWorld,
        byte mode,
        boolean rack,
        boolean landed,
        Vec3 impact,
        int fallTicks,
        Vec3 velocity,
        boolean hasTarget,
        Vec3 target,
        float along,
        float cross,
        int untilDrop,
        boolean releasing,
        boolean autoRelease,
        int seriesTicks,
        boolean centred)
        implements CustomPacketPayload {
    public static final Type<BombsightSolutionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "bombsight_solution"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BombsightSolutionPayload> STREAM_CODEC =
            StreamCodec.of(BombsightSolutionPayload::write, BombsightSolutionPayload::read);

    private static void writeVec(RegistryFriendlyByteBuf buf, Vec3 v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    private static Vec3 readVec(RegistryFriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private static void write(RegistryFriendlyByteBuf buf, BombsightSolutionPayload p) {
        buf.writeBlockPos(p.sight);
        writeVec(buf, p.sightWorld);
        buf.writeByte(p.mode);
        buf.writeBoolean(p.rack);
        buf.writeBoolean(p.landed);
        writeVec(buf, p.impact);
        buf.writeVarInt(p.fallTicks);
        writeVec(buf, p.velocity);
        buf.writeBoolean(p.hasTarget);
        writeVec(buf, p.target);
        buf.writeFloat(p.along);
        buf.writeFloat(p.cross);
        buf.writeVarInt(p.untilDrop + 1);
        buf.writeBoolean(p.releasing);
        buf.writeBoolean(p.autoRelease);
        buf.writeVarInt(p.seriesTicks);
        buf.writeBoolean(p.centred);
    }

    private static BombsightSolutionPayload read(RegistryFriendlyByteBuf buf) {
        return new BombsightSolutionPayload(
                buf.readBlockPos(),
                readVec(buf),
                buf.readByte(),
                buf.readBoolean(),
                buf.readBoolean(),
                readVec(buf),
                buf.readVarInt(),
                readVec(buf),
                buf.readBoolean(),
                readVec(buf),
                buf.readFloat(),
                buf.readFloat(),
                buf.readVarInt() - 1,
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
