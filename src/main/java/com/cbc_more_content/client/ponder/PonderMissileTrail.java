package com.cbc_more_content.client.ponder;

import com.cbc_more_content.registry.ModParticles;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.PonderElementBase;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Scene-owned exhaust follows the animated airframe, including its turn and cold start. */
public final class PonderMissileTrail extends PonderElementBase {
    private final ElementLink<WorldSectionElement> airframe;
    private final Vec3 body;
    private final int duration;
    private final int coldTicks;
    private int age;

    public PonderMissileTrail(ElementLink<WorldSectionElement> airframe, Vec3 body, int duration, int coldTicks) {
        this.airframe = airframe;
        this.body = body;
        this.duration = duration;
        this.coldTicks = coldTicks;
    }

    @Override
    public void tick(PonderScene scene) {
        if (++age > duration) {
            setVisible(false);
            return;
        }
        var section = scene.resolve(airframe);
        if (section == null || !section.isVisible()) {
            return;
        }
        Vec3 angles = section.getAnimatedRotation();
        var down = new Quaternionf()
                .rotationXYZ(
                        (float) angles.x * Mth.DEG_TO_RAD,
                        (float) angles.y * Mth.DEG_TO_RAD,
                        (float) angles.z * Mth.DEG_TO_RAD)
                .transform(new Vector3f(0, -1, 0));
        Vec3 axis = new Vec3(down.x, down.y, down.z);
        Vec3 nozzle = body.add(section.getAnimatedOffset()).add(axis.scale(1.4));
        var world = scene.getWorld();
        if (age <= coldTicks) {
            world.addParticle(
                    ModParticles.MISSILE_GAS.get(),
                    nozzle.x,
                    nozzle.y,
                    nozzle.z,
                    axis.x * .04,
                    axis.y * .04,
                    axis.z * .04);
        } else {
            for (int i = 0; i < 3; i++) {
                Vec3 at = nozzle.add(axis.scale(i * .2));
                world.addParticle(
                        ModParticles.MISSILE_EXHAUST.get(), at.x, at.y, at.z, axis.x * .08, axis.y * .08, axis.z * .08);
            }
            if (age % 3 == 0) {
                Vec3 at = nozzle.add(axis.scale(.65));
                world.addParticle(
                        ModParticles.MISSILE_SMOKE.get(),
                        at.x,
                        at.y,
                        at.z,
                        axis.x * .025,
                        axis.y * .025,
                        axis.z * .025);
            }
        }
    }
}
