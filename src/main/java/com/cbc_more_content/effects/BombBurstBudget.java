package com.cbc_more_content.effects;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.config.WarnauticsConfig;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import rbasamoyai.createbigcannons.CreateBigCannons;
import rbasamoyai.ritchiesprojectilelib.effects.screen_shake.ScreenShakeEffect;

/**
 * Per-dimension burst load tracker for mass simultaneous detonations.
 * Gameplay (crater / damage) stays full; FX / network / raycasts scale down under load.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID)
public final class BombBurstBudget {
    private static final Map<ResourceKey<Level>, DimState> STATES = new HashMap<>();

    private BombBurstBudget() {
    }

    public static Snapshot begin(ServerLevel level) {
        long tick = level.getGameTime();
        DimState state = STATES.computeIfAbsent(level.dimension(), k -> new DimState());
        if (state.gameTick != tick) {
            state.gameTick = tick;
            state.count = 0;
            state.pendingShake.clear();
        }
        state.count++;
        return new Snapshot(state.count, Lod.of(state.count), state);
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (STATES.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<ResourceKey<Level>, DimState>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            DimState state = it.next().getValue();
            if (!state.pendingShake.isEmpty()) {
                flushShakes(state);
            }
            // Drop idle dims so the map stays small.
            if (state.count == 0 && state.pendingShake.isEmpty()) {
                it.remove();
            } else {
                state.count = 0;
            }
        }
    }

    private static void flushShakes(DimState state) {
        for (PendingShake pending : state.pendingShake.values()) {
            if (pending.player.isRemoved() || !pending.player.isAlive()) {
                continue;
            }
            float shake = pending.shake;
            CreateBigCannons.shakePlayerScreen(pending.player,
                    new ScreenShakeEffect(0, shake, shake * 0.55f, shake * 0.55f, 1, 1, 1,
                            pending.x, pending.y, pending.z));
        }
        state.pendingShake.clear();
    }

    public enum Lod {
        /** ≤8 bombs/tick — full FX. */
        FULL,
        /** ≤24 — cut smoke rings / coalesce shake. */
        REDUCED,
        /** ≤60 — minimal particles, skip expensive rays. */
        MINIMAL,
        /** 61+ — sound+cloud only, max vaporize, no war spam. */
        ESSENTIAL;

        static Lod of(int count) {
            if (count <= 8) {
                return FULL;
            }
            if (count <= 24) {
                return REDUCED;
            }
            if (count <= 60) {
                return MINIMAL;
            }
            return ESSENTIAL;
        }

        public boolean fullFx() {
            return this == FULL;
        }

        public boolean allowMushroomRing() {
            return this == FULL || this == REDUCED;
        }

        public boolean allowExtraSmoke() {
            return this == FULL;
        }

        public boolean allowWarRumble(ServerLevel level) {
            return switch (this) {
                case FULL -> true;
                case REDUCED -> level.random.nextFloat() < 0.45f;
                case MINIMAL -> level.random.nextFloat() < 0.18f;
                case ESSENTIAL -> false;
            };
        }

        public int mushroomSectors() {
            return switch (this) {
                case FULL -> 10;
                case REDUCED -> 5;
                default -> 0;
            };
        }

        public float flashScale() {
            float base = switch (this) {
                case FULL -> 1.0f;
                case REDUCED -> 0.88f;
                case MINIMAL -> 0.76f;
                case ESSENTIAL -> 0.65f;
            };
            return base * WarnauticsConfig.blastFxScale();
        }

        /**
         * Smoke puffs are sent per player, so the packet cost is puffs × viewers.
         * The server-side FX scale is applied here to keep every call site honest.
         */
        public int puffCount(int basePuffs) {
            int lodPuffs = switch (this) {
                case FULL -> basePuffs;
                case REDUCED -> Math.max(2, basePuffs / 2);
                case MINIMAL -> Math.max(1, basePuffs / 3);
                case ESSENTIAL -> 1;
            };
            float scale = WarnauticsConfig.blastFxScale();
            if (scale >= 1.0f) {
                return lodPuffs;
            }
            return Math.max(scale <= 0.0f ? 0 : 1, Math.round(lodPuffs * scale));
        }

        public float vaporizeChance() {
            return switch (this) {
                case FULL -> 0.62f;
                case REDUCED -> 0.72f;
                case MINIMAL -> 0.85f;
                case ESSENTIAL -> 0.94f;
            };
        }

        public boolean useExplosionExposureRays() {
            return this == FULL || this == REDUCED;
        }

        /** Soften only under extreme carpet-bombing; crater still digs. */
        public float terrainPowerScale() {
            return switch (this) {
                case FULL, REDUCED -> 1.0f;
                case MINIMAL -> 0.92f;
                case ESSENTIAL -> 0.82f;
            };
        }
    }

    public record Snapshot(int index, Lod lod, DimState state) {
        public void offerShake(ServerPlayer player, float shake, Vec3 pos) {
            if (shake < 0.35f) {
                return;
            }
            // Under load: coalesce to one max shake per player per tick (flushed end-of-tick).
            if (this.lod == Lod.FULL && this.index <= 3) {
                CreateBigCannons.shakePlayerScreen(player,
                        new ScreenShakeEffect(0, shake, shake * 0.55f, shake * 0.55f, 1, 1, 1,
                                pos.x, pos.y, pos.z));
                return;
            }
            UUID id = player.getUUID();
            PendingShake existing = this.state.pendingShake.get(id);
            if (existing == null || shake > existing.shake) {
                this.state.pendingShake.put(id, new PendingShake(player, shake, pos.x, pos.y, pos.z));
            }
        }
    }

    static final class DimState {
        long gameTick = Long.MIN_VALUE;
        int count;
        final Map<UUID, PendingShake> pendingShake = new HashMap<>();
    }

    private record PendingShake(ServerPlayer player, float shake, double x, double y, double z) {
    }
}
