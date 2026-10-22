package com.example.supertonic.audio

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Metadata for a stored PCM audio chunk in off-heap DirectByteBuffer storage
 */
data class PcmChunkMeta(
    val chunkIndex: Int,
    val blockId: Int,
    val offset: Int,
    val length: Int,
    val durationSec: Float,
    val sampleRate: Int,
    var lastAccessTime: Long = System.currentTimeMillis()
)

/**
 * Off-heap PCM chunk store using 4 MB DirectByteBuffers with LRU eviction and memory bounds.
 */
class PcmStore(
    maxCapacityBytes: Long = DEFAULT_MAX_CAPACITY_BYTES
) {
    companion object {
        const val BLOCK_SIZE: Int = 4 * 1024 * 1024 // 4 MB per block
        const val DEFAULT_MAX_CAPACITY_BYTES: Long = 128L * 1024 * 1024 // 128 MB default
        const val MIN_CAPACITY_BYTES: Long = 64L * 1024 * 1024 // 64 MB
        const val MAX_CAPACITY_BYTES_UPPER: Long = 256L * 1024 * 1024 // 256 MB
    }

    /**
     * Memory budget, always clamped to [MIN_CAPACITY_BYTES]..[MAX_CAPACITY_BYTES_UPPER] so a bad
     * preference can neither starve the playback buffer nor blow the process heap.
     */
    var maxCapacityBytes: Long = maxCapacityBytes.coerceIn(MIN_CAPACITY_BYTES, MAX_CAPACITY_BYTES_UPPER)
        set(value) {
            field = value.coerceIn(MIN_CAPACITY_BYTES, MAX_CAPACITY_BYTES_UPPER)
        }

    private class Block(
        val id: Int,
        val buffer: ByteBuffer,
        var writePos: Int = 0,
        var activeChunkCount: Int = 0
    )

    private val lock = Any()
    private var nextBlockId = 0
    private val blocks = mutableListOf<Block>()
    private val chunkMap = ConcurrentHashMap<Int, PcmChunkMeta>()

    private val _hitCount = AtomicLong(0)
    private val _missCount = AtomicLong(0)

    val hitCount: Long get() = _hitCount.get()
    val missCount: Long get() = _missCount.get()

    val blockCount: Int
        get() = synchronized(lock) { blocks.size }

    val allocatedBytes: Long
        get() = synchronized(lock) { blocks.size.toLong() * BLOCK_SIZE }

    val usedBytes: Long
        get() = synchronized(lock) { chunkMap.values.sumOf { it.length.toLong() } }

    fun has(chunkIndex: Int): Boolean = chunkMap.containsKey(chunkIndex)

    fun sizeOf(chunkIndex: Int): Int? = chunkMap[chunkIndex]?.length

    fun get(chunkIndex: Int): ByteArray? {
        val size = sizeOf(chunkIndex)
        if (size == null) {
            _missCount.incrementAndGet()
            return null
        }
        val outBytes = ByteArray(size)
        val copied = copyInto(chunkIndex, outBytes, 0)
        return if (copied == size) outBytes else null
    }

    /**
     * Copies a stored chunk into [dst] without allocating a new array (hot path of the playback loop).
     *
     * @return number of bytes copied, or -1 when the chunk is missing or [dst] is too small.
     */
    fun copyInto(chunkIndex: Int, dst: ByteArray, dstOffset: Int = 0): Int {
        val meta = chunkMap[chunkIndex]
        if (meta == null) {
            _missCount.incrementAndGet()
            return -1
        }
        if (dstOffset < 0 || dstOffset + meta.length > dst.size) {
            return -1
        }

        synchronized(lock) {
            meta.lastAccessTime = System.currentTimeMillis()
            val block = blocks.find { it.id == meta.blockId }
            if (block == null) {
                chunkMap.remove(chunkIndex)
                _missCount.incrementAndGet()
                return -1
            }

            val readBuf = block.buffer.duplicate()
            readBuf.position(meta.offset)
            readBuf.get(dst, dstOffset, meta.length)
            _hitCount.incrementAndGet()
            return meta.length
        }
    }

    fun put(chunkIndex: Int, pcm: ByteArray, durationSec: Float, sampleRate: Int = 44100) {
        if (pcm.isEmpty()) return
        if (pcm.size > BLOCK_SIZE) {
            // A single chunk is never that long (≈45 s of 44.1 kHz mono PCM); refusing beats a
            // BufferOverflowException in the middle of a write.
            android.util.Log.e("PcmStore", "Chunk $chunkIndex is ${pcm.size} bytes, larger than block $BLOCK_SIZE")
            return
        }

        synchronized(lock) {
            // If already present, remove old reference
            chunkMap.remove(chunkIndex)?.let { oldMeta ->
                blocks.find { it.id == oldMeta.blockId }?.let { b ->
                    b.activeChunkCount = max(0, b.activeChunkCount - 1)
                }
            }

            val needed = pcm.size

            // Find existing block with enough remaining space
            var targetBlock = blocks.find { BLOCK_SIZE - it.writePos >= needed }

            if (targetBlock == null) {
                val maxAllowedBlocks = (maxCapacityBytes / BLOCK_SIZE).toInt().coerceAtLeast(1)
                if (blocks.size >= maxAllowedBlocks) {
                    // Evict oldest block
                    evictOldestBlock()
                }

                // If still cannot fit, allocate new block
                if (blocks.size < maxAllowedBlocks) {
                    val directBuf = ByteBuffer.allocateDirect(BLOCK_SIZE)
                    targetBlock = Block(id = nextBlockId++, buffer = directBuf)
                    blocks.add(targetBlock)
                } else {
                    // Reuse block with lowest active count
                    val emptiest = blocks.minByOrNull { it.activeChunkCount }
                    if (emptiest != null) {
                        evictChunksInBlock(emptiest.id)
                        emptiest.writePos = 0
                        emptiest.activeChunkCount = 0
                        targetBlock = emptiest
                    }
                }
            }

            val block = targetBlock ?: return
            val offset = block.writePos
            val writeBuf = block.buffer.duplicate()
            writeBuf.position(offset)
            writeBuf.put(pcm)
            block.writePos += needed
            block.activeChunkCount++

            val meta = PcmChunkMeta(
                chunkIndex = chunkIndex,
                blockId = block.id,
                offset = offset,
                length = needed,
                durationSec = durationSec,
                sampleRate = sampleRate,
                lastAccessTime = System.currentTimeMillis()
            )
            chunkMap[chunkIndex] = meta
        }
    }

    fun evictExcept(retainedIndices: Set<Int>) {
        synchronized(lock) {
            val toRemove = chunkMap.keys.filter { it !in retainedIndices }
            for (idx in toRemove) {
                chunkMap.remove(idx)?.let { meta ->
                    blocks.find { it.id == meta.blockId }?.let { b ->
                        b.activeChunkCount = max(0, b.activeChunkCount - 1)
                    }
                }
            }

            // Compact / release blocks with 0 active chunks
            val emptyBlocks = blocks.filter { it.activeChunkCount == 0 }
            if (emptyBlocks.size > 1) {
                // Keep at least 1 empty block ready, release others
                for (b in emptyBlocks.drop(1)) {
                    blocks.remove(b)
                }
                emptyBlocks.firstOrNull()?.writePos = 0
            }
        }
    }

    private fun evictOldestBlock() {
        val oldestChunk = chunkMap.values.minByOrNull { it.lastAccessTime } ?: return
        val blockToEvict = blocks.find { it.id == oldestChunk.blockId } ?: return
        evictChunksInBlock(blockToEvict.id)
        blocks.remove(blockToEvict)
    }

    private fun evictChunksInBlock(blockId: Int) {
        val inBlock = chunkMap.filterValues { it.blockId == blockId }.keys
        for (idx in inBlock) {
            chunkMap.remove(idx)
        }
    }

    fun clear() {
        synchronized(lock) {
            chunkMap.clear()
            blocks.clear()
            _hitCount.set(0)
            _missCount.set(0)
        }
    }
}
