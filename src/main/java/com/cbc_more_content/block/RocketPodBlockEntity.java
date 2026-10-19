package com.cbc_more_content.block;

import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.config.WarnauticsConfig;
import com.cbc_more_content.munitions.RocketProjectile;
import com.cbc_more_content.registry.ModBlockEntities;
import com.cbc_more_content.registry.ModEntityTypes;
import com.cbc_more_content.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

/** Magazine and firing circuit of a rocket pod: lives in its middle block. */
public class RocketPodBlockEntity extends BlockEntity {
    /** Tubes on the front face, in a ring as on the model. */
    public static final int TUBES = 8;
    /** Ring radius of the tubes on the front face, in blocks. */
    private static final double TUBE_RING = 0.3D;

    /** Fastest and slowest ripple the settings key can set, in ticks between rockets. */
    public static final int MIN_RIPPLE = 0;

    public static final int MAX_RIPPLE = 40;

    private int rockets;
    /** Ticks between rockets in a salvo; -1 until set, meaning the server default. */
    private int rippleTicks = -1;
    /** Rockets per redstone pulse, 0 = everything; -1 until set, meaning the server default. */
    private int salvoSize = -1;

    private boolean powered;
    private int salvoLeft;
    private int rippleTimer;
    private int nextTube;
    /** Rockets gone so far in the salvo under way, for pacing the launch effects. */
    private int firedInSalvo;

    public RocketPodBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ROCKET_POD.get(), pos, state);
    }

    public int rockets() {
        return rockets;
    }

    public int rippleTicks() {
        return rippleTicks >= 0 ? rippleTicks : WarnauticsConfig.rocketRippleTicks();
    }

    public int salvoSize() {
        return salvoSize >= 0 ? salvoSize : WarnauticsConfig.rocketsPerSalvo();
    }

    /** From the settings key: the ripple interval and how many rockets one pulse fires. */
    public void configure(int ripple, int salvo) {
        rippleTicks = Math.clamp(ripple, MIN_RIPPLE, MAX_RIPPLE);
        salvoSize = Math.clamp(salvo, 0, WarnauticsConfig.rocketPodCapacity());
        changed();
    }

    /** Saved, and sent to clients so the settings screen and the HUD see the same pod. */
    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(
                    worldPosition,
                    getBlockState(),
                    getBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    /** Loads up to {@code count} rockets; returns how many went in. */
    public int load(int count) {
        int room = Math.max(0, WarnauticsConfig.rocketPodCapacity() - rockets);
        int loaded = Math.max(0, Math.min(room, count));
        if (loaded > 0) {
            rockets += loaded;
            changed();
        }
        return loaded;
    }

    /** Takes every rocket out; returns how many there were. */
    public int unloadAll() {
        int count = rockets;
        rockets = 0;
        salvoLeft = 0;
        changed();
        return count;
    }

    /** Reads the firing circuit: any of the three blocks receiving a signal. A rising edge fires. */
    public void checkSignal() {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        boolean now = false;
        for (BlockPos cell : RocketPodBlock.partsOf(server, worldPosition, getBlockState())) {
            if (server.hasNeighborSignal(cell)) {
                now = true;
                break;
            }
        }
        if (now == powered) {
            return;
        }
        powered = now;
        setChanged();
        if (now) {
            fireSalvo();
        }
    }

    /** Starts a salvo: the configured number of rockets, or everything loaded. */
    public void fireSalvo() {
        if (!(level instanceof ServerLevel server) || salvoLeft > 0) {
            return;
        }
        if (rockets <= 0) {
            server.playSound(null, worldPosition, SoundEvents.DISPENSER_FAIL, SoundSource.BLOCKS, 0.8f, 1.2f);
            return;
        }
        int perSalvo = salvoSize();
        salvoLeft = perSalvo <= 0 ? rockets : Math.min(perSalvo, rockets);
        rippleTimer = 0;
        firedInSalvo = 0;
        setChanged();
        if (rippleTicks() <= 0) {
            while (salvoLeft > 0 && rockets > 0) {
                fireOne(server);
            }
            return;
        }
        // The first leaves on the signal itself: a pod out where nothing is ticking still
        // answers the button, and holds its chunk until the rest are gone.
        fireOne(server);
        rippleTimer = Math.max(1, rippleTicks());
        if (salvoLeft > 0) {
            com.cbc_more_content.munitions.FlightTicket.keepLoaded(server, worldPosition);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RocketPodBlockEntity pod) {
        if (!(level instanceof ServerLevel server) || pod.salvoLeft <= 0) {
            return;
        }
        if (pod.rockets <= 0) {
            pod.salvoLeft = 0;
            return;
        }
        com.cbc_more_content.munitions.FlightTicket.keepLoaded(server, pos);
        if (--pod.rippleTimer > 0) {
            return;
        }
        pod.rippleTimer = Math.max(1, pod.rippleTicks());
        pod.fireOne(server);
    }

    /**
     * One rocket out of the next tube in the ring. On a ship the tube, its direction and
     * the carrier's speed are all read off the ship as it is now.
     */
    private void fireOne(ServerLevel server) {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof RocketPodBlock) || !RocketPodBlock.formed(state)) {
            salvoLeft = 0;
            return;
        }
        Direction facing = state.getValue(RocketPodBlock.FACING);
        Vec3 forward = Vec3.atLowerCornerOf(facing.getNormal());
        // Two axes across the face, for the ring of tubes.
        Vec3 across = facing.getAxis() == Direction.Axis.Y ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 side = forward.cross(across).normalize();
        Vec3 up = side.cross(forward).normalize();
        double angle = (nextTube % TUBES) * (Math.PI * 2 / TUBES);
        nextTube = (nextTube + 1) % TUBES;
        Vec3 tube = side.scale(Math.cos(angle) * TUBE_RING).add(up.scale(Math.sin(angle) * TUBE_RING));
        BlockPos front = RocketPodBlock.frontOf(state, worldPosition);
        Vec3 local = front.getCenter().add(forward.scale(0.6D)).add(tube);

        var frame = SableDropCompat.resolveLaunch(server, front, local, Vec3.ZERO, forward);
        Vec3 heading = frame.orientation() == null ? forward : frame.orientation();
        RocketProjectile rocket = ModEntityTypes.ROCKET.get().create(frame.level());
        rockets--;
        salvoLeft--;
        changed();
        if (rocket == null) {
            return;
        }
        rocket.setPos(frame.pos());
        rocket.launch(heading, frame.vel(), server.random);
        frame.level().addFreshEntity(rocket);

        // The open rear of the tubes, at the back face of the pod.
        Vec3 rear = frame.pos().subtract(heading.scale(3.1D));
        // The whole show for the first of a salvo and every fourth after; the rest add only
        // their own flame, smoke and sound, which in a ripple reads just the same.
        boolean full = firedInSalvo % 4 == 0;
        firedInSalvo++;
        com.cbc_more_content.effects.BombBlastFx.playRocketLaunch(frame.level(), frame.pos(), rear, heading, full);
    }

    /** Hoppers, funnels and pipes: rockets in, nothing out. */
    public IItemHandler itemHandler() {
        return new IItemHandler() {
            @Override
            public int getSlots() {
                return 1;
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return rockets > 0 ? new ItemStack(ModItems.S8_ROCKET.get(), rockets) : ItemStack.EMPTY;
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (!stack.is(ModItems.S8_ROCKET.get())) {
                    return stack;
                }
                int room = Math.max(0, WarnauticsConfig.rocketPodCapacity() - rockets);
                int taken = Math.min(room, stack.getCount());
                if (taken <= 0) {
                    return stack;
                }
                if (!simulate) {
                    load(taken);
                }
                return stack.copyWithCount(stack.getCount() - taken);
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return ItemStack.EMPTY;
            }

            @Override
            public int getSlotLimit(int slot) {
                return WarnauticsConfig.rocketPodCapacity();
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return stack.is(ModItems.S8_ROCKET.get());
            }
        };
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Rockets", rockets);
        tag.putBoolean("Powered", powered);
        tag.putInt("SalvoLeft", salvoLeft);
        tag.putInt("NextTube", nextTube);
        tag.putInt("Ripple", rippleTicks);
        tag.putInt("Salvo", salvoSize);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        rockets = Math.max(0, tag.getInt("Rockets"));
        powered = tag.getBoolean("Powered");
        salvoLeft = Math.max(0, tag.getInt("SalvoLeft"));
        nextTube = Math.floorMod(tag.getInt("NextTube"), TUBES);
        rippleTicks = tag.contains("Ripple") ? tag.getInt("Ripple") : -1;
        salvoSize = tag.contains("Salvo") ? tag.getInt("Salvo") : -1;
    }
}
