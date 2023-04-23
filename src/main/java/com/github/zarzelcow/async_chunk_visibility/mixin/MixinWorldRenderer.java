package com.github.zarzelcow.async_chunk_visibility.mixin;

import com.github.zarzelcow.async_chunk_visibility.TaskChunkVisibility;
import com.google.common.collect.Lists;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.render.world.AbstractChunkRenderManager;
import net.minecraft.client.util.math.Vector3d;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(WorldRenderer.class)
public abstract class MixinWorldRenderer {

    @Shadow
    @Final
    private MinecraftClient client;
    @Shadow
    private int renderDistance;

    @Shadow
    public abstract void reload();

    @Shadow
    private ClientWorld world;
    @Shadow
    private double lastCameraChunkUpdateX;
    @Shadow
    private double lastCameraChunkUpdateY;
    @Shadow
    private double lastCameraChunkUpdateZ;
    @Shadow
    private int cameraChunkX;
    @Shadow
    private int cameraChunkY;
    @Shadow
    private int cameraChunkZ;
    @Shadow
    private BuiltChunkStorage chunks;
    @Shadow
    private AbstractChunkRenderManager chunkRenderManager;
    @Shadow
    private BaseFrustum capturedFrustum;
    @Shadow
    @Final
    private Vector3d capturedFrustumPosition;
    @Shadow
    private boolean needsTerrainUpdate;
    @Shadow
    private Set<BuiltChunk> chunksToRebuild = ConcurrentHashMap.newKeySet(); // Use a concurrent set instead to solve the issue of concurrent modification
    @Shadow
    private double lastCameraX;
    @Shadow
    private double lastCameraY;
    @Shadow
    private double lastCameraZ;
    @Shadow
    private double lastCameraPitch;
    @Shadow
    private double lastCameraYaw;
    @Shadow
    private List<WorldRenderer.ChunkInfo> visibleChunks;

    @Shadow
    private boolean field_10813;

    @Shadow
    protected abstract void captureFrustum(double x, double y, double z);

    @Shadow
    @Final
    private ChunkBuilder chunkBuilder;

    @Shadow
    protected abstract boolean isInChunk(BlockPos pos, BuiltChunk chunk);

    private List<WorldRenderer.ChunkInfo> writeBuffer = Lists.newArrayListWithCapacity(69696);
    private static final TaskChunkVisibility taskChunkVisibility = new TaskChunkVisibility();

    /**
     * @author Zarzelcow
     * @reason replace with an async breadth search
     */
    @Overwrite
    public void setupTerrain(Entity entity, double tickDelta, CameraView cameraView, int frame, boolean spectator) {
        if (this.client.options.viewDistance != this.renderDistance) {
            this.reload();
        }
        if (taskChunkVisibility.get()) {
            this.client.profiler.push("swapBuffers");
            swapVisibleBuffers();
            this.client.profiler.pop();
        }
        WorldRenderer self = (WorldRenderer) (Object) this;
        double g = entity.prevTickX + (entity.x - entity.prevTickX) * tickDelta;
        double h = entity.prevTickY + (entity.y - entity.prevTickY) * tickDelta;
        double i = entity.prevTickZ + (entity.z - entity.prevTickZ) * tickDelta;
        {
            this.world.profiler.push("camera");
            double d = entity.x - this.lastCameraChunkUpdateX;
            double e = entity.y - this.lastCameraChunkUpdateY;
            double f = entity.z - this.lastCameraChunkUpdateZ;
            if (this.cameraChunkX != entity.chunkX || this.cameraChunkY != entity.chunkY || this.cameraChunkZ != entity.chunkZ || d * d + e * e + f * f > 16.0) {
                this.lastCameraChunkUpdateX = entity.x;
                this.lastCameraChunkUpdateY = entity.y;
                this.lastCameraChunkUpdateZ = entity.z;
                this.cameraChunkX = entity.chunkX;
                this.cameraChunkY = entity.chunkY;
                this.cameraChunkZ = entity.chunkZ;
                this.chunks.updateCameraPosition(entity.x, entity.z);
            }

            this.world.profiler.swap("renderlistcamera");
            this.chunkRenderManager.setViewPos(g, h, i);
            this.world.profiler.swap("cull");
            if (this.capturedFrustum != null) {
                CullingCameraView cullingCameraView = new CullingCameraView(this.capturedFrustum);
                cullingCameraView.setPos(this.capturedFrustumPosition.x, this.capturedFrustumPosition.y, this.capturedFrustumPosition.z);
                cameraView = cullingCameraView;
            }

            this.client.profiler.swap("culling");

            this.needsTerrainUpdate = this.needsTerrainUpdate || !this.chunksToRebuild.isEmpty() || entity.x != this.lastCameraX || entity.y != this.lastCameraY || entity.z != this.lastCameraZ || (double) entity.pitch != this.lastCameraPitch || (double) entity.yaw != this.lastCameraYaw;
            this.lastCameraX = entity.x;
            this.lastCameraY = entity.y;
            this.lastCameraZ = entity.z;
            this.lastCameraPitch = entity.pitch;
            this.lastCameraYaw = entity.yaw;
            this.client.profiler.pop();
        }
        boolean bl = this.capturedFrustum != null;
        if (!bl && this.needsTerrainUpdate) {
            this.client.profiler.push("updateTerrain");
            this.needsTerrainUpdate = false;
            BlockPos blockPos = new BlockPos(g, h + (double) entity.getEyeHeight(), i);

            taskChunkVisibility.run(self, blockPos, this.chunks, cameraView, renderDistance, writeBuffer, this::enqueueVisibleChunk);
            this.client.profiler.pop();
        }

        if (this.field_10813) {
            this.captureFrustum(g, h, i);
            this.field_10813 = false;
        }

        this.client.profiler.push("flush");
        this.chunkBuilder.clear(); // looks more like a "flush" to me instead of a "clear"
        this.client.profiler.pop();
    }

    public void enqueueVisibleChunk(BuiltChunk chunk) {
        if (!chunk.method_10173() && !this.chunksToRebuild.contains(chunk)) return;
        this.needsTerrainUpdate = true;
//        if (this.isInChunk(, chunk)) {
//            this.client.profiler.push("build near");
//            this.chunkBuilder.upload(chunk);
//            chunk.method_10162(false);
//            this.client.profiler.pop();
//            return;
//        }
        this.chunksToRebuild.add(chunk);
    }

    private void swapVisibleBuffers() {
        List<WorldRenderer.ChunkInfo> temp = visibleChunks;
        visibleChunks = writeBuffer;
        writeBuffer = temp;
        writeBuffer.clear();
    }
}
