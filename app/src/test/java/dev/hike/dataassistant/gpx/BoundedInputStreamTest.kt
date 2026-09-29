package dev.hike.dataassistant.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class BoundedInputStreamTest {

    @Test
    fun `single byte reads stop exactly at the limit`() {
        val input = BoundedInputStream(ByteArrayInputStream(ByteArray(11)), maxBytes = 10)
        repeat(10) { assertEquals(0, input.read()) }
        try {
            input.read()
            fail("第 11 字节应当抛错")
        } catch (expected: GpxParseException) {
        }
    }

    @Test
    fun `bulk read cannot jump past the limit`() {
        val input = BoundedInputStream(ByteArrayInputStream(ByteArray(11)), maxBytes = 10)
        val buffer = ByteArray(16)
        // 一次请求读 11 字节：只允许读到上限内的 10 字节
        val n = input.read(buffer, 0, 11)
        assertEquals(10, n)
        // 下一字节触发上限
        try {
            input.read(buffer, 0, 1)
            fail("超过上限后应当抛错")
        } catch (expected: GpxParseException) {
        }
    }

    @Test
    fun `bulk read larger than limit reads at most the limit`() {
        val input = BoundedInputStream(ByteArrayInputStream(ByteArray(50)), maxBytes = 10)
        val buffer = ByteArray(50)
        assertEquals(10, input.read(buffer, 0, 50))
        try {
            input.read(buffer, 0, 50)
            fail("超过上限后应当抛错")
        } catch (expected: GpxParseException) {
        }
    }

    @Test
    fun `under the limit reads normally`() {
        val data = "hello gpx".toByteArray()
        val input = BoundedInputStream(ByteArrayInputStream(data), maxBytes = 1_000)
        val buffer = ByteArray(data.size)
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            assertTrue(n > 0)
            off += n
        }
        assertEquals("hello gpx", String(buffer))
    }
}
