package com.example.arslauria.glyphs.events;

import com.hollingsworth.arsnouveau.api.event.ITimedEvent;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Ждёт удаления (despawn / removal) сущности — подходит для FallingBlockEntity.
 */
public class WatchingEntityDelayEvent implements ITimedEvent {
    private final SpellResolver resolver;
    private final Entity watched;
    private final Level world;
    private boolean expired = false;

    public WatchingEntityDelayEvent(Entity watched, Level world, SpellResolver resolver) {
        this.watched = watched;
        this.world = world;
        this.resolver = resolver;
    }

    @Override
    public void tick(boolean serverSide) {
        if (expired || world == null) return;

        if (serverSide) {
            // Когда сущность удалена — резолвим от имени spellResolver
            if (watched == null || watched.isRemoved()) {
                resolver.resume(world);
                expired = true;
            }
        } else {
            // Клиентская часть: можно добавить частицы, если хочется.
        }
    }

    @Override
    public boolean isExpired() {
        return expired || world == null;
    }
}
