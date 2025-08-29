// File: src/main/java/com/example/arslauria/client/BarrierDomeRenderer.java
package com.example.arslauria.client;

import com.example.arslauria.Lauria;
import com.example.arslauria.effects.BarrierEffect;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Рендер полной полупрозрачной сферы из стеклянных «блоков»
 * вокруг каждой сущности с активным барьером.
 */
@Mod.EventBusSubscriber(
        modid = Lauria.MOD_ID,
        value = Dist.CLIENT,
        bus   = Mod.EventBusSubscriber.Bus.FORGE
)
public class BarrierDomeRenderer {
    // Радиус сферы
    private static final float RADIUS   = 1.5f;
    // Шаг углов: V_STEP по вертикали (φ), H_STEP по горизонтали (θ)
    // Уменьшены для «сетки» примерно 3×3 без больших пустот
    private static final float V_STEP   = 45;  // φ: 0°→180°
    private static final float H_STEP   = 45    ;  // θ: 0°→360°

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        PoseStack ms = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        // смещаем в камеру
        double camX = mc.gameRenderer.getMainCamera().getPosition().x;
        double camY = mc.gameRenderer.getMainCamera().getPosition().y;
        double camZ = mc.gameRenderer.getMainCamera().getPosition().z;
        ms.pushPose();
        ms.translate(-camX, -camY, -camZ);

        // проход по всем сущностям с активным барьер-эффектом
        for (LivingEntity entity : BarrierEffect.getEntities()) {
            double ex = interpolate(entity.xOld, entity.getX(), event.getPartialTick());
            double ey = interpolate(entity.yOld, entity.getY(), event.getPartialTick())
                    + entity.getBbHeight() / 2.0;  // центр по высоте
            double ez = interpolate(entity.zOld, entity.getZ(), event.getPartialTick());

            ms.pushPose();
            ms.translate(ex, ey, ez);

            // φ от 0° (северный полюс) до 180° (южный полюс)
            for (float φ = 0; φ <= 180; φ += V_STEP) {
                double radφ = Math.toRadians(φ);
                double sinφ = Math.sin(radφ);    // радиус кольца
                double cosφ = Math.cos(radφ);    // высота Y

                for (float θ = 0; θ < 360; θ += H_STEP) {
                    double radθ = Math.toRadians(θ);
                    float x = (float)(sinφ * Math.cos(radθ) * RADIUS);
                    float y = (float)(cosφ * RADIUS);
                    float z = (float)(sinφ * Math.sin(radθ) * RADIUS);
                    renderCenteredGlass(ms, buf, x, y, z);
                }
            }

            ms.popPose();
        }

        buf.endBatch(RenderType.translucent());
        ms.popPose();
    }

    /**
     * Рендерит один «блок» стекла так, чтобы он был центрирован по (x,y,z)
     */
    private static void renderCenteredGlass(
            PoseStack ms,
            MultiBufferSource buf,
            float x, float y, float z
    ) {
        BlockState state = Blocks.GLASS.defaultBlockState();
        ms.pushPose();
        // сдвигаем на −0.5 во всех осях, чтобы куб «блок» центрировался
        ms.translate(x - 0.5f, y - 0.5f, z - 0.5f);
        VertexConsumer vc = buf.getBuffer(RenderType.translucent());
        Minecraft.getInstance()
                .getBlockRenderer()
                .renderSingleBlock(
                        state,
                        ms,
                        buf,
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY
                );
        ms.popPose();
    }

    private static double interpolate(double prev, double now, float pt) {
        return prev + (now - prev) * pt;
    }
}
