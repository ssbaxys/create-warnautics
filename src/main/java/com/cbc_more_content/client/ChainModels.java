package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

public final class ChainModels {
    public static final PartialModel STRAND = model("chain/rope");
    public static final PartialModel KNOT = model("chain/knot");
    public static final PartialModel CONNECTOR_KNOT = model("chain_connector/knot");

    private ChainModels() {}

    private static PartialModel model(String path) {
        return PartialModel.of(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "block/" + path));
    }

    public static void init() {}
}
