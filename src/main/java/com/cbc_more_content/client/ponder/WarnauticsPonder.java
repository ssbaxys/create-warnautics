package com.cbc_more_content.client.ponder;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.registry.ModItems;
import com.google.gson.Gson;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.TooltipModifier;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import net.createmod.catnip.lang.FontHelper.Palette;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.createmod.ponder.api.registration.SharedTextRegistrationHelper;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/** Client-only integration with Create's actual tooltips and Ponder's scene index. */
public final class WarnauticsPonder implements PonderPlugin {
    public record Guide(String id, String category, String title, List<String> steps) {
        public Item item() {
            return BuiltInRegistries.ITEM.get(location(id));
        }
    }

    private static final List<Guide> GUIDES = loadGuides();

    private static List<Guide> loadGuides() {
        try (var reader = new InputStreamReader(
                Objects.requireNonNull(
                        WarnauticsPonder.class.getResourceAsStream("/assets/cbc_more_content/ponder/guides.json")),
                StandardCharsets.UTF_8)) {
            return List.of(new Gson().fromJson(reader, Guide[].class));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load Warnautics guide catalog", exception);
        }
    }

    public static List<Guide> guides() {
        return GUIDES;
    }

    public static ResourceLocation location(String path) {
        return ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, path);
    }

    public static void register() {
        PonderIndex.addPlugin(new WarnauticsPonder());
        for (var entry : ModItems.ITEMS.getEntries()) {
            Item item = entry.get();
            // BlockItem aliases otherwise share the base block's translation key.
            ItemDescription.useKey(
                    item, "item." + CBCMoreContent.MOD_ID + "." + entry.getId().getPath());
            TooltipModifier.REGISTRY.register(item, new ItemDescription.Modifier(item, Palette.STANDARD_CREATE));
        }
    }

    @Override
    public String getModId() {
        return CBCMoreContent.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        for (Guide guide : GUIDES) {
            helper.addStoryBoard(
                    location(guide.id()),
                    "workshop",
                    (scene, util) -> new WarnauticsScenes(guide, scene, util).program(),
                    location(guide.category()));
        }
    }

    @Override
    public void registerSharedText(SharedTextRegistrationHelper helper) {
        for (Guide guide : GUIDES) {
            for (int i = 0; i < guide.steps().size(); i++) {
                helper.registerSharedText(guide.id() + "_" + i, guide.steps().get(i));
            }
        }
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        tag(
                helper,
                "bombs",
                "Bombs and release racks",
                "Mounting, release intervals and blast behaviour.",
                "small_bomb");
        tag(helper, "mines", "Ground mines", "Contact, burial and different triggers.", "bounding_mine");
        tag(
                helper,
                "naval",
                "Water and physical connections",
                "Torpedoes, floating mines, chains and connectors.",
                "chain_coil");
        tag(
                helper,
                "guidance",
                "Missiles and targeting",
                "Installation, guidance and remote controls.",
                "cruise_missile");
        tag(
                helper,
                "tools",
                "Equipment and control",
                "Configuration, signals, remote charges and sound.",
                "settings_key");
        for (Guide guide : GUIDES) {
            helper.addTagToComponent(location(guide.id()), location(guide.category()));
        }
    }

    private static void tag(
            PonderTagRegistrationHelper<ResourceLocation> helper,
            String id,
            String title,
            String description,
            String item) {
        helper.registerTag(id)
                .title(title)
                .description(description)
                .item(BuiltInRegistries.ITEM.get(location(item)))
                .addToIndex()
                .register();
    }
}
