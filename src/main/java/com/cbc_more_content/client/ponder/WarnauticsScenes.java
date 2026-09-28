package com.cbc_more_content.client.ponder;

import com.cbc_more_content.block.Aim9Block;
import com.cbc_more_content.block.C4Block;
import com.cbc_more_content.block.ChainConnectorBlock;
import com.cbc_more_content.block.CruiseMissileBlock;
import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.block.LandMineBlock;
import com.cbc_more_content.block.MoabBlock;
import com.cbc_more_content.block.SeaMineBlock;
import com.cbc_more_content.block.SirenBlock;
import com.cbc_more_content.registry.ModBlocks;
import com.cbc_more_content.registry.ModItems;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Staged teaching scenes. Explosions, ship motion and releases are visual instructions,
 * never live gameplay: replaying a lesson cannot run physics, send packets or damage a world.
 */
public final class WarnauticsScenes {
    private final WarnauticsPonder.Guide guide;
    private final CreateSceneBuilder scene;
    private final SceneBuildingUtil util;
    private static final BlockPos CENTRE = new BlockPos(4, 3, 4);

    public WarnauticsScenes(WarnauticsPonder.Guide guide, SceneBuilder scene, SceneBuildingUtil util) {
        this.guide = guide;
        this.scene = new CreateSceneBuilder(scene);
        this.util = util;
    }

    public void program() {
        scene.title(guide.id(), guide.title());
        scene.configureBasePlate(0, 0, 9);
        scene.scaleSceneView(.72f);
        scene.setSceneOffsetY(-1);
        scene.showBasePlate();
        scene.world().showSection(box(0, 1, 0, 8, 2, 8), Direction.UP);
        scene.idle(12);
        switch (guide.id()) {
            case "small_bomb" -> dropBomb(ModBlocks.SMALL_BOMB.get(), 1);
            case "small_bomb_2" -> cassette(2);
            case "small_bomb_3" -> cassette(3);
            case "small_bomb_4" -> cassette(4);
            case "medium_bomb" -> dropBomb(ModBlocks.MEDIUM_BOMB.get(), 2);
            case "large_bomb" -> dropBomb(ModBlocks.LARGE_BOMB.get(), 3);
            case "moab" -> moab();
            case "sea_bomb" -> torpedo();
            case "small_mine" -> mine(ModBlocks.SMALL_MINE.get(), 0);
            case "bounding_mine" -> mine(ModBlocks.BOUNDING_MINE.get(), 1);
            case "large_mine" -> mine(ModBlocks.LARGE_MINE.get(), 2);
            case "sea_mine" -> seaMine();
            case "cruise_missile" -> missile(false);
            case "target_designator" -> missile(true);
            case "aim9" -> aim9();
            case "c4" -> c4();
            case "detonator" -> detonator();
            case "bomb_vest" -> vest();
            case "wire_cutters" -> cutters();
            case "tripwire_coil" -> tripwire();
            case "chain_coil" -> chain(false);
            case "chain_connector" -> chain(true);
            case "siren" -> siren();
            case "settings_key" -> settings();
            case "music_disc_breaker_of_skies" -> record();
            default -> throw new IllegalArgumentException("Missing storyboard: " + guide.id());
        }
        scene.markAsFinished();
    }

    private Selection box(int x, int y, int z, int xx, int yy, int zz) {
        return util.select().fromTo(x, y, z, xx, yy, zz);
    }

    private Selection at(BlockPos pos) {
        return util.select().position(pos);
    }

    private void put(BlockPos pos, BlockState state) {
        scene.world().setBlock(pos, state, false);
        scene.world().showSection(at(pos), Direction.DOWN);
    }

    private void put(BlockPos pos, Block block) {
        put(pos, block.defaultBlockState());
    }

    private void text(int index, BlockPos pos) {
        int duration = caption(index, pos);
        scene.idle(duration + 12);
    }

    /** Starts a narrated keyframe without waiting, so the mechanism works while its caption is visible. */
    private int caption(int index, BlockPos pos) {
        scene.addKeyframe();
        int duration = Math.max(130, Math.min(210, guide.steps().get(index).length()));
        scene.overlay().showText(duration).sharedText(guide.id() + "_" + index).pointAt(Vec3.atCenterOf(pos));
        return duration;
    }

    private void use(Item item, BlockPos pos) {
        scene.overlay()
                .showControls(Vec3.atCenterOf(pos).add(0, 1, 0), Pointing.DOWN, 60)
                .rightClick()
                .withItem(new ItemStack(item));
    }

    private void sneak(Item item, BlockPos pos) {
        scene.overlay()
                .showControls(Vec3.atCenterOf(pos).add(0, 1, 0), Pointing.DOWN, 60)
                .rightClick()
                .whileSneaking()
                .withItem(new ItemStack(item));
    }

    private void highlight(Selection selection, PonderPalette color) {
        scene.overlay().showOutline(color, new Object(), selection, 90);
    }

    private BlockPos lever() {
        BlockPos pos = new BlockPos(2, 3, 2);
        put(
                pos,
                Blocks.LEVER
                        .defaultBlockState()
                        .setValue(
                                BlockStateProperties.ATTACH_FACE,
                                net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
        return pos;
    }

    private void power(BlockPos lever, BlockPos device) {
        scene.overlay()
                .showControls(Vec3.atCenterOf(lever).add(0, 1, 0), Pointing.DOWN, 25)
                .rightClick()
                .withItem(new ItemStack(Items.LEVER));
        scene.world().toggleRedstonePower(at(lever));
        scene.effects().indicateRedstone(lever);
        scene.overlay().showLine(PonderPalette.RED, Vec3.atCenterOf(lever), Vec3.atCenterOf(device), 70);
    }

    private void burst(BlockPos pos, int radius) {
        scene.effects()
                .emitParticles(
                        Vec3.atCenterOf(pos),
                        scene.effects().simpleParticleEmitter(ParticleTypes.EXPLOSION, Vec3.ZERO),
                        1,
                        1);
        scene.effects()
                .emitParticles(
                        Vec3.atCenterOf(pos).add(0, .25, 0),
                        scene.effects().simpleParticleEmitter(ParticleTypes.POOF, new Vec3(0, .09, 0)),
                        1.5f,
                        28);
        scene.effects()
                .emitParticles(
                        Vec3.atCenterOf(pos).add(0, .3, 0),
                        scene.effects().simpleParticleEmitter(ParticleTypes.SMOKE, new Vec3(0, .06, 0)),
                        .4f,
                        38);
        for (int x = -radius - 1; x <= radius + 1; x++) {
            for (int z = -radius - 1; z <= radius + 1; z++) {
                int distance = x * x + z * z;
                BlockPos top = new BlockPos(pos.getX() + x, 2, pos.getZ() + z);
                if (top.getX() < 0 || top.getX() > 8 || top.getZ() < 0 || top.getZ() > 8) {
                    continue;
                }
                if (distance <= radius * radius) {
                    scene.world().destroyBlock(top);
                    if (distance <= Math.max(0, (radius - 1) * (radius - 1))) {
                        scene.world().destroyBlock(top.below());
                    }
                } else if (distance <= (radius + 1) * (radius + 1)) {
                    Block[] soils = {Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL};
                    scene.world().setBlock(top, soils[Math.floorMod(x + z, soils.length)].defaultBlockState(), false);
                }
            }
        }
        scene.idle(12);
    }

    private ElementLink<WorldSectionElement> independent(BlockPos pos, BlockState state) {
        scene.world().setBlock(pos, state, false);
        return scene.world().showIndependentSection(at(pos), Direction.DOWN);
    }

    private void dropBomb(Block block, int radius) {
        BlockPos mount = new BlockPos(4, 6, 4);
        put(mount.above(), Blocks.IRON_BLOCK);
        BlockState bomb = block.defaultBlockState().setValue(DropBombBlock.FACING, Direction.DOWN);
        var falling = independent(mount, bomb);
        BlockPos lever = lever();
        text(0, mount);
        use(AllItems.WRENCH.get(), mount);
        highlight(at(mount), PonderPalette.GREEN);
        text(1, mount);
        int fallingTime = caption(2, CENTRE);
        power(lever, mount);
        scene.world().moveSection(falling, new Vec3(0, -3, 0), 60);
        scene.idle(fallingTime + 12);
        int impactTime = caption(3, CENTRE.below());
        scene.world().hideIndependentSection(falling, Direction.DOWN);
        burst(CENTRE, radius);
        scene.idle(impactTime);
        if (radius == 1) {
            BlockPos bundle = new BlockPos(6, 3, 6);
            put(bundle, bomb);
            use(ModItems.SMALL_BOMB.get(), bundle);
            for (int count = 2; count <= 4; count++) {
                scene.world().modifyBlock(bundle, state -> state.cycle(DropBombBlock.CASSETTE), false);
                scene.idle(12);
            }
            text(4, bundle);
        } else if (radius == 3) {
            var hull = hull(new BlockPos(1, 4, 6));
            scene.world().moveSection(hull, new Vec3(-.5, .5, .5), 30);
            text(4, new BlockPos(2, 4, 6));
        } else {
            highlight(box(1, 2, 1, 7, 2, 7), PonderPalette.GREEN);
            text(4, new BlockPos(6, 2, 6));
        }
        BlockPos reserve = new BlockPos(7, 3, 2);
        put(reserve, ModBlocks.SMALL_BOMB.get());
        highlight(at(reserve), PonderPalette.RED);
        text(5, reserve);
    }

    private void cassette(int count) {
        BlockPos mount = new BlockPos(4, 6, 4);
        put(mount.above(), Blocks.IRON_BLOCK);
        BlockState single = ModBlocks.SMALL_BOMB.get().defaultBlockState();
        put(mount, single.setValue(DropBombBlock.CASSETTE, count));
        BlockPos lever = lever();
        text(0, mount);
        use(ModItems.SETTINGS_KEY.get(), mount);
        text(1, mount);
        scene.overlay().showScrollInput(Vec3.atCenterOf(mount), Direction.WEST, 80);
        text(2, mount);
        int releaseTime = caption(3, CENTRE);
        power(lever, mount);
        for (int i = 0; i < count; i++) {
            BlockPos spare = new BlockPos(12 + i, 6, 4);
            var dropped = independent(spare, single);
            scene.world().moveSection(dropped, new Vec3(-8 - i, 0, 0), 0);
            int left = count - i - 1;
            scene.world()
                    .setBlock(
                            mount,
                            left == 0 ? Blocks.AIR.defaultBlockState() : single.setValue(DropBombBlock.CASSETTE, left),
                            false);
            scene.world().moveSection(dropped, new Vec3(i * .4, -3, 0), 26);
            scene.idle(27);
            scene.world().hideIndependentSection(dropped, Direction.DOWN);
            burst(CENTRE.offset(i, 0, 0), 1);
        }
        scene.idle(Math.max(12, releaseTime - count * 39));
        put(mount, single.setValue(DropBombBlock.CASSETTE, count - 1));
        power(lever, mount);
        highlight(at(mount), PonderPalette.GREEN);
        text(4, mount);
        int chainTime = caption(5, mount);
        scene.world().setBlock(mount, Blocks.AIR.defaultBlockState(), false);
        for (int i = 0; i < count; i++) {
            scene.effects()
                    .emitParticles(
                            Vec3.atCenterOf(mount).add(i * .35, -.25 * i, 0),
                            scene.effects().simpleParticleEmitter(ParticleTypes.EXPLOSION, Vec3.ZERO),
                            1,
                            1);
            burst(CENTRE.offset(i - 1, 0, 1), 1);
            scene.idle(12);
        }
        scene.idle(chainTime);
    }

    private Selection airframe(Block block, BlockPos body, Direction direction) {
        BlockState state = block.defaultBlockState().setValue(BlockStateProperties.FACING, direction);
        for (int part = -1; part <= 1; part++) {
            BlockState segment;
            if (block instanceof MoabBlock) {
                segment = state.setValue(
                        MoabBlock.PART,
                        part == -1 ? MoabBlock.Part.TAIL : part == 0 ? MoabBlock.Part.BODY : MoabBlock.Part.NOSE);
            } else if (block instanceof Aim9Block) {
                segment = state.setValue(
                        Aim9Block.PART,
                        part == -1 ? Aim9Block.Part.TAIL : part == 0 ? Aim9Block.Part.BODY : Aim9Block.Part.NOSE);
            } else {
                segment = state.setValue(
                        CruiseMissileBlock.PART,
                        part == -1
                                ? CruiseMissileBlock.Part.TAIL
                                : part == 0 ? CruiseMissileBlock.Part.BODY : CruiseMissileBlock.Part.NOSE);
            }
            scene.world().setBlock(body.relative(direction, part), segment, false);
        }
        return util.select().fromTo(body.relative(direction, -1), body.relative(direction));
    }

    private void moab() {
        BlockPos body = new BlockPos(4, 5, 4);
        Selection shape = airframe(ModBlocks.MOAB.get(), body, Direction.DOWN);
        scene.world().showSection(shape, Direction.DOWN);
        highlight(shape, PonderPalette.GREEN);
        text(0, body);
        use(ModItems.MOAB.get(), body);
        text(1, body);
        use(AllItems.WRENCH.get(), body);
        scene.rotateCameraY(45);
        text(2, body);
        BlockPos lever = lever();
        int fallingTime = caption(3, body.below());
        power(lever, body);
        var falling = scene.world().makeSectionIndependent(shape);
        scene.world().moveSection(falling, new Vec3(0, -1.5, 0), 65);
        scene.idle(fallingTime + 12);
        int impactTime = caption(4, CENTRE.below());
        scene.world().hideIndependentSection(falling, Direction.DOWN);
        burst(CENTRE, 3);
        scene.idle(impactTime);
        var hull = hull(new BlockPos(1, 4, 7));
        int hullTime = caption(5, new BlockPos(2, 4, 7));
        scene.world().destroyBlock(new BlockPos(2, 4, 7));
        scene.effects()
                .emitParticles(
                        Vec3.atCenterOf(new BlockPos(2, 4, 7)),
                        scene.effects().simpleParticleEmitter(ParticleTypes.POOF, Vec3.ZERO),
                        2,
                        20);
        scene.world().moveSection(hull, new Vec3(-.6, .25, .5), 55);
        scene.idle(hullTime + 12);
    }

    private void pools() {
        scene.world().setBlocks(box(0, 2, 2, 2, 2, 6), Blocks.WATER.defaultBlockState(), false);
        scene.world().setBlocks(box(5, 2, 2, 8, 2, 6), Blocks.WATER.defaultBlockState(), false);
    }

    private void torpedo() {
        pools();
        BlockPos start = new BlockPos(1, 2, 4);
        var torpedo = independent(
                start,
                ModBlocks.SEA_BOMB
                        .get()
                        .defaultBlockState()
                        .setValue(DropBombBlock.FACING, Direction.EAST)
                        .setValue(DropBombBlock.WATERLOGGED, true));
        text(0, start);
        power(lever(), start);
        text(1, start);
        int waterTime = caption(2, new BlockPos(2, 2, 4));
        scene.overlay().showLine(PonderPalette.BLUE, new Vec3(1, 2.7, 4.5), new Vec3(3, 2.7, 4.5), 100);
        scene.world().moveSection(torpedo, new Vec3(1.7, .4, 0), 65);
        scene.effects()
                .emitParticles(
                        new Vec3(2.5, 2.7, 4.5),
                        scene.effects().simpleParticleEmitter(ParticleTypes.BUBBLE, new Vec3(-.05, .03, 0)),
                        1,
                        55);
        scene.idle(waterTime + 12);
        int airTime = caption(3, new BlockPos(4, 3, 4));
        Vec3[] arc = {new Vec3(.5, .55, 0), new Vec3(.5, .4, 0), new Vec3(.5, -.05, 0)};
        for (int i = 0; i < arc.length; i++) {
            scene.world().moveSection(torpedo, arc[i], 22);
            scene.world().rotateSection(torpedo, 0, 0, i < 2 ? 10 : -14, 22);
            scene.idle(22);
        }
        scene.idle(airTime - 66 + 12);
        int reentryTime = caption(4, new BlockPos(6, 2, 4));
        for (Vec3 delta : new Vec3[] {new Vec3(.5, -.35, 0), new Vec3(.5, -.55, 0)}) {
            scene.world().moveSection(torpedo, delta, 22);
            scene.world().rotateSection(torpedo, 0, 0, -14, 22);
            scene.idle(22);
        }
        scene.world().rotateSection(torpedo, 0, 0, 22, 25);
        scene.world().moveSection(torpedo, new Vec3(1.5, -.3, 0), 55);
        spray(new BlockPos(6, 2, 4));
        scene.idle(reentryTime - 44 + 12);
        for (BlockPos plank : new BlockPos[] {
            new BlockPos(7, 2, 3), new BlockPos(7, 2, 4), new BlockPos(7, 2, 5), new BlockPos(7, 3, 4)
        }) {
            put(plank, Blocks.OAK_PLANKS);
        }
        int impactTime = caption(5, new BlockPos(7, 2, 4));
        scene.world().moveSection(torpedo, new Vec3(.5, 0, 0), 28);
        scene.idle(28);
        scene.world().hideIndependentSection(torpedo, Direction.EAST);
        scene.world().destroyBlock(new BlockPos(7, 2, 4));
        scene.world().destroyBlock(new BlockPos(7, 3, 4));
        scene.world().destroyBlock(new BlockPos(7, 2, 5));
        splash(new BlockPos(7, 2, 4));
        scene.idle(impactTime - 28 + 12);
    }

    private ElementLink<WorldSectionElement> hull(BlockPos pos) {
        Selection planks = util.select().fromTo(pos, pos.offset(2, 0, 1));
        scene.world().setBlocks(planks, Blocks.OAK_PLANKS.defaultBlockState(), false);
        return scene.world().showIndependentSection(planks, Direction.DOWN);
    }

    private ElementLink<EntityElement> person(BlockPos pos) {
        return scene.world().createEntity(level -> {
            ArmorStand stand = new ArmorStand(level, pos.getX() + .5, pos.getY(), pos.getZ() + .5);
            stand.setNoGravity(true);
            stand.setYRot(90);
            stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            stand.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
            return stand;
        });
    }

    private void mine(Block block, int type) {
        BlockState state = block.defaultBlockState();
        put(CENTRE, state);
        text(0, CENTRE);
        scene.world().modifyBlock(CENTRE, s -> s.setValue(LandMineBlock.ARMED, true), false);
        scene.effects().indicateSuccess(CENTRE);
        text(1, CENTRE);
        use(Items.IRON_SHOVEL, CENTRE);
        scene.world().modifyBlock(CENTRE, s -> s.setValue(LandMineBlock.BURIAL, 4), false);
        text(2, CENTRE);
        ElementLink<EntityElement> walker = person(new BlockPos(2, 3, 4));
        if (type == 0) {
            scene.world().modifyBlock(CENTRE, s -> s.setValue(LandMineBlock.BURIAL, 8), false);
            sneak(Items.IRON_SHOVEL, CENTRE);
            scene.idle(40);
            scene.world().modifyBlock(CENTRE, s -> s.setValue(LandMineBlock.BURIAL, 0), false);
        } else if (type == 1) {
            scene.world().modifyBlock(CENTRE, s -> s.setValue(LandMineBlock.BURIAL, 0), false);
            scene.world().modifyEntity(walker, e -> e.setPos(3.7, 3, 4.5));
            var jumping = scene.world().makeSectionIndependent(at(CENTRE));
            scene.world().moveSection(jumping, new Vec3(0, 1.5, 0), 15);
            scene.idle(18);
            scene.world().hideIndependentSection(jumping, Direction.UP);
        } else {
            scene.world().modifyEntity(walker, e -> e.setPos(4.5, 3, 4.5));
            highlight(at(CENTRE), PonderPalette.GREEN);
        }
        text(3, CENTRE);
        if (type == 2) {
            scene.world().modifyEntity(walker, e -> e.setPos(7.5, 3, 2.5));
            var moving = hull(new BlockPos(1, 3, 4));
            scene.world().moveSection(moving, new Vec3(2, 0, 0), 35);
            scene.idle(35);
            scene.world().moveSection(moving, new Vec3(-.35, .7, .2), 15);
        } else if (type == 0) {
            scene.world().modifyEntity(walker, e -> e.setPos(4.5, 3, 4.5));
        }
        int blastTime = caption(4, type == 1 ? CENTRE.above() : CENTRE);
        scene.world().setBlock(CENTRE, Blocks.AIR.defaultBlockState(), false);
        if (type == 1) {
            scene.effects()
                    .emitParticles(
                            Vec3.atCenterOf(CENTRE.above()),
                            scene.effects().simpleParticleEmitter(ParticleTypes.EXPLOSION, Vec3.ZERO),
                            1,
                            1);
        } else {
            burst(CENTRE, type == 2 ? 2 : 1);
        }
        scene.idle(blastTime + 12);
        text(5, CENTRE);
    }

    private void spray(BlockPos pos) {
        scene.effects()
                .emitParticles(
                        Vec3.atCenterOf(pos),
                        scene.effects().simpleParticleEmitter(ParticleTypes.SPLASH, new Vec3(0, .2, 0)),
                        4,
                        28);
    }

    private void splash(BlockPos pos) {
        spray(pos);
        scene.effects()
                .emitParticles(
                        Vec3.atCenterOf(pos),
                        scene.effects().simpleParticleEmitter(ParticleTypes.EXPLOSION, Vec3.ZERO),
                        1,
                        1);
        scene.effects()
                .emitParticles(
                        Vec3.atCenterOf(pos),
                        scene.effects().simpleParticleEmitter(ParticleTypes.BUBBLE, new Vec3(0, .08, 0)),
                        2,
                        45);
    }

    private void seaMine() {
        scene.world().setBlocks(box(1, 2, 1, 7, 2, 7), Blocks.WATER.defaultBlockState(), false);
        put(CENTRE, ModBlocks.SEA_MINE.get());
        text(0, CENTRE);
        var ship = hull(new BlockPos(1, 3, 5));
        int contactTime = caption(1, CENTRE);
        scene.world().moveSection(ship, new Vec3(1, 0, 0), 55);
        highlight(at(CENTRE), PonderPalette.RED);
        scene.idle(contactTime + 12);
        for (int stage = 0; stage < 4; stage++) {
            BlockPos pos = new BlockPos(1 + stage * 2, 3, 2);
            put(pos, ModBlocks.SEA_MINE.get().defaultBlockState().setValue(SeaMineBlock.OXIDATION, stage));
            scene.idle(15);
        }
        text(2, new BlockPos(4, 3, 2));
        scene.overlay().showLine(PonderPalette.BLUE, new Vec3(1, 4, 2.5), new Vec3(8, 4, 2.5), 110);
        text(3, new BlockPos(5, 3, 2));
        scene.world().modifyBlock(CENTRE, s -> s.setValue(SeaMineBlock.OXIDATION, 3), false);
        int missTime = caption(4, CENTRE);
        scene.world().moveSection(ship, new Vec3(.6, 0, -.4), 35);
        scene.idle(38);
        scene.world().moveSection(ship, new Vec3(-.35, 0, .2), 25);
        highlight(at(CENTRE), PonderPalette.RED);
        scene.idle(missTime - 38 + 12);
        int impactTime = caption(5, CENTRE);
        scene.world().setBlock(CENTRE, Blocks.AIR.defaultBlockState(), false);
        splash(CENTRE);
        scene.world().destroyBlock(new BlockPos(2, 3, 5));
        scene.world().destroyBlock(new BlockPos(3, 3, 5));
        scene.world().moveSection(ship, new Vec3(-.7, .5, .4), 45);
        scene.idle(impactTime + 12);
    }

    private void missile(boolean designator) {
        BlockPos launcher = new BlockPos(2, 4, 4);
        Selection shape = airframe(ModBlocks.CRUISE_MISSILE.get(), launcher, Direction.UP);
        scene.world().showSection(shape, Direction.DOWN);
        BlockPos target = new BlockPos(6, 3, 6);
        var moving = hull(target);
        use(ModItems.SETTINGS_KEY.get(), launcher);
        text(0, launcher);
        use(designator ? ModItems.TARGET_DESIGNATOR.get() : ModItems.SETTINGS_KEY.get(), launcher);
        text(1, launcher);
        scene.world().moveSection(moving, new Vec3(-.5, 0, 0), 50);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.BLUE, "target", new AABB(5.5, 3, 6, 8.5, 4, 8), 100);
        text(2, target);
        int lockTime = caption(3, target);
        use(ModItems.TARGET_DESIGNATOR.get(), target);
        scene.overlay().showLine(PonderPalette.BLUE, Vec3.atCenterOf(launcher), Vec3.atCenterOf(target), 80);
        scene.idle(40);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, "target", new AABB(5.5, 3, 6, 8.5, 4, 8), 100);
        scene.effects().indicateSuccess(target);
        scene.idle(lockTime - 40 + 12);
        if (designator) {
            int launchTime = caption(4, launcher);
            scene.overlay()
                    .showControls(Vec3.atCenterOf(launcher).add(0, 1, 0), Pointing.DOWN, 60)
                    .leftClick()
                    .withItem(new ItemStack(ModItems.TARGET_DESIGNATOR.get()));
            var rocket = scene.world().makeSectionIndependent(shape);
            scene.world().moveSection(rocket, new Vec3(0, 3, 0), 55);
            scene.effects()
                    .emitParticles(
                            Vec3.atCenterOf(launcher),
                            scene.effects().simpleParticleEmitter(ParticleTypes.FLAME, new Vec3(0, -.04, 0)),
                            1.5f,
                            45);
            scene.idle(55);
            scene.world().hideIndependentSection(rocket, Direction.UP);
            scene.idle(launchTime - 55 + 12);
        } else {
            highlight(box(0, 3, 2, 3, 5, 3), PonderPalette.BLUE);
            text(4, launcher);
        }
        if (designator) {
            put(new BlockPos(4, 3, 5), Blocks.STONE_BRICKS);
            put(new BlockPos(4, 4, 5), Blocks.STONE_BRICKS);
            highlight(box(4, 3, 5, 4, 4, 5), PonderPalette.RED);
            text(5, target);
        } else {
            int impactTime = caption(5, target);
            var rocket = scene.world().makeSectionIndependent(shape);
            scene.world().moveSection(rocket, new Vec3(4, -1, 2), 65);
            scene.effects()
                    .emitParticles(
                            Vec3.atCenterOf(launcher),
                            scene.effects().simpleParticleEmitter(ParticleTypes.FLAME, new Vec3(0, -.03, 0)),
                            1,
                            45);
            scene.idle(65);
            scene.world().hideIndependentSection(rocket, Direction.DOWN);
            scene.world().destroyBlock(target);
            scene.world().destroyBlock(target.east());
            scene.world().moveSection(moving, new Vec3(.7, .3, .5), 35);
            burst(new BlockPos(6, 3, 6), 2);
            scene.idle(impactTime - 65 + 12);
        }
    }

    private void aim9() {
        Selection shape = airframe(ModBlocks.AIM9.get(), CENTRE.above(), Direction.EAST);
        scene.world().showSection(shape, Direction.DOWN);
        highlight(shape, PonderPalette.GREEN);
        text(0, CENTRE.above());
        sneak(ModItems.AIM9.get(), CENTRE.above());
        text(1, CENTRE.above());
        put(new BlockPos(4, 3, 4), Blocks.IRON_BLOCK);
        text(2, CENTRE);
        use(AllItems.WRENCH.get(), CENTRE.above());
        var frame = scene.world().makeSectionIndependent(shape);
        scene.world().configureCenterOfRotation(frame, Vec3.atCenterOf(CENTRE.above()));
        scene.world().rotateSection(frame, 0, 90, 0, 30);
        text(3, CENTRE.above());
        sneak(AllItems.WRENCH.get(), CENTRE.above());
        scene.world().hideIndependentSection(frame, Direction.UP);
        var picked = scene.world()
                .createItemEntity(Vec3.atCenterOf(CENTRE.above()), Vec3.ZERO, new ItemStack(ModItems.AIM9.get()));
        scene.world().modifyEntity(picked, e -> e.setNoGravity(true));
        text(4, CENTRE.above());
        highlight(at(CENTRE), PonderPalette.RED);
        text(5, CENTRE.above());
    }

    private void charge(BlockPos pos, boolean remote) {
        put(
                pos,
                ModBlocks.C4
                        .get()
                        .defaultBlockState()
                        .setValue(C4Block.FACING, Direction.UP)
                        .setValue(C4Block.RECEIVER, remote));
    }

    private void armed(BlockPos pos, boolean remote) {
        scene.world()
                .modifyBlock(
                        pos,
                        s -> s.setValue(C4Block.STATE, C4Block.Fuse.values()[1]).setValue(C4Block.RECEIVER, remote),
                        false);
        highlight(at(pos), PonderPalette.RED);
    }

    private void c4() {
        BlockPos pos = CENTRE;
        charge(pos, false);
        use(ModItems.C4.get(), pos);
        text(0, pos);
        use(ModItems.SETTINGS_KEY.get(), pos);
        text(1, pos);
        armed(pos, false);
        text(2, pos);
        scene.world().modifyBlock(pos, s -> s.setValue(C4Block.RECEIVER, true), false);
        use(ModItems.DETONATOR.get(), pos);
        text(3, pos);
        use(ModItems.SETTINGS_KEY.get(), pos);
        scene.world()
                .modifyBlock(
                        pos,
                        s -> s.setValue(C4Block.STATE, C4Block.Fuse.IDLE).setValue(C4Block.RECEIVER, false),
                        false);
        scene.effects().indicateSuccess(pos);
        text(4, pos);
        armed(pos, false);
        int dangerTime = caption(5, pos);
        scene.world().setBlock(pos, Blocks.AIR.defaultBlockState(), false);
        burst(pos, 1);
        scene.idle(dangerTime + 12);
    }

    private void detonator() {
        BlockPos first = new BlockPos(2, 3, 4);
        BlockPos second = new BlockPos(6, 3, 4);
        charge(first, true);
        charge(second, true);
        armed(first, true);
        armed(second, true);
        use(ModItems.SETTINGS_KEY.get(), first);
        text(0, first);
        use(ModItems.DETONATOR.get(), first);
        scene.idle(30);
        use(ModItems.DETONATOR.get(), second);
        text(1, second);
        sneak(ModItems.DETONATOR.get(), second);
        highlight(at(first), PonderPalette.GREEN);
        text(2, second);
        int fireTime = caption(3, first);
        use(ModItems.DETONATOR.get(), CENTRE.above());
        scene.overlay().showLine(PonderPalette.RED, Vec3.atCenterOf(CENTRE.above()), Vec3.atCenterOf(first), 50);
        scene.world().setBlock(first, Blocks.AIR.defaultBlockState(), false);
        burst(first, 1);
        scene.idle(fireTime + 12);
        highlight(at(second), PonderPalette.RED);
        text(4, second);
        sneak(ModItems.DETONATOR.get(), CENTRE.above());
        text(5, CENTRE.above());
    }

    private void vest() {
        var wearer = person(CENTRE);
        scene.world().modifyEntity(wearer, e -> ((ArmorStand) e)
                .setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.BOMB_VEST.get())));
        text(0, CENTRE.above());
        sneak(ModItems.DETONATOR.get(), CENTRE.above());
        text(1, CENTRE.above());
        scene.effects().indicateSuccess(CENTRE);
        text(2, CENTRE);
        int vestTime = caption(3, CENTRE);
        use(ModItems.DETONATOR.get(), CENTRE.above());
        burst(CENTRE, 1);
        scene.idle(vestTime + 12);
        sneak(ModItems.DETONATOR.get(), CENTRE.above());
        text(4, CENTRE.above());
        scene.world().modifyEntity(wearer, e -> ((ArmorStand) e).setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY));
        text(5, CENTRE.above());
    }

    private void posts() {
        put(new BlockPos(1, 3, 4), Blocks.OAK_FENCE);
        put(new BlockPos(7, 3, 4), Blocks.OAK_FENCE);
    }

    private void wire(PonderPalette color, int duration) {
        scene.overlay().showLine(color, new Vec3(1.5, 3.5, 4.5), new Vec3(7.5, 3.5, 4.5), duration);
    }

    private void tripwire() {
        posts();
        text(0, new BlockPos(1, 3, 4));
        use(ModItems.TRIPWIRE_COIL.get(), new BlockPos(1, 3, 4));
        scene.idle(30);
        use(ModItems.TRIPWIRE_COIL.get(), new BlockPos(7, 3, 4));
        wire(PonderPalette.BLUE, 180);
        text(1, CENTRE);
        wire(PonderPalette.BLUE, 180);
        highlight(box(1, 3, 4, 7, 3, 4), PonderPalette.GREEN);
        text(2, CENTRE);
        var walker = person(new BlockPos(4, 3, 2));
        int crossingTime = caption(3, CENTRE);
        wire(PonderPalette.RED, 45);
        scene.idle(32);
        scene.world().modifyEntity(walker, e -> e.setPos(4.5, 3, 5.5));
        scene.effects().indicateRedstone(new BlockPos(1, 3, 4));
        scene.effects().indicateRedstone(new BlockPos(7, 3, 4));
        scene.idle(crossingTime - 32 + 12);
        put(new BlockPos(7, 3, 5), Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true));
        text(4, new BlockPos(7, 3, 5));
        use(ModItems.WIRE_CUTTERS.get(), new BlockPos(1, 3, 4));
        text(5, new BlockPos(1, 3, 4));
    }

    private void cutters() {
        posts();
        wire(PonderPalette.BLUE, 140);
        use(ModItems.WIRE_CUTTERS.get(), new BlockPos(1, 3, 4));
        text(0, new BlockPos(1, 3, 4));
        scene.effects().indicateSuccess(new BlockPos(1, 3, 4));
        text(1, CENTRE);
        charge(CENTRE, false);
        armed(CENTRE, false);
        use(ModItems.WIRE_CUTTERS.get(), CENTRE);
        text(2, CENTRE);
        for (int i = 0; i < 3; i++) {
            PonderPalette color = i == 0 ? PonderPalette.RED : i == 1 ? PonderPalette.BLUE : PonderPalette.GREEN;
            scene.overlay().showBigLine(color, new Vec3(3.7 + .3 * i, 4, 4), new Vec3(3.7 + .3 * i, 4, 5), 160);
        }
        text(3, CENTRE);
        highlight(at(CENTRE), PonderPalette.RED);
        text(4, CENTRE);
        use(ModItems.SETTINGS_KEY.get(), CENTRE);
        scene.world().modifyBlock(CENTRE, s -> s.setValue(C4Block.STATE, C4Block.Fuse.IDLE), false);
        text(5, CENTRE);
    }

    private void chain(boolean connector) {
        BlockPos anchor = new BlockPos(2, 6, 4);
        BlockPos load = new BlockPos(5, 3, 4);
        put(anchor.above(), Blocks.IRON_BLOCK);
        put(
                anchor,
                ModBlocks.CHAIN_CONNECTOR
                        .get()
                        .defaultBlockState()
                        .setValue(BlockStateProperties.FACING, Direction.DOWN));
        put(load, Blocks.IRON_BLOCK);
        put(
                load.above(),
                ModBlocks.CHAIN_CONNECTOR
                        .get()
                        .defaultBlockState()
                        .setValue(BlockStateProperties.FACING, Direction.UP));
        text(0, anchor);
        use(ModItems.CHAIN_COIL.get(), anchor);
        scene.idle(30);
        use(ModItems.CHAIN_COIL.get(), load.above());
        text(1, load.above());
        // Render the same textured strand used by the live Simulated connection.
        scene.addInstruction(
                ponder -> ponder.addElement(new PonderChainElement(new Vec3(2.5, 6.2, 4.5), new Vec3(5.5, 4.5, 4.5))));
        if (connector) {
            highlight(at(anchor), PonderPalette.RED);
        } else {
            scene.effects().indicateSuccess(load.above());
        }
        text(2, connector ? anchor : load.above());
        var moving = scene.world().makeSectionIndependent(util.select().fromTo(load, load.above()));
        int loadTime = caption(3, load);
        scene.world().moveSection(moving, new Vec3(-.75, -.1, 0), 60);
        scene.world().rotateSection(moving, 0, 0, 10, 60);
        scene.idle(loadTime + 12);
        if (connector) {
            scene.world()
                    .setBlock(
                            anchor,
                            ModBlocks.CHAIN_CONNECTOR
                                    .get()
                                    .defaultBlockState()
                                    .setValue(BlockStateProperties.FACING, Direction.DOWN)
                                    .setValue(ChainConnectorBlock.WATERLOGGED, true),
                            false);
            put(anchor.east(), Blocks.WATER);
            use(Items.WATER_BUCKET, anchor);
        } else {
            scene.world().moveSection(moving, new Vec3(-.5, 1, 0), 35);
        }
        text(4, connector ? anchor : load.above());
        if (!connector) {
            sneak(ModItems.CHAIN_COIL.get(), anchor);
        }
        text(5, anchor);
    }

    private void siren() {
        BlockPos shaft = CENTRE;
        BlockPos siren = CENTRE.above();
        put(shaft, AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        put(siren, ModBlocks.SIREN.get());
        text(0, shaft);
        scene.world().setKineticSpeed(util.select().fromTo(shaft, siren), 64);
        scene.effects().rotationSpeedIndicator(shaft);
        text(1, siren);
        BlockPos lever = lever();
        power(lever, siren);
        scene.world()
                .modifyBlock(
                        siren, s -> s.setValue(SirenBlock.POWERED, true).setValue(SirenBlock.SOUNDING, true), false);
        text(2, siren);
        use(ModItems.SETTINGS_KEY.get(), siren);
        text(3, siren);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.BLUE, "watch", new AABB(0, 3, 0, 9, 6, 9), 150);
        text(4, siren);
        var moving = scene.world().makeSectionIndependent(util.select().fromTo(shaft, siren));
        scene.world().moveSection(moving, new Vec3(1.5, 0, 0), 40);
        text(5, siren.east());
    }

    private void settings() {
        BlockPos rack = new BlockPos(2, 4, 3);
        put(rack, ModBlocks.SMALL_BOMB.get().defaultBlockState().setValue(DropBombBlock.CASSETTE, 3));
        put(rack.above(), ModBlocks.SMALL_BOMB.get().defaultBlockState().setValue(DropBombBlock.CASSETTE, 2));
        use(ModItems.SETTINGS_KEY.get(), rack);
        highlight(util.select().fromTo(rack, rack.above()), PonderPalette.GREEN);
        text(0, rack);
        BlockPos missile = new BlockPos(6, 4, 3);
        Selection shape = airframe(ModBlocks.CRUISE_MISSILE.get(), missile, Direction.UP);
        scene.world().showSection(shape, Direction.DOWN);
        use(ModItems.SETTINGS_KEY.get(), missile);
        text(1, missile);
        BlockPos siren = new BlockPos(2, 3, 6);
        put(siren, ModBlocks.SIREN.get());
        use(ModItems.SETTINGS_KEY.get(), siren);
        text(2, siren);
        BlockPos c4 = new BlockPos(6, 3, 6);
        charge(c4, false);
        use(ModItems.SETTINGS_KEY.get(), c4);
        text(3, c4);
        highlight(shape, PonderPalette.BLUE);
        text(4, missile);
        sneak(ModItems.SETTINGS_KEY.get(), CENTRE.above());
        text(5, CENTRE.above());
    }

    private void record() {
        put(CENTRE, Blocks.JUKEBOX);
        text(0, CENTRE);
        use(ModItems.MUSIC_DISC_BREAKER_OF_SKIES.get(), CENTRE);
        scene.world().modifyBlock(CENTRE, s -> s.setValue(BlockStateProperties.HAS_RECORD, true), false);
        text(1, CENTRE);
        scene.effects()
                .emitParticles(
                        Vec3.atCenterOf(CENTRE.above()),
                        scene.effects().simpleParticleEmitter(ParticleTypes.NOTE, Vec3.ZERO),
                        .3f,
                        100);
        text(2, CENTRE.above());
        BlockPos comparator = CENTRE.west();
        put(
                comparator,
                Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        scene.effects().indicateRedstone(comparator);
        text(3, comparator);
        use(Items.AIR, CENTRE);
        scene.world().modifyBlock(CENTRE, s -> s.setValue(BlockStateProperties.HAS_RECORD, false), false);
        scene.world()
                .createItemEntity(Vec3.atCenterOf(CENTRE.above()), new Vec3(.1, .15, 0), new ItemStack(guide.item()));
        text(4, CENTRE);
        put(new BlockPos(6, 3, 6), ModBlocks.SIREN.get());
        text(5, new BlockPos(6, 3, 6));
    }
}
