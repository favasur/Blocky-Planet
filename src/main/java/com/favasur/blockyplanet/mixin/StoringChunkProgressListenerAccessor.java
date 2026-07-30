package com.favasur.blockyplanet.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for {@link StoringChunkProgressListener#statuses} private field.
 */
@Mixin(StoringChunkProgressListener.class)
public interface StoringChunkProgressListenerAccessor {

    @Accessor("statuses")
    Long2ObjectOpenHashMap<ChunkStatus> getStatuses();
}
