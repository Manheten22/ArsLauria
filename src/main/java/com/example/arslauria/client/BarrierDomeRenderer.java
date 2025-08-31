package com.example.arslauria.client;

import com.example.arslauria.Lauria;
import com.example.arslauria.setup.ModEffects;
import com.mojang.blaze3d.vertex.PoseStack;
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

import java.util.HashSet;
import java.util.Set;

/**
 * Hollow cube renderer implemented by drawing six thin slabs (faces) using renderSingleBlock.
 * This approach avoids low-level vertex/matrix mismatches and is compatible with block rendering pipeline.
 */
@Mod.EventBusSubscriber(
        modid = Lauria.MOD_ID,
        value = Dist.CLIENT,
        bus   = Mod.EventBusSubscriber.Bus.FORGE
)
public class BarrierDomeRenderer {
    private static volatile boolean RENDER_ENABLED = true;
    public static void setRenderEnabled(boolean enabled) { RENDER_ENABLED = enabled; }
    public static boolean isRenderEnabled() { return RENDER_ENABLED; };

    // ---------- ПАРАМЕТРЫ ----------
    private static final boolean UNIFORM_CUBE = true;
    private static final float SCALE_MULTIPLIER = 1.3f;
    private static final float ADD_PADDING = 0.0f;
    private static final float OFFSET_Y = 0.0f;
    private static final float MIN_DIM = 0.3f;
    private static final float MAX_DIM = 6.0f;
    private static final double MAX_RENDER_DISTANCE = 64.0;
    // Толщина граней в unit-cube (0..1). 0.02 ≈ 1/50 блока — тонкая грань.
    private static final float THICKNESS = 0.02f;
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Long> LAST_PARTICLE = new java.util.concurrent.ConcurrentHashMap<>();

    // --------------------------------

    private static void spawnCrackParticles(LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        double cx = entity.getX();
        double cy = entity.getY() + entity.getBbHeight() * 0.5;
        double cz = entity.getZ();
        // несколько частиц случайно по кубу
        for (int i = 0; i < 3; i++) {
            double rx = (Math.random() - 0.5) * entity.getBbWidth();
            double ry = (Math.random() - 0.5) * entity.getBbHeight();
            double rz = (Math.random() - 0.5) * entity.getBbWidth();
            mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.DAMAGE_INDICATOR,
                    cx + rx, cy + ry, cz + rz,
                    0.0, 0.0, 0.0);
        }
    }


    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!RENDER_ENABLED) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        PoseStack ms = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        double camX = mc.gameRenderer.getMainCamera().getPosition().x;
        double camY = mc.gameRenderer.getMainCamera().getPosition().y;
        double camZ = mc.gameRenderer.getMainCamera().getPosition().z;
        ms.pushPose();
        ms.translate(-camX, -camY, -camZ);

        double maxDistSq = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;
        Set<Integer> renderedIds = new HashSet<>();

        // --- 1) Обычный перебор сущностей рядом ---
        for (LivingEntity entity : mc.level.getEntitiesOfClass(LivingEntity.class, mc.player.getBoundingBox().inflate(MAX_RENDER_DISTANCE))) {
            if (entity == null) continue;
            if (!entity.isAlive()) continue;

            boolean hasEffect = entity.hasEffect(ModEffects.BARRIER.get());
            boolean inClientMap = ClientBarrierData.contains(entity.getId());

            if (!hasEffect && !inClientMap) continue;

            double dx = entity.getX() - camX;
            double dy = (entity.getY() + entity.getBbHeight() * 0.5) - camY;
            double dz = entity.getZ() - camZ;
            if (dx*dx + dy*dy + dz*dz > maxDistSq) continue;

            renderHollowForEntity(entity, ms, buf, event.getPartialTick());
            renderedIds.add(entity.getId());
        }

        // --- 2) Fallback: рендер по ClientBarrierData (если есть id'ы, которых не встретили) ---
        for (Integer id : ClientBarrierData.getEntities()) {
            if (renderedIds.contains(id)) continue; // уже отрендерено

            var maybeEntity = mc.level.getEntity(id);
            if (!(maybeEntity instanceof LivingEntity)) continue;
            LivingEntity entity = (LivingEntity) maybeEntity;

            double dx = entity.getX() - camX;
            double dy = (entity.getY() + entity.getBbHeight() * 0.5) - camY;
            double dz = entity.getZ() - camZ;
            if (dx*dx + dy*dy + dz*dz > maxDistSq) continue;

            renderHollowForEntity(entity, ms, buf, event.getPartialTick());
            renderedIds.add(entity.getId());
        }

        buf.endBatch(RenderType.translucent());
        ms.popPose();
    }

    private static void renderHollowForEntity(LivingEntity entity, PoseStack ms, MultiBufferSource.BufferSource buf, float partialTick) {
        // интерполяция позиции и центр по высоте
        double ex = interpolate(entity.xOld, entity.getX(), partialTick);
        double eyFeet = interpolate(entity.yOld, entity.getY(), partialTick);
        double eyCenter = eyFeet + entity.getBbHeight() / 2.0 + OFFSET_Y;
        double ez = interpolate(entity.zOld, entity.getZ(), partialTick);

        float baseW = (float) entity.getBbWidth();
        float baseH = (float) entity.getBbHeight();

        // размер куба (unit cube позже масштабируется)
        float targetX, targetY, targetZ;
        if (UNIFORM_CUBE) {
            float base = Math.max(baseW, baseH);
            float dim = base * SCALE_MULTIPLIER + ADD_PADDING;
            dim = clamp(dim, MIN_DIM, MAX_DIM);
            targetX = targetY = targetZ = dim;
        } else {
            targetX = clamp(baseW * SCALE_MULTIPLIER + ADD_PADDING, MIN_DIM, MAX_DIM);
            targetZ = clamp(baseW * SCALE_MULTIPLIER + ADD_PADDING, MIN_DIM, MAX_DIM);
            targetY = clamp(baseH * SCALE_MULTIPLIER + ADD_PADDING, MIN_DIM, MAX_DIM);
        }

        // нижний-левый-задний угол итогового куба
        double minX = ex - targetX / 2.0;
        double minY = eyCenter - targetY / 2.0;
        double minZ = ez - targetZ / 2.0;

        // По сути мы будем масштабировать unit-cube (0..1) в размер targetX/Y/Z,
        // и внутри этого пространства рисовать шесть тонких плит (slabs).
        ms.pushPose();

        // переносим в min
        ms.translate(minX, minY, minZ);
        // масштабируем unit-cube в target размеры
        ms.scale(targetX, targetY, targetZ);

        boolean cracking = com.example.arslauria.client.ClientBarrierData.isCracking(entity.getId());
        if (cracking) {
            // пульсация (маленькая): делаем scale относительно центра unit-cube
            float jitter = 1.0f + (float)Math.sin(System.currentTimeMillis() / 120.0) * 0.02f; // ~2% масштаб
            // центр unit-cube = (0.5,0.5,0.5)
            ms.translate(0.5, 0.5, 0.5);
            ms.scale(jitter, jitter, jitter);
            ms.translate(-0.5, -0.5, -0.5);

            // спавн частиц с тайротлом (чтобы не спамить) — используем статическую карту
            long now = System.currentTimeMillis();
            Long last = LAST_PARTICLE.putIfAbsent(entity.getId(), now);
            if (last == null) {
                // только добавили — spawn сразу
                spawnCrackParticles(entity);
                LAST_PARTICLE.put(entity.getId(), now);
            } else {
                if (now - last >= 200) { // каждые ~200ms
                    spawnCrackParticles(entity);
                    LAST_PARTICLE.put(entity.getId(), now);
                }
            }
        }

        BlockState state = Blocks.WHITE_STAINED_GLASS.defaultBlockState();

        // Толщина в локальных unit-координатах
        float t = THICKNESS;

        // FRONT (z = 0 .. t)
        ms.pushPose();
        ms.translate(0.0, 0.0, 0.0); // в unit coords
        ms.scale(1.0f, 1.0f, t);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, ms, buf, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        ms.popPose();

        // BACK (z = 1-t .. 1) -> translate z = 1 - t, scale z = t
        ms.pushPose();
        ms.translate(0.0, 0.0, 1.0 - t);
        ms.scale(1.0f, 1.0f, t);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, ms, buf, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        ms.popPose();

        // LEFT (x = 0 .. t)
        ms.pushPose();
        ms.translate(0.0, 0.0, 0.0);
        ms.scale(t, 1.0f, 1.0f);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, ms, buf, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        ms.popPose();

        // RIGHT (x = 1-t .. 1)
        ms.pushPose();
        ms.translate(1.0 - t, 0.0, 0.0);
        ms.scale(t, 1.0f, 1.0f);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, ms, buf, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        ms.popPose();

        // BOTTOM (y = 0 .. t)
        ms.pushPose();
        ms.translate(0.0, 0.0, 0.0);
        ms.scale(1.0f, t, 1.0f);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, ms, buf, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        ms.popPose();

        // TOP (y = 1-t .. 1)
        ms.pushPose();
        ms.translate(0.0, 1.0 - t, 0.0);
        ms.scale(1.0f, t, 1.0f);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, ms, buf, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        ms.popPose();

        ms.popPose();
    }


    private static double interpolate(double prev, double now, float pt) {
        return prev + (now - prev) * pt;
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
