package com.example.arslauria.mana;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;

/** Stores capability token. Actual registration via RegisterCapabilitiesEvent. */
public final class ManaCapability {
    public static final Capability<IMana> MANA = CapabilityManager.get(new CapabilityToken<IMana>() {});
}
