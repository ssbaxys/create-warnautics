package com.cbc_more_content.damage;

import com.cbc_more_content.mine.MineType;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * Mine blast damage. Separate from {@link BombDamageSource} so a death message can say
 * what actually killed you: shrapnel from an antipersonnel charge reads very differently
 * from being caught under an antivehicle mine.
 */
public class MineDamageSource extends DamageSource {
    private static final String[] SMALL_KEYS = {
        "death.attack.cbc_more_content.land_mine.small.1",
        "death.attack.cbc_more_content.land_mine.small.2",
        "death.attack.cbc_more_content.land_mine.small.3",
        "death.attack.cbc_more_content.land_mine.small.4",
        "death.attack.cbc_more_content.land_mine.small.5",
        "death.attack.cbc_more_content.land_mine.small.6"
    };
    private static final String[] LARGE_KEYS = {
        "death.attack.cbc_more_content.land_mine.large.1",
        "death.attack.cbc_more_content.land_mine.large.2",
        "death.attack.cbc_more_content.land_mine.large.3",
        "death.attack.cbc_more_content.land_mine.large.4",
        "death.attack.cbc_more_content.land_mine.large.5",
        "death.attack.cbc_more_content.land_mine.large.6"
    };

    private static final String[] BOUNDING_KEYS = {
        "death.attack.cbc_more_content.land_mine.bounding.1",
        "death.attack.cbc_more_content.land_mine.bounding.2",
        "death.attack.cbc_more_content.land_mine.bounding.3",
        "death.attack.cbc_more_content.land_mine.bounding.4",
        "death.attack.cbc_more_content.land_mine.bounding.5",
        "death.attack.cbc_more_content.land_mine.bounding.6"
    };

    private final MineType type;

    public MineDamageSource(Holder<DamageType> damageType, MineType type) {
        super(damageType);
        this.type = type;
    }

    public static MineDamageSource create(Level level, MineType type) {
        Holder<DamageType> damageType = level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ModDamageTypes.LAND_MINE);
        return new MineDamageSource(damageType, type);
    }

    @Override
    public Component getLocalizedDeathMessage(LivingEntity entity) {
        String[] keys =
                switch (this.type) {
                    case SMALL -> SMALL_KEYS;
                    case BOUNDING -> BOUNDING_KEYS;
                    case LARGE -> LARGE_KEYS;
                };
        String key = keys[entity.getRandom().nextInt(keys.length)];
        return Component.translatable(key, entity.getDisplayName());
    }
}
