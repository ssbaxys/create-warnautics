package com.cbc_more_content;

import com.cbc_more_content.client.ChainModels;
import com.cbc_more_content.compat.simulated.ChainConnection;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModItems;
import dev.simulated_team.simulated.content.blocks.rope.rope_connector.RopeConnectorBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.rope_connector.RopeConnectorRenderer;
import dev.simulated_team.simulated.index.SimItems;
import dev.simulated_team.simulated.index.SimPartialModels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EntityBlock;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Opt-in real client resource/mixin check, excluded from release JARs. */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ChainClientCheck {
    private static java.lang.reflect.Method injected(Class<?> target, String suffix) {
        var method = java.util.Arrays.stream(target.getDeclaredMethods())
                .filter(m -> m.getName().contains(suffix))
                .findFirst()
                .orElseThrow();
        method.setAccessible(true);
        return method;
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new IllegalStateException(message);
        }
    }

    @SubscribeEvent
    public static void modelsReady(ModelEvent.BakingCompleted event) {
        if (!Boolean.getBoolean("warnautics.chainClientCheck")) {
            return;
        }
        List<String> checked = new ArrayList<>();
        boolean waitingForClient = false;
        try {
            for (String[] expected : List.of(
                    new String[] {"simulated:block/rope/rope", "standalone", "simulated:block/rope_particle"},
                    new String[] {"simulated:rope_coupling", "inventory", "simulated:item/rope_coupling"},
                    new String[] {"simulated:rope_connector", "inventory", "simulated:block/rope_winch/winch"},
                    new String[] {"cbc_more_content:block/chain/rope", "standalone", "cbc_more_content:block/chain"},
                    new String[] {
                        "cbc_more_content:block/chain_connector/knot", "standalone", "cbc_more_content:block/chain"
                    },
                    new String[] {"cbc_more_content:chain_coil", "inventory", "cbc_more_content:block/chain"},
                    new String[] {"cbc_more_content:chain_connector", "inventory", "cbc_more_content:block/winch"})) {
                var entry = event.getModels().entrySet().stream()
                        .filter(e -> e.getKey().id().equals(ResourceLocation.parse(expected[0]))
                                && e.getKey().getVariant().equals(expected[1]))
                        .findFirst()
                        .orElseThrow();
                var quads = entry.getValue().getQuads(null, null, RandomSource.create(0));
                require(
                        quads.stream()
                                .anyMatch(q ->
                                        q.getSprite().contents().name().equals(ResourceLocation.parse(expected[2]))),
                        "Wrong material on " + entry.getKey());
                checked.add(entry.getKey() + " -> " + expected[2]);
            }
            var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:rope_connector"));
            var connectorStates = event.getModels().entrySet().stream()
                    .filter(e -> e.getKey().id().equals(ResourceLocation.parse("cbc_more_content:chain_connector"))
                            && !e.getKey().getVariant().equals("inventory"))
                    .toList();
            require(connectorStates.size() == 24, "All twelve orientations must be baked dry and waterlogged");
            for (var entry : connectorStates) {
                require(
                        entry.getValue().getQuads(null, null, RandomSource.create(0)).stream()
                                .anyMatch(q -> q.getSprite()
                                        .contents()
                                        .name()
                                        .equals(ResourceLocation.parse("cbc_more_content:block/winch"))),
                        "Connector orientation uses our texture: " + entry.getKey());
            }
            checked.add("Custom connector item and all 12 orientations use our model/texture, dry and waterlogged");
            var be = (RopeConnectorBlockEntity)
                    ((EntityBlock) block).newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
            if (be.getRopeHolder() == null) {
                be.addBehaviours(new ArrayList<>());
            }
            var holder = be.getRopeHolder();
            holder.giveFakeClientStrand(UUID.randomUUID());
            Class<?> renderer =
                    Class.forName("dev.simulated_team.simulated.content.blocks.rope.strand.client.RopeStrandRenderer");
            var strand = injected(renderer, "warnautics$strand");
            var layer = injected(renderer, "chainCutout");
            var connectorKnot = injected(RopeConnectorRenderer.class, "connectorKnot");
            var connectorLayer = injected(RopeConnectorRenderer.class, "chainCutout");
            var connector = new RopeConnectorRenderer(null);
            for (boolean chain : List.of(false, true, false)) {
                ((ChainConnection) holder).warnautics$setChain(chain);
                require(
                        strand.invoke(null, be, holder, 0F, null, null)
                                == (chain ? ChainModels.STRAND : SimPartialModels.ROPE),
                        "Strand material leaked between chain and rope");
                require(
                        layer.invoke(null, be, holder, 0F, null, null)
                                == (chain ? RenderType.cutoutMipped() : RenderType.solid()),
                        "Wrong strand render layer");
                require(
                        connectorKnot.invoke(connector, be, 0F, null, null, 0, 0)
                                == (chain ? ChainModels.CONNECTOR_KNOT : SimPartialModels.ROPE_CONNECTOR_KNOT),
                        "Wrong endpoint knot");
                require(
                        connectorLayer.invoke(connector, be, 0F, null, null, 0, 0)
                                == (chain ? RenderType.cutoutMipped() : RenderType.solid()),
                        "Wrong endpoint render layer");
            }
            checked.add("Rope and chain choose different native/chain models and layers; switching back restores rope");
            Class<?> preview =
                    Class.forName("dev.simulated_team.simulated.content.items.rope.RopeItem.ClientRopeItemHandler");
            var previewHandler = injected(preview, "previewChain");
            require(
                    (boolean) previewHandler.invoke(
                            null, SimItems.ROPE_COUPLING, new ItemStack(ModItems.CHAIN_COIL.get())),
                    "Chain has no native placement preview");
            require(
                    (boolean) previewHandler.invoke(
                            null, SimItems.ROPE_COUPLING, new ItemStack(SimItems.ROPE_COUPLING.get())),
                    "Native rope preview broken");
            require(
                    !(boolean) previewHandler.invoke(null, SimItems.ROPE_COUPLING, new ItemStack(Items.STICK)),
                    "Preview leaks to unrelated items");
            checked.add("Native preview accepts both coils and rejects unrelated items");
            require(
                    Minecraft.getInstance()
                            .getResourceManager()
                            .getResource(ResourceLocation.parse("cbc_more_content:sounds/sea_mine_armed.ogg"))
                            .isPresent(),
                    "Mine click asset missing");
            checked.add("New sea mine click resource loaded");
            // BakingCompleted precedes the dispatcher's resource reload. Verify its populated
            // renderer map only after the entire loading overlay has finished.
            NeoForge.EVENT_BUS.addListener(new FinishClientCheck(checked));
            waitingForClient = true;
        } catch (Throwable failure) {
            failure.printStackTrace();
            try {
                Files.writeString(
                        Path.of("chain-client-check.txt"), "FAIL\n" + failure + "\n" + String.join("\n", checked));
            } catch (java.io.IOException io) {
                failure.addSuppressed(io);
            }
        } finally {
            if (!waitingForClient) {
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
            }
        }
    }

    private static final class FinishClientCheck implements Consumer<ClientTickEvent.Post> {
        private final List<String> checked;
        private int readyTicks;

        private FinishClientCheck(List<String> checked) {
            this.checked = checked;
        }

        @Override
        public void accept(ClientTickEvent.Post event) {
            var minecraft = Minecraft.getInstance();
            if (minecraft.getOverlay() != null) {
                readyTicks = 0;
                return;
            }
            // Veil replaces vanilla shaders at the end of the resource reload. Let the
            // first title frames initialize their screen/camera uniforms before offscreen tests.
            if (++readyTicks < 10) {
                return;
            }
            NeoForge.EVENT_BUS.unregister(this);
            try {
                var ownConnector = (RopeConnectorBlockEntity) ModBlocks.CHAIN_CONNECTOR
                        .get()
                        .newBlockEntity(
                                BlockPos.ZERO, ModBlocks.CHAIN_CONNECTOR.get().defaultBlockState());
                require(
                        minecraft.getBlockEntityRenderDispatcher().getRenderer(ownConnector)
                                instanceof RopeConnectorRenderer,
                        "Custom connector needs native strand renderer");
                checked.add("Custom connector has the native strand renderer after client initialization");
                var missileBlock = ModBlocks.CRUISE_MISSILE.get();
                for (var facing : net.minecraft.core.Direction.values()) {
                    var state = missileBlock
                            .defaultBlockState()
                            .setValue(com.cbc_more_content.block.CruiseMissileBlock.FACING, facing);
                    var missile = (com.cbc_more_content.block.CruiseMissileBlockEntity)
                            missileBlock.newBlockEntity(BlockPos.ZERO, state);
                    require(
                            minecraft.getBlockEntityRenderDispatcher().getRenderer(missile)
                                    instanceof com.cbc_more_content.client.CruiseMissileBlockRenderer,
                            "All missile facings have the live renderer");
                    require(!missile.isLiveAirframe(), "Detached missile instance must not render a phantom");
                    var baked = minecraft
                            .getBlockRenderer()
                            .getBlockModel(facing.getAxis().isHorizontal() ? state : missileBlock.defaultBlockState());
                    require(
                            !baked.getQuads(null, null, RandomSource.create(0)).isEmpty(),
                            "Missile renderer has visible baked geometry");
                }
                require(
                        com.cbc_more_content.client.TargetMarkerRenderType.LINES
                                != net.minecraft.client.renderer.RenderType.lines(),
                        "Target markers use a dedicated overlay layer");
                checked.add("All missile facings use live rendering and target marker overlay initializes");
                TargetMarkerPixelCheck.run();
                MissilePlumePixelCheck.run();
                checked.add(
                        "Veil missile volume compiled, rendered at seven angles, animates, respects depth and motor cutoff");
                var aim9 = ModBlocks.AIM9.get();
                for (var facing : net.minecraft.core.Direction.values()) {
                    var state = aim9.defaultBlockState().setValue(com.cbc_more_content.block.Aim9Block.FACING, facing);
                    var be = (com.cbc_more_content.block.Aim9BlockEntity) aim9.newBlockEntity(BlockPos.ZERO, state);
                    require(
                            minecraft.getBlockEntityRenderDispatcher().getRenderer(be)
                                    instanceof com.cbc_more_content.client.Aim9BlockRenderer,
                            "AIM-9 renderer registered");
                    require(!be.isLiveAirframe(), "Detached AIM-9 cannot draw phantom");
                    var model = minecraft
                            .getBlockRenderer()
                            .getBlockModel(facing.getAxis().isVertical() ? aim9.defaultBlockState() : state);
                    var quads = model.getQuads(null, null, RandomSource.create(0));
                    require(
                            !quads.isEmpty()
                                    && quads.stream().allMatch(q -> q.getSprite()
                                            .contents()
                                            .name()
                                            .equals(ResourceLocation.parse("cbc_more_content:block/aim9"))),
                            "AIM-9 model uses supplied texture in every direction");
                }
                var aim9Item = minecraft.getItemRenderer().getModel(new ItemStack(ModItems.AIM9.get()), null, null, 0);
                require(
                        !aim9Item.getQuads(null, null, RandomSource.create(0)).isEmpty(),
                        "AIM-9 inventory model baked");
                checked.add("AIM-9 item, six orientations and supplied texture baked; live renderer registered");
                Aim9ModelPixelCheck.run();
                checked.add("AIM-9 live renderer produces textured pixels in all six orientations");
                checked.add(
                        "Target marker pixels visible through hull depth at six camera rotations; view state restored");
                Files.writeString(Path.of("chain-client-check.txt"), "PASS\n" + String.join("\n", checked) + "\n");
                System.out.println("WARNAUTICS_CHAIN_CLIENT_CHECK_PASS " + checked);
            } catch (Throwable failure) {
                failure.printStackTrace();
                try {
                    Files.writeString(
                            Path.of("chain-client-check.txt"), "FAIL\n" + failure + "\n" + String.join("\n", checked));
                } catch (java.io.IOException io) {
                    failure.addSuppressed(io);
                }
            } finally {
                minecraft.stop();
            }
        }
    }
}
