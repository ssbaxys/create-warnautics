package com.cbc_more_content.munitions;

import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.effects.BombSympatheticDetonation;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModEntityTypes;
import javax.annotation.Nonnull;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.index.CBCMunitionPropertiesHandlers;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;
import rbasamoyai.createbigcannons.munitions.big_cannon.FuzedBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonCommonShellProperties;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonFuzePropertiesComponent;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonProjectilePropertiesComponent;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;
import rbasamoyai.createbigcannons.munitions.config.components.EntityDamagePropertiesComponent;

/**
 * Drop bomb — always detonates on hard impact / ground stick (no impact-fuze RNG duds).
 * Crater via CBC {@link rbasamoyai.createbigcannons.munitions.ShellExplosion}.
 */
public class DropBombProjectile extends FuzedBigCannonProjectile {

    private boolean detonated;

    public DropBombProjectile(EntityType<? extends DropBombProjectile> type, Level level) {
        super(type, level);
        // Warnautics owns the long falling whistle. Suppress CBC's short shell
        // fly-by loop so both systems cannot stack on the same bomb.
        this.setLocalSoundCooldown(-1);
    }

    /**
     * Sable collision bridge for a placed block that was turned into a loose
     * physics body. Keeping detonation inside the projectile class means this path
     * reads the same reloadable CBC munition properties as a redstone-released bomb.
     */
    public final void detonateFromPhysicalImpact(Vec3 position) {
        this.forceDetonate(position);
    }

    @Override
    protected boolean onImpact(HitResult hitResult, ImpactResult impactResult, ProjectileContext projectileContext) {
        // Drop bombs are impact explosives, not penetrating cannon shells. Calling
        // CBC's normal impact path first lets the projectile break a block, bounce
        // or wait for probabilistic fuze handling. It also leaves the client copy
        // hovering until the delayed server removal arrives.
        if (this.level().isClientSide) {
            this.removeNextTick = true;
            return true;
        }
        if (this.detonated || this.isRemoved()) {
            return true;
        }
        this.forceDetonate(this.impactDetonationPosition(hitResult));
        return true;
    }

    private Vec3 impactDetonationPosition(HitResult hitResult) {
        Vec3 hit = hitResult.getLocation();
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return hit;
        }
        Vec3 velocity = this.getDeltaMovement();
        double speed = velocity.length();
        if (speed < 1.0E-5D) {
            return hit;
        }

        // Aerial bombs burst just inside the struck surface instead of at an
        // arbitrary air-side coordinate. This produces a grounded, slightly
        // deeper crater while retaining immediate collision-tick detonation.
        double baseDepth =
                switch (this.bombSize()) {
                    case SMALL -> 0.18D;
                    case SEA -> 0.24D;
                    case MEDIUM -> 0.42D;
                    case LARGE -> 0.62D;
                    case MOAB -> -0.45D;
                };
        double speedScale = Math.max(0.35D, Math.min(1.0D, speed / 1.25D));
        return hit.add(velocity.scale((baseDepth * speedScale) / speed));
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && !this.detonated && !this.isRemoved() && this.isInGround()) {
            // Failed / lingering stick in dirt — still cook off immediately for bombs.
            this.forceDetonate(this.position());
        }
    }

    @Override
    protected void detonate(Position position) {
        this.forceDetonate(new Vec3(position.x(), position.y(), position.z()));
    }

    private void forceDetonate(Vec3 position) {
        this.forceDetonate(position, 1.0f);
    }

    protected void forceDetonate(Vec3 position, float powerScale) {
        this.forceDetonate(position, powerScale, powerScale);
    }

    protected void forceDetonate(Vec3 position, float blockPowerScale, float entityPowerScale) {
        if (this.detonated || this.level().isClientSide) {
            return;
        }
        this.detonated = true;
        // Mark removal before crater calculation: even a very large/Sable-aware
        // explosion cannot leave this projectile eligible for another impact.
        this.removeNextTick = true;

        BigCannonCommonShellProperties properties = this.getAllProperties();
        float blockPower = properties.explosion().blockDamagePower() * blockPowerScale;
        float entityPower = properties.explosion().entityDamagePower() * entityPowerScale;

        if (this.level() instanceof ServerLevel serverLevel) {
            BombExplosionHandler.detonate(
                    serverLevel,
                    this,
                    BombDamageSource.create(serverLevel),
                    position,
                    blockPower,
                    entityPower,
                    this.bombSize());
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Explosion#finalizeExplosion damages entities. Without this gate a stick of
        // bombs released one tick apart detonated each other in mid-air, which is what
        // destroyed the carrier aircraft in practice.
        if (!BombSympatheticDetonation.allowsCookoffFrom(null)) {
            return false;
        }
        if (this.detonated || this.level().isClientSide || this.isRemoved()) {
            return false;
        }
        this.forceDetonate(this.position());
        return true;
    }

    @Override
    public boolean canHitEntity(Entity entity) {
        return !(entity instanceof DropBombProjectile) && super.canHitEntity(entity);
    }

    /** Nearby TNT / shell / bomb blast — cook off in flight. */
    public void sympatheticDetonate() {
        if (this.detonated || this.level().isClientSide || this.isRemoved()) {
            return;
        }
        this.forceDetonate(this.position());
    }

    @Override
    protected DamageSource getEntityDamage(Entity entity) {
        return BombDamageSource.create(this.level());
    }

    @Override
    public DamageSource indirectArtilleryFire(boolean bypassArmor) {
        return BombDamageSource.create(this.level());
    }

    @Override
    public boolean canLingerInGround() {
        return false;
    }

    @Override
    public BlockState getRenderedBlockState() {
        Block block = this.visualBlock();
        BlockState state = block.defaultBlockState().setValue(DropBombBlock.FACING, Direction.NORTH);
        if (this.getType() == ModEntityTypes.SEA_BOMB.get()) {
            // Reserved state variants split the projectile body from its animated
            // author-model propeller. Placed sea bombs still use cassette=1.
            state = state.setValue(DropBombBlock.POWERED, true).setValue(DropBombBlock.CASSETTE, 4);
        }
        return state;
    }

    private Block visualBlock() {
        EntityType<?> type = this.getType();
        if (type == ModEntityTypes.SEA_BOMB.get()) {
            return ModBlocks.SEA_BOMB.get();
        }
        if (type == ModEntityTypes.MEDIUM_BOMB.get()) {
            return ModBlocks.MEDIUM_BOMB.get();
        }
        if (type == ModEntityTypes.LARGE_BOMB.get()) {
            return ModBlocks.LARGE_BOMB.get();
        }
        if (type == ModEntityTypes.MOAB.get()) {
            return ModBlocks.MOAB.get();
        }
        return ModBlocks.SMALL_BOMB.get();
    }

    public BombSize bombSize() {
        EntityType<?> type = this.getType();
        if (type == ModEntityTypes.SEA_BOMB.get()) {
            return BombSize.SEA;
        }
        if (type == ModEntityTypes.MEDIUM_BOMB.get()) {
            return BombSize.MEDIUM;
        }
        if (type == ModEntityTypes.LARGE_BOMB.get()) {
            return BombSize.LARGE;
        }
        if (type == ModEntityTypes.MOAB.get()) {
            return BombSize.MOAB;
        }
        return BombSize.SMALL;
    }

    @Nonnull
    @Override
    protected BigCannonFuzePropertiesComponent getFuzeProperties() {
        return this.getAllProperties().fuze();
    }

    @Nonnull
    @Override
    protected BigCannonProjectilePropertiesComponent getBigCannonProjectileProperties() {
        return this.getAllProperties().bigCannonProperties();
    }

    @Nonnull
    @Override
    public EntityDamagePropertiesComponent getDamageProperties() {
        return this.getAllProperties().damage();
    }

    @Nonnull
    @Override
    protected BallisticPropertiesComponent getBallisticProperties() {
        return this.getAllProperties().ballistics();
    }

    protected BigCannonCommonShellProperties getAllProperties() {
        return CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE.getPropertiesOf(this);
    }
}
