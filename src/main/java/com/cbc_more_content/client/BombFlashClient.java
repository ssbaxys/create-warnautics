package com.cbc_more_content.client;

import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.network.BombFlashPayload;
import com.cbc_more_content.util.ReflectiveDispatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

/**
 * Active bomb-flash pulses (client-only). Veil path owns real lights + post bloom.
 */
@OnlyIn(Dist.CLIENT)
public final class BombFlashClient {
    private static final int MAX_ACTIVE_FLASHES = 12;
    private static final int MERGE_WINDOW_TICKS = 4;
    private static final List<Flash> FLASHES = new ArrayList<>();
    private static final List<Flash> READ_ONLY_FLASHES = Collections.unmodifiableList(FLASHES);

    private BombFlashClient() {}

    public static void handle(BombFlashPayload payload) {
        if (!Double.isFinite(payload.x())
                || !Double.isFinite(payload.y())
                || !Double.isFinite(payload.z())
                || !Float.isFinite(payload.intensity())) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        BombSize[] sizes = BombSize.values();
        int idx = Mth.clamp(payload.sizeOrdinal() & 0xFF, 0, sizes.length - 1);
        BombSize size = sizes[idx];
        int life = lifeFor(size);
        float intensity = Mth.clamp(payload.intensity(), 0.15f, 1.65f);
        Vec3 pos = new Vec3(payload.x(), payload.y(), payload.z());

        Flash mergeTarget = findMergeTarget(level, pos, size);
        if (mergeTarget != null) {
            mergeTarget.merge(pos, intensity, life, size);
            return;
        }

        if (FLASHES.size() >= MAX_ACTIVE_FLASHES) {
            expireWeakestFlash();
        }
        Flash flash = new Flash(level, pos, intensity, life, size);
        FLASHES.add(flash);

        if (ModList.get().isLoaded("veil")) {
            ReflectiveDispatcher.invoke(
                    "com.cbc_more_content.client.veil.VeilBombFx", "onFlash", new Class<?>[] {Flash.class}, flash);
        }
    }

    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        Iterator<Flash> it = FLASHES.iterator();
        while (it.hasNext()) {
            Flash flash = it.next();
            if (level == null || flash.level != level) {
                flash.expire();
                it.remove();
                continue;
            }
            flash.age++;
            if (flash.age >= flash.life) {
                it.remove();
            }
        }
    }

    public static List<Flash> flashes() {
        return READ_ONLY_FLASHES;
    }

    private static Flash findMergeTarget(ClientLevel level, Vec3 pos, BombSize size) {
        double mergeRadius =
                switch (size) {
                    case SMALL -> 5.0D;
                    case SEA -> 6.0D;
                    case MEDIUM -> 8.0D;
                    case LARGE -> 12.0D;
                };
        double mergeRadiusSqr = mergeRadius * mergeRadius;
        Flash best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Flash flash : FLASHES) {
            if (flash.level != level || flash.age > MERGE_WINDOW_TICKS) {
                continue;
            }
            double distance = flash.pos.distanceToSqr(pos);
            if (distance <= mergeRadiusSqr && distance < bestDistance) {
                best = flash;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static void expireWeakestFlash() {
        Flash weakest = null;
        float weakestPriority = Float.MAX_VALUE;
        for (Flash flash : FLASHES) {
            float priority = flash.intensity * flash.fade(0.0f);
            if (priority < weakestPriority) {
                weakestPriority = priority;
                weakest = flash;
            }
        }
        if (weakest != null) {
            weakest.expire();
            FLASHES.remove(weakest);
        }
    }

    private static int lifeFor(BombSize size) {
        return switch (size) {
            case SMALL -> 42;
            case SEA -> 48;
            case MEDIUM -> 58;
            case LARGE -> 72;
        };
    }

    public static final class Flash {
        public final ClientLevel level;
        public Vec3 pos;
        public float intensity;
        public int life;
        public BombSize size;
        public int age;

        Flash(ClientLevel level, Vec3 pos, float intensity, int life, BombSize size) {
            this.level = level;
            this.pos = pos;
            this.intensity = intensity;
            this.life = life;
            this.size = size;
            this.age = 0;
        }

        public float fade(float partialTick) {
            int screenLife =
                    switch (size) {
                        case SMALL -> 18;
                        case SEA -> 20;
                        case MEDIUM -> 24;
                        case LARGE -> 30;
                    };
            float t = Mth.clamp((age + partialTick) / (float) screenLife, 0.0f, 1.0f);
            // Explosion light peaks immediately, then leaves a short warm afterglow.
            float impulse = (float) Math.exp(-t * 16.0f);
            float tail = 1.0f - smoothstep(t);
            return Mth.clamp(impulse * 0.72f + tail * 0.28f, 0.0f, 1.0f);
        }

        /**
         * Physical Veil lights outlive the camera flash so native CBC smoke stays
         * warm and readable at night without replacing or recoloring the particles.
         */
        public float lightFade(float partialTick) {
            float t = Mth.clamp((age + partialTick) / (float) life, 0.0f, 1.0f);
            float impulse = (float) Math.exp(-t * 5.0f);
            float tail = 1.0f - smoothstep(t);
            return Mth.clamp(impulse * 0.35f + tail * 0.65f, 0.0f, 1.0f);
        }

        private void merge(Vec3 newPos, float newIntensity, int newLife, BombSize newSize) {
            float oldWeight = Math.max(0.1f, this.intensity);
            float newWeight = Math.max(0.1f, newIntensity);
            double invWeight = 1.0D / (oldWeight + newWeight);
            this.pos = new Vec3(
                    (this.pos.x * oldWeight + newPos.x * newWeight) * invWeight,
                    (this.pos.y * oldWeight + newPos.y * newWeight) * invWeight,
                    (this.pos.z * oldWeight + newPos.z * newWeight) * invWeight);
            this.intensity = Mth.clamp(
                    (float) Math.sqrt(this.intensity * this.intensity + newIntensity * newIntensity * 0.55f),
                    0.15f,
                    1.65f);
            if (newSize.ordinal() > this.size.ordinal()) {
                this.size = newSize;
            }
            this.life = Math.max(Math.max(this.life, newLife), lifeFor(this.size));
            this.age = Math.min(this.age, 1);
        }

        private void expire() {
            this.age = this.life;
        }

        private static float smoothstep(float value) {
            value = Mth.clamp(value, 0.0f, 1.0f);
            return value * value * (3.0f - 2.0f * value);
        }
    }
}
