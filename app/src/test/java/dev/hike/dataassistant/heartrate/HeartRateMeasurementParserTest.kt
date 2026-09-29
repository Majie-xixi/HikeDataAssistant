package dev.hike.dataassistant.heartrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0x2A37 Heart Rate Measurement 解析测试。
 * 帧格式：flags(1B) + bpm(1B/2B LE) + 可选 energy(2B) + 可选 RR 区间。
 * flags bit0=16位 bpm；bit1=支持接触传感；bit2=检测到接触；bit3=含 energy；bit4=含 RR。
 */
class HeartRateMeasurementParserTest {

    private fun parse(vararg bytes: Int) =
        HeartRateMeasurementParser.parse(bytes.map { it.toByte() }.toByteArray())

    @Test
    fun `8-bit bpm packet`() {
        val parsed = parse(0x00, 0x46)
        assertEquals(70, parsed?.bpm)
    }

    @Test
    fun `16-bit little-endian bpm packet`() {
        val parsed = parse(0x01, 0x64, 0x00)
        assertEquals(100, parsed?.bpm)
        // 0xC8 0x01 LE = 456 -> 超出生理范围也不在此层过滤，仅解析
        assertEquals(456, parse(0x01, 0xC8, 0x01)?.bpm)
    }

    @Test
    fun `contact status flags`() {
        // bit1+bit2：支持且检测到接触
        assertEquals(true, parse(0x06, 0x5A)?.contactDetected)
        // 仅 bit1：支持但未接触
        assertEquals(false, parse(0x02, 0x5A)?.contactDetected)
        // 未声明接触传感
        assertNull(parse(0x00, 0x5A)?.contactDetected)
    }

    @Test
    fun `energy expended field is skipped`() {
        val parsed = parse(0x08, 0x46, 0xC8, 0x00)
        assertEquals(70, parsed?.bpm)
        assertEquals(200, parsed?.energyExpendedKJ)
    }

    @Test
    fun `rr intervals are skipped`() {
        val parsed = parse(0x10, 0x46, 0x30, 0x01, 0x28, 0x01)
        assertEquals(70, parsed?.bpm)
        assertEquals(listOf(304, 296), parsed?.rrIntervals)
    }

    @Test
    fun `too short packets return null`() {
        assertNull(parse())
        assertNull(parse(0x00))
        // 声明 16 位 bpm 但缺少字节
        assertNull(parse(0x01, 0x64))
    }

    @Test
    fun `reserved high bits in flags do not crash parsing`() {
        // 高位保留位被置位时仍按已知字段解析
        val parsed = parse(0xE0, 0x46)
        assertEquals(70, parsed?.bpm)
    }
}
