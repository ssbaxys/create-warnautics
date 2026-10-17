package com.cbc_more_content;

import com.cbc_more_content.registry.ModItems;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Every Warnautics item can be made. A recipe whose ingredient ids do not resolve fails
 * to load silently in play and simply never appears, so the check is against the
 * recipes the server actually loaded, not the files in the jar.
 */
@GameTestHolder(CBCMoreContent.MOD_ID)
@PrefixGameTestTemplate(false)
public class RecipeCoverageGameTests {
    /** Found, not made: loot-table only, like the vanilla discs. */
    private static final Set<String> LOOT_ONLY = Set.of("music_disc_breaker_of_skies");

    @GameTest(template = "empty", batch = "recipe_coverage", timeoutTicks = 20)
    public static void everyItemHasALoadedRecipe(GameTestHelper helper) {
        var level = helper.getLevel();
        Set<Item> made = new HashSet<>();
        for (var holder : level.getRecipeManager().getRecipes()) {
            var result = holder.value().getResultItem(level.registryAccess());
            if (!result.isEmpty()) {
                made.add(result.getItem());
            }
        }
        Set<String> missing = new TreeSet<>();
        for (var entry : ModItems.ITEMS.getEntries()) {
            ResourceLocation id = entry.getId();
            if (!LOOT_ONLY.contains(id.getPath()) && !made.contains(entry.get())) {
                missing.add(id.getPath());
            }
        }
        helper.assertTrue(missing.isEmpty(), "Items without a loaded recipe: " + missing);
        helper.assertTrue(
                BuiltInRegistries.ITEM.containsKey(
                        ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "music_disc_breaker_of_skies")),
                "Loot-only exception still names a real item");
        helper.succeed();
    }
}
