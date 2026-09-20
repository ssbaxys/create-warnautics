package com.cbc_more_content.item;

import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BombBlastFx;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.registry.ModSounds;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class BombVestItem extends ArmorItem {
    public static final float BLOCK_POWER = 7.5f;

    public static final float ENTITY_POWER = 9.0f;

    private static final String LINKED_KEY = "Linked";
    private static final int BEEP_INTERVAL = 40;
    private static final float BEEP_PITCH = 0.85f;

    public BombVestItem(Holder<ArmorMaterial> material, Properties properties) {
        super(material, Type.CHESTPLATE, properties);
    }

    public static boolean isWearing(LivingEntity entity) {
        return entity.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof BombVestItem;
    }

    @Nullable
    public static ItemStack worn(LivingEntity entity) {
        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        return chest.getItem() instanceof BombVestItem ? chest : null;
    }

    public static boolean isLinked(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(LINKED_KEY);
    }

    private static void setLinked(ItemStack stack, boolean linked) {
        CompoundTag root = new CompoundTag();
        CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
        if (existing != null) {
            root = existing.copyTag();
        }
        if (linked) {
            root.putBoolean(LINKED_KEY, true);
        } else {
            root.remove(LINKED_KEY);
        }
        if (root.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        }
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || !(entity instanceof Player wearer) || !(level instanceof ServerLevel server)) {
            return;
        }
        if (wearer.getItemBySlot(EquipmentSlot.CHEST) != stack) {
            if (isLinked(stack)) {
                setLinked(stack, false);
            }
            return;
        }
        if (level.getGameTime() % BEEP_INTERVAL != 0) {
            return;
        }

        boolean linked = holdsLiveLink(wearer);
        if (isLinked(stack) != linked) {
            setLinked(stack, linked);
        }
        if (linked) {
            server.playSound(
                    null, wearer.blockPosition(), ModSounds.C4_TICK.get(), SoundSource.PLAYERS, 0.35f, BEEP_PITCH);
        }
    }

    private static boolean holdsLiveLink(Player wearer) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (isLinkedSet(wearer.getItemInHand(hand), wearer)) {
                return true;
            }
        }
        for (ItemStack held : wearer.getInventory().items) {
            if (isLinkedSet(held, wearer)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLinkedSet(ItemStack held, Player wearer) {
        return held.getItem() instanceof DetonatorItem && wearer.getUUID().equals(DetonatorItem.boundVest(held));
    }

    static void syncLinkMirror(Player wearer) {
        if (!(wearer.level() instanceof ServerLevel)) {
            return;
        }
        ItemStack chest = worn(wearer);
        if (chest == null) {
            return;
        }
        boolean linked = holdsLiveLink(wearer);
        if (isLinked(chest) != linked) {
            setLinked(chest, linked);
        }
    }

    public static void detonate(ServerLevel level, LivingEntity wearer) {
        Vec3 at = wearer.position();
        // The vest is the charge: it goes with the blast, before any damage is dealt.
        wearer.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        BombExplosionHandler.detonateBreachingCharge(
                level, BombDamageSource.create(level), at, BLOCK_POWER, ENTITY_POWER);
        BombBlastFx.playBreachingCharge(level, at, BLOCK_POWER);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.cbc_more_content.bomb_vest").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.cbc_more_content.bomb_vest.link")
                .withStyle(ChatFormatting.DARK_GRAY));
        if (isLinked(stack)) {
            tooltip.add(Component.translatable("tooltip.cbc_more_content.bomb_vest.armed")
                    .withStyle(ChatFormatting.RED));
        }
    }
}
