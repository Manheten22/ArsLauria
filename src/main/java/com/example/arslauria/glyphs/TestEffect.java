package com.example.arslauria.glyphs;

import com.hollingsworth.arsnouveau.api.spell.AbstractAugment;
import com.hollingsworth.arsnouveau.api.spell.AbstractEffect;
import com.hollingsworth.arsnouveau.api.spell.SpellContext;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import com.hollingsworth.arsnouveau.api.spell.SpellStats;
import com.hollingsworth.arsnouveau.common.spell.augment.AugmentAmplify;
import com.hollingsworth.arsnouveau.common.spell.augment.AugmentAccelerate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import javax.annotation.Nonnull;
import java.util.Set;

import static com.example.arslauria.Lauria.prefix;

public class TestEffect extends AbstractEffect {

    public static TestEffect INSTANCE = new TestEffect(prefix("glyph_test"), "Test");

    public TestEffect(ResourceLocation tag, String description) {
        super(tag, description);
    }

    @Override
    public int getDefaultManaCost() {
        return 100;
    }

    @Override
    public void onResolve(HitResult rayTraceResult, Level world, @Nonnull LivingEntity shooter,
                          SpellStats spellStats, SpellContext spellContext, SpellResolver resolver) {
        super.onResolve(rayTraceResult, world, shooter, spellStats, spellContext, resolver);

        // выполняем только на сервере и только если попали по блоку
        if (world.isClientSide) return;
        if (!(rayTraceResult instanceof BlockHitResult)) return;

        if (!(world instanceof ServerLevel serverWorld)) return;

        BlockHitResult bhr = (BlockHitResult) rayTraceResult;
        BlockPos hitPos = bhr.getBlockPos();

        int radius = 1;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos pos = hitPos.offset(dx, 0, dz);
                BlockState state = serverWorld.getBlockState(pos);
                if (state == null) continue;

                // Если блок поддерживает костную муку — применяем
                if (state.getBlock() instanceof BonemealableBlock bonemealable) {
                    try {
                        // performBonemeal требует ServerLevel, Random, BlockPos, BlockState
                        bonemealable.performBonemeal(serverWorld, serverWorld.getRandom(), pos, state);
                    } catch (Exception ignored) { }
                }
                // Запасной путь для классических культур: пшеница/морковь/свёкла и т.п.
                else if (state.getBlock() instanceof CropBlock crop) {
                    try {
                        int current = state.getValue(CropBlock.AGE);
                        int max = crop.getMaxAge();
                        if (current < max) {
                            BlockState newState = state.setValue(CropBlock.AGE, Math.min(max, current + 1));
                            serverWorld.setBlock(pos, newState, 2);
                        }
                    } catch (Exception ignored) { }
                }
                // Пример явной поддержки кустов ягод (если нужно особое поведение)
                else if (state.getBlock() instanceof SweetBerryBushBlock) {
                    try {
                        // SweetBerryBushBlock реализует BonemealableBlock, но на всякий
                        ((BonemealableBlock) state.getBlock()).performBonemeal(serverWorld, serverWorld.getRandom(), pos, state);
                    } catch (Exception ignored) { }
                }
            }
        }

        // необязательный лог
        System.out.println("Accelerated growth around " + hitPos);
    }

    @Nonnull
    @Override
    public Set<AbstractAugment> getCompatibleAugments() {
        // Разрешаем использовать и Amplify, и Accelerate
        return augmentSetOf(AugmentAmplify.INSTANCE, AugmentAccelerate.INSTANCE);
    }
}
