package com.github.zarzelcow.async_chunk_visibility;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.CameraView;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class TaskChunkVisibility {
    private CompletableFuture<Void> currentTask = null;


    public void run(WorldRenderer self, BlockPos playerPos, BuiltChunkStorage chunks, CameraView cameraView, int renderDistance, List<WorldRenderer.ChunkInfo> visibleChunks, Consumer<BuiltChunk> consumer) {
        if (currentTask == null) {
            currentTask = CompletableFuture.runAsync(() -> {
                IntArrayList results = new IntArrayList();
                bfs(playerPos, chunks, cameraView, results, renderDistance);
                results.forEach(visible -> {
                    BuiltChunk chunk = chunks.chunks[visible];
                    visibleChunks.add(self.new ChunkInfo(chunk, null, 0));
                    consumer.accept(chunk);
                });
            });
        }
    }

    public boolean get() {
        if (currentTask != null && currentTask.isDone()) {
            currentTask = null;
            return true;
        }
        return false;
    }

    public void bfs(BlockPos playerPos, BuiltChunkStorage chunks, CameraView cameraView, IntList results, int renderDistance) {
        BitSet visited = new BitSet(chunks.chunks.length);
        // breadth first search for chunks
        ObjectArrayFIFOQueue<ChunkInfo> queue = new ObjectArrayFIFOQueue<ChunkInfo>(1024); // queue stores the indexs of the chunks
        // add the chunk the player is in
        int playerIndex = getRenderedChunk(playerPos, chunks.sizeX, chunks.sizeY, chunks.sizeZ);
        visited.set(playerIndex);
        results.add(playerIndex);
        queue.enqueue(new ChunkInfo(chunks.chunks[playerIndex], null, playerPos));
        while (!queue.isEmpty()) {
            ChunkInfo v = queue.dequeue();
            // get adjacent chunks
            for (Direction direction : Direction.values()) {
                int adjacent = getAdjacentChunk(playerPos, v.chunk.getPos(), direction, renderDistance, chunks.sizeX, chunks.sizeY, chunks.sizeZ);
                if (adjacent == -1) continue;
                BuiltChunk chunk = chunks.chunks[adjacent];
                if (!visited.get(adjacent) && (v.direction == null || v.chunk.method_10170().isVisibleThrough(v.direction.getOpposite(), direction)) && cameraView.isBoxInFrustum(chunk.field_11071)) {
                    visited.set(adjacent);
                    results.add(adjacent);
                    queue.enqueue(new ChunkInfo(chunk, direction, v.blockPos.offset(direction, 16)));
                }

            }
        }
    }

    public int getRenderedChunk(BlockPos pos, int sizeX, int sizeY, int sizeZ) {
        int x = MathHelper.floorDiv(pos.getX(), 16);
        int y = MathHelper.floorDiv(pos.getY(), 16);
        int z = MathHelper.floorDiv(pos.getZ(), 16);
        if (y >= 0 && y < sizeY) {
            x %= sizeX;
            if (x < 0) {
                x += sizeX;
            }

            z %= sizeZ;
            if (z < 0) {
                z += sizeZ;
            }

            return (z * sizeY + y) * sizeX + x;
        } else {
            return -1;
        }
    }

    private int getAdjacentChunk(BlockPos playerPos, BlockPos chunkPos, Direction direction, int renderDistance, int sizeX, int sizeY, int sizeZ) {
        BlockPos offset = chunkPos.offset(direction, 16);
        int blockRenderDistance = renderDistance * 16;

        int deltaX = Math.abs(playerPos.getX() - offset.getX());
        int deltaZ = Math.abs(playerPos.getZ() - offset.getZ());

        if (deltaX > blockRenderDistance
                || deltaZ > blockRenderDistance
                || !(offset.getY() >= 0 && offset.getY() < 256)) {
            return -1;
        }
        return getRenderedChunk(offset, sizeX, sizeY, sizeZ);
    }

    private static class ChunkInfo {
        public BuiltChunk chunk;
        public Direction direction;
        public BlockPos blockPos;

        public ChunkInfo(BuiltChunk chunk, Direction direction, BlockPos blockPos) {
            this.chunk = chunk;
            this.direction = direction;
            this.blockPos = blockPos;
        }
    }
}
