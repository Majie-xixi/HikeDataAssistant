package dev.hike.dataassistant.gpx

import java.io.FilterInputStream
import java.io.InputStream

/** 读取超过 maxBytes 即抛错的输入流：文件选择器拿不到大小时，用它流式强制上限。 */
class BoundedInputStream(
    input: InputStream,
    private val maxBytes: Long
) : FilterInputStream(input) {

    private var read = 0L

    private fun limitExceeded(): Nothing = throw GpxParseException("文件超过大小上限（$maxBytes 字节）")

    override fun read(): Int {
        if (read >= maxBytes) limitExceeded()
        val b = super.read()
        if (b >= 0) read += 1
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (read >= maxBytes) limitExceeded()
        // 批量读取也受剩余预算约束，不允许一次越过上限
        val allowed = minOf(len.toLong(), maxBytes - read).toInt()
        if (allowed <= 0) limitExceeded()
        val n = super.read(b, off, allowed)
        if (n > 0) read += n
        return n
    }
}
