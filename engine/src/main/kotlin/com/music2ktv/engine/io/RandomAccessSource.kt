package com.music2ktv.engine.io

import java.io.ByteArrayOutputStream
import java.io.EOFException

/** 可随机读取的字节源；本地文件、SMB、WebDAV(Range) 都实现它，供标签解析按需读取文件头。 */
interface RandomAccessSource {
    /** 总长度；未知时为 -1。 */
    val length: Long

    /** 从 [position] 读取至多 [length] 字节到 [buffer]，返回实际读取数，到达末尾返回 -1。 */
    fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}

class ByteArraySource(
    private val bytes: ByteArray,
) : RandomAccessSource {
    override val length: Long get() = bytes.size.toLong()

    override fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position >= bytes.size) return -1
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }
}

/**
 * 按块缓存的读取器：远程源每次请求代价高，解析器又需要大量零散小读，
 * 这里把读请求对齐到 [blockSize] 并保留最近 [maxBlocks] 块。
 */
class CachedSource(
    private val source: RandomAccessSource,
    private val blockSize: Int = 64 * 1024,
    private val maxBlocks: Int = 8,
) {
    val length: Long get() = source.length

    private val blocks =
        object : LinkedHashMap<Long, ByteArray>(maxBlocks, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > maxBlocks
        }

    /** 读取恰好 [size] 字节，不足时抛出 [EOFException]。 */
    fun readFully(
        position: Long,
        size: Int,
    ): ByteArray {
        val out = readUpTo(position, size)
        if (out.size < size) throw EOFException("读取越界：position=$position size=$size 实得=${out.size}")
        return out
    }

    /** 读取至多 [size] 字节，到达末尾时返回较短数组。 */
    fun readUpTo(
        position: Long,
        size: Int,
    ): ByteArray {
        require(position >= 0 && size >= 0) { "非法读取范围 position=$position size=$size" }
        // 按实际读到的数据增长，文件头声明的超大长度不会造成大块预分配
        val out = ByteArrayOutputStream(minOf(size, blockSize))
        var done = 0
        while (done < size) {
            val pos = position + done
            val index = pos / blockSize
            val block = block(index)
            val inBlock = (pos - index * blockSize).toInt()
            if (inBlock >= block.size) break
            val n = minOf(size - done, block.size - inBlock)
            out.write(block, inBlock, n)
            done += n
        }
        return out.toByteArray()
    }

    private fun block(index: Long): ByteArray =
        blocks.getOrPut(index) {
            val buf = ByteArray(blockSize)
            var filled = 0
            val start = index * blockSize
            while (filled < blockSize) {
                val n = source.read(start + filled, buf, filled, blockSize - filled)
                if (n <= 0) break
                filled += n
            }
            if (filled == blockSize) buf else buf.copyOf(filled)
        }
}
