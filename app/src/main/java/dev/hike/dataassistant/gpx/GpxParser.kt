package dev.hike.dataassistant.gpx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream

data class GpxPoint(val lat: Double, val lon: Double, val eleMeters: Double?)

/** 一个连续段（来自单个 trkseg 或整个 rte）。多段之间不拼接。 */
data class GpxTrackSegment(val name: String?, val points: List<GpxPoint>)

data class ParsedGpx(val trackName: String?, val segments: List<GpxTrackSegment>)

class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * GPX 解析：支持 trk/trkseg/trkpt 与 rte/rtept 兜底。
 *
 * 安全约束：拒绝 DTD 与实体引用，不解析任何外部内容；
 * 坐标非法或结构损坏时抛 GpxParseException，绝不静默吞错。
 * 解析器通过构造注入，便于 JVM 单元测试使用 kxml2。
 */
class GpxParser(private val parser: XmlPullParser) {

    constructor() : this(Xml.newPullParser())

    companion object {
        private const val MAX_POINTS = 100_000
    }

    fun parse(input: InputStream): ParsedGpx {
        try {
            try {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            } catch (_: XmlPullParserException) {
                // 部分实现只允许在工厂层设置；忽略
            }
            parser.setInput(input, null)
            return readGpx()
        } catch (e: XmlPullParserException) {
            throw GpxParseException("GPX 结构损坏或包含不允许的实体：${e.message}", e)
        } catch (e: IOException) {
            throw GpxParseException("读取 GPX 失败：${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw GpxParseException("GPX 内容非法：${e.message}", e)
        }
    }

    private fun readGpx(): ParsedGpx {
        val segments = mutableListOf<GpxTrackSegment>()
        val openTags = ArrayDeque<String>()
        var sawGpx = false
        var topLevelName: String? = null
        var metadataName: String? = null
        var trkName: String? = null
        var rteName: String? = null
        var segmentPoints: MutableList<GpxPoint>? = null // trkseg 或 rte 的活动点集
        var rtePoints: MutableList<GpxPoint>? = null
        var text = StringBuilder()
        var pendingEle: Double? = null
        var pendingLat: Double? = null
        var pendingLon: Double? = null
        var inPoint = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF ->
                    throw GpxParseException("GPX 不允许 DTD 或实体引用")

                XmlPullParser.START_TAG -> {
                    openTags.addLast(parser.name)
                    when (parser.name) {
                        "gpx" -> sawGpx = true
                        "trk" -> trkName = null
                        "rte" -> { rteName = null; rtePoints = null }
                        "trkseg" -> segmentPoints = mutableListOf()
                        "trkpt", "rtept" -> {
                            inPoint = true
                            pendingEle = null
                            pendingLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                ?: throw IllegalArgumentException("${parser.name} 缺少合法 lat 属性")
                            pendingLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                                ?: throw IllegalArgumentException("${parser.name} 缺少合法 lon 属性")
                            if (pendingLat !in -90.0..90.0 || pendingLon !in -180.0..180.0) {
                                throw IllegalArgumentException("坐标超出范围：$pendingLat,$pendingLon")
                            }
                        }
                    }
                    text = StringBuilder()
                }

                XmlPullParser.TEXT -> text.append(parser.text)

                XmlPullParser.END_TAG -> {
                    val closed = openTags.removeLastOrNull()
                    val parent = openTags.lastOrNull()
                    when (parser.name) {
                        "name" -> when (parent) {
                            "gpx" -> if (topLevelName == null) topLevelName = text.toString().trim().ifEmpty { null }
                            "metadata" -> if (metadataName == null) metadataName = text.toString().trim().ifEmpty { null }
                            "trk" -> if (trkName == null) trkName = text.toString().trim().ifEmpty { null }
                            "rte" -> if (rteName == null) rteName = text.toString().trim().ifEmpty { null }
                        }

                        "ele" -> if (inPoint) pendingEle = text.toString().trim().toDoubleOrNull()

                        "trkpt" -> {
                            segmentPoints?.add(GpxPoint(pendingLat!!, pendingLon!!, pendingEle))
                            if ((segmentPoints?.size ?: 0) > MAX_POINTS) {
                                throw GpxParseException("轨迹点数超过上限 $MAX_POINTS")
                            }
                            inPoint = false
                        }

                        "rtept" -> {
                            if (rtePoints == null) rtePoints = mutableListOf()
                            rtePoints!!.add(GpxPoint(pendingLat!!, pendingLon!!, pendingEle))
                            if (rtePoints!!.size > MAX_POINTS) {
                                throw GpxParseException("路线点数超过上限 $MAX_POINTS")
                            }
                            inPoint = false
                        }

                        "trkseg" -> {
                            val pts = segmentPoints
                            if (pts != null && pts.isNotEmpty()) segments.add(GpxTrackSegment(trkName, pts.toList()))
                            segmentPoints = null
                        }

                        "rte" -> {
                            val pts = rtePoints
                            if (pts != null && pts.isNotEmpty()) segments.add(GpxTrackSegment(rteName, pts.toList()))
                            rtePoints = null
                        }
                    }
                    text = StringBuilder()
                }
            }
            event = parser.next()
        }

        if (!sawGpx) throw GpxParseException("缺少 gpx 根元素")
        val name = trkName ?: rteName ?: metadataName ?: topLevelName
        return ParsedGpx(name, segments)
    }
}
