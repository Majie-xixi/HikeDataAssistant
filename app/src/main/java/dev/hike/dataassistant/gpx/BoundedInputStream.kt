package dev.hike.dataassistant.gpx

import java.io.FilterInputStream
import java.io.InputStream

/** 读取超过 maxBytes 即抛错的输入流：文件选择器拿不到大小时，用它流式强制上限。 */
class BoundedInputStream(
    input: InputStream,
    private val maxBytes: Long
) : FilterInputStream(input) {

    private var read = 0L

    override fun read(): Int {
        if (read >= maxBytes) {
            // 已读满预算：底层已到 EOF 属于"恰好等于上限"的合法文件；
            // 真实存在第 maxBytes+1 字节才算超限
            val probe = super.read()
            if (probe == -1) return -1
            limitExceeded()
        }
        val b = super.read()
        if (b >= 0) read += 1
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (read >= maxBytes) {
            val probe = super.read()
            if (probe == -1) return -1
            limitExceeded()
        }
        // 批量读取受剩余预算约束，不允许一次越过上限
        val allowed = minOf(len.toLong(), maxBytes - read).toInt()
        val n = super.read(b, off, allowed)
        if (n > 0) read += n
        return n
    }

    private fun limitExceeded(): Nothing = throw GpxParseException("文件超过大小上限（$maxBytes 字节）")
}
