package dev.hike.dataassistant.heartrate

/**
 * BLE Heart Rate Measurement（0x2A37）解析。
 *
 * 帧格式（Bluetooth SIG HRS 1.0）：
 * flags(1B) + Heart Rate Value(1B 或 2B LE) + 可选 Energy Expended(2B LE) + 可选 RR-Interval(s)(2B LE)
 * flags bit0：心率值占 2 字节；bit1：支持接触传感；bit2：检测到接触；
 * bit3：含 Energy Expended；bit4：含 RR-Interval。
 */
object HeartRateMeasurementParser {

    data class ParsedHeartRate(
        val bpm: Int,
        val contactDetected: Boolean?,
        val energyExpendedKJ: Int?,
        val rrIntervals: List<Int>?
    )

    fun parse(data: ByteArray): ParsedHeartRate? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        var offset = 1

        val bpm = if (flags and 0x01 == 0) {
            if (offset >= data.size) return null
            data[offset].toInt() and 0xFF
        } else {
            if (offset + 1 >= data.size) return null
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        }.also { offset += if (flags and 0x01 == 0) 1 else 2 }
        if (bpm <= 0) return null

        val contactSupported = flags and 0x02 != 0
        val contactDetected = if (contactSupported) flags and 0x04 != 0 else null

        var energyExpended: Int? = null
        if (flags and 0x08 != 0) {
            if (offset + 1 >= data.size) return energyFallback(bpm, contactDetected)
            energyExpended = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
        }

        var rrIntervals: MutableList<Int>? = null
        if (flags and 0x10 != 0) {
            val list = mutableListOf<Int>()
            while (offset + 1 < data.size) {
                list.add((data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8))
                offset += 2
            }
            rrIntervals = list
        }

        return ParsedHeartRate(bpm, contactDetected, energyExpended, rrIntervals)
    }

    /** energy 字段声明了但字节不足：保留已可靠解析的心率，丢弃其余。 */
    private fun energyFallback(bpm: Int, contact: Boolean?) =
        ParsedHeartRate(bpm, contact, null, null)
}
