package com.example.arslauria.mana;

import net.minecraft.nbt.CompoundTag;

public class ManaImpl implements IMana {
    private double current = 1100.0;
    private double max = 1100.0;
    private double visibleFactor = 1.0;

    @Override public double getCurrent() { return current; }
    @Override public double getMax() { return max; }
    @Override public void setCurrent(double v) { current = Math.max(0.0, Math.min(v, max)); }
    @Override public void setMax(double v) { max = Math.max(0.0, v); if (current > max) current = max; }
    @Override public double getVisibleFactor() { return visibleFactor; }
    @Override public void setVisibleFactor(double f) { visibleFactor = Math.max(0.0, Math.min(1.0, f)); }

    // helper serialize/deserialize for provider
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("current", current);
        tag.putDouble("max", max);
        tag.putDouble("visibleFactor", visibleFactor);
        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("max")) this.max = tag.getDouble("max");
        if (tag.contains("current")) this.current = Math.max(0.0, Math.min(tag.getDouble("current"), max));
        if (tag.contains("visibleFactor")) this.visibleFactor = Math.max(0.0, Math.min(tag.getDouble("visibleFactor"), 1.0));
    }
}
