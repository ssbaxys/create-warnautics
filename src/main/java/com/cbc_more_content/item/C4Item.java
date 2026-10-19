package com.cbc_more_content.item;

import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.munitions.C4Projectile;
import com.cbc_more_content.util.ReflectiveDispatcher;
import java.util.List;
import net.minecraft.ChatFormatting;
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

/**
 * Throwable breaching charge. Sticks where it lands; the settings key sets its fuse.
 * <p>
 * Sneak and use it in the air to set the whole stack up before the throw — code, timer or
 * detonator, and whether it goes live as it is thrown or lands ready to be started — so a
 * charge thrown onto someone's wall needs nobody to walk up to it and work the keypad.
 */
public class C4Item extends Item {
    public C4Item(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown() && WarnauticsConfig.c4PresetsAllowed()) {
            if (level.isClientSide) {
                ReflectiveDispatcher.invoke(
                        "com.cbc_more_content.client.gui.C4PresetScreen",
                        "open",
                        new Class<?>[] {InteractionHand.class},
                        hand);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        level.playSound(
                null,
                player.getX(),
                player.getY(),
                player.getZ(),
                SoundEvents.SNOWBALL_THROW,
                SoundSource.PLAYERS,
                0.6f,
                0.4f / (level.getRandom().nextFloat() * 0.4f + 0.8f));

        if (!level.isClientSide) {
            C4Projectile charge = new C4Projectile(level, player);
            charge.setItem(stack);
            if (WarnauticsConfig.c4PresetsAllowed()) {
                C4Preset preset = C4Preset.of(stack);
                if (preset.activation() == C4Preset.ON_THROW && !WarnauticsConfig.c4ArmOnThrowAllowed()) {
                    preset = new C4Preset(C4Preset.MANUAL, preset.remote(), preset.seconds(), preset.code());
                }
                charge.applyPreset(preset, player);
            }
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
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        C4Preset preset = C4Preset.of(stack);
        if (!preset.active()) {
            return;
        }
        Component trigger = preset.remote()
                ? Component.translatable("tooltip.cbc_more_content.c4.preset.remote")
                : Component.translatable("tooltip.cbc_more_content.c4.preset.timer", preset.seconds());
        lines.add(Component.translatable(
                        preset.activation() == C4Preset.ON_THROW
                                ? "tooltip.cbc_more_content.c4.preset.on_throw"
                                : "tooltip.cbc_more_content.c4.preset.manual",
                        trigger)
                .withStyle(preset.activation() == C4Preset.ON_THROW ? ChatFormatting.RED : ChatFormatting.GOLD));
    }
}
