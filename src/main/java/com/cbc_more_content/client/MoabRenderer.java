package com.cbc_more_content.client;

import com.cbc_more_content.munitions.DropBombProjectile;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonProjectileRenderer;

public class MoabRenderer extends BigCannonProjectileRenderer<DropBombProjectile> {
    public MoabRenderer(EntityRendererProvider.Context context) {
        super(context);
    }
}
