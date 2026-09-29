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
        ensureBudget(1)
        val b = super.read()
        if (b >= 0) read += 1
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        ensureBudget(1)
        val n = super.read(b, off, len)
        if (n > 0) read += n
        return n
    }

    private fun ensureBudget(next: Int) {
        if (read + next > maxBytes) {
            throw GpxParseException("文件超过 ${maxBytes / (1024 * 1024)} MB 上限")
        }
    }
}
