package com.cbc_more_content.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;

/**
 * Adds one item to a loot roll with a flat chance. Which tables it applies to is decided
 * by the conditions in the modifier JSON, not here.
 */
public class AddDiscModifier extends LootModifier {
    public static final MapCodec<AddDiscModifier> CODEC = RecordCodecBuilder.mapCodec(instance -> codecStart(instance)
            .and(instance.group(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .byNameCodec()
                            .fieldOf("item")
                            .forGetter(m -> m.item),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("chance").forGetter(m -> m.chance)))
            .apply(instance, AddDiscModifier::new));

    private final Item item;
    private final float chance;

    public AddDiscModifier(LootItemCondition[] conditions, Item item, float chance) {
        super(conditions);
        this.item = item;
        this.chance = chance;
    }

    @NotNull
    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        RandomSource random = context.getRandom();
        if (random.nextFloat() < this.chance) {
            loot.add(new ItemStack(this.item));
        }
        return loot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
