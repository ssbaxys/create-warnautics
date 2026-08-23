package com.cbc_more_content.item;

import java.util.List;

import com.cbc_more_content.munitions.C4Projectile;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** Throwable breaching charge. Sticks where it lands; the settings key sets its fuse. */
public class C4Item extends Item {
    public C4Item(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.6f,
                0.4f / (level.getRandom().nextFloat() * 0.4f + 0.8f));

        if (!level.isClientSide) {
            C4Projectile charge = new C4Projectile(level, player);
            charge.setItem(stack);
            // A brick of plastic explosive is slapped onto the wall in front of you, not
            // thrown across the map: low muzzle speed, and C4Projectile falls heavily.
            charge.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0f, 0.55f, 1.0f);
            level.addFreshEntity(charge);
        }

        player.awardStat(Stats.ITEM_USED.get(this));
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
            List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.cbc_more_content.c4"));
    }
}
