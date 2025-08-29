package com.example.arslauria.mana;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Attach this provider to living entities. */
public class ManaProvider implements ICapabilitySerializable<CompoundTag> {
    public static final ResourceLocation KEY = ResourceLocation.fromNamespaceAndPath("arslauria", "mana"); // replace 'arslauria' if needed

    private final IMana impl = new ManaImpl();
    private final LazyOptional<IMana> optional = LazyOptional.of(() -> impl);

    @Override
    @Nonnull
    public <T> LazyOptional<T> getCapability(@Nonnull net.minecraftforge.common.capabilities.Capability<T> cap, @Nullable Direction side) {
        if (cap == ManaCapability.MANA) {
            return optional.cast();
        }
        return LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        if (impl instanceof ManaImpl m) {
            return m.serializeNBT();
        } else {
            CompoundTag t = new CompoundTag();
            t.putDouble("current", impl.getCurrent());
            t.putDouble("max", impl.getMax());
            t.putDouble("visibleFactor", impl.getVisibleFactor());
            return t;
        }
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        if (impl instanceof ManaImpl m) {
            m.deserializeNBT(nbt);
        } else {
            impl.setMax(nbt.getDouble("max"));
            impl.setCurrent(nbt.getDouble("current"));
            if (nbt.contains("visibleFactor")) impl.setVisibleFactor(nbt.getDouble("visibleFactor"));
        }
    }
}
