package com.cbc_more_content.damage;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * Bomb blast damage with randomized aviation-themed death messages.
 */
public class BombDamageSource extends DamageSource {
    public static final String[] DEATH_KEYS = {
            "death.attack.cbc_more_content.aerial_bombing.1",
            "death.attack.cbc_more_content.aerial_bombing.2",
            "death.attack.cbc_more_content.aerial_bombing.3",
            "death.attack.cbc_more_content.aerial_bombing.4",
            "death.attack.cbc_more_content.aerial_bombing.5",
            "death.attack.cbc_more_content.aerial_bombing.6",
            "death.attack.cbc_more_content.aerial_bombing.7",
            "death.attack.cbc_more_content.aerial_bombing.8",
            "death.attack.cbc_more_content.aerial_bombing.9",
            "death.attack.cbc_more_content.aerial_bombing.10",
            "death.attack.cbc_more_content.aerial_bombing.11",
            "death.attack.cbc_more_content.aerial_bombing.12"
    };

    public BombDamageSource(Holder<DamageType> type) {
        super(type);
    }

    /**
     * Never throws. A missing damage type is a broken install, not a reason to kill the
     * server tick: an explosion that cannot name itself still has to go off, and a
     * detonation failing here would rethrow on every entity tick that follows.
     */
    public static DamageSource create(Level level) {
        try {
            Holder<DamageType> type = level.registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(ModDamageTypes.AERIAL_BOMBING);
            return new BombDamageSource(type);
        } catch (Throwable t) {
            return level.damageSources().explosion(null, null);
        }
    }

    @Override
    public Component getLocalizedDeathMessage(LivingEntity entity) {
        String key = DEATH_KEYS[entity.getRandom().nextInt(DEATH_KEYS.length)];
        return Component.translatable(key, entity.getDisplayName());
    }
}
