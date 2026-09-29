package dev.hike.dataassistant.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class GpxParserTest {

    private fun parserFor(xml: String): ParsedGpx {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        return GpxParser(parser).parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `parses trk trkseg trkpt with elevation`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="2bulu">
              <trk><name>黄山环线</name>
                <trkseg>
                  <trkpt lat="30.00000" lon="120.00000"><ele>100.0</ele></trkpt>
                  <trkpt lat="30.00100" lon="120.00000"><ele>105.5</ele></trkpt>
                  <trkpt lat="30.00200" lon="120.00000"><ele>110.0</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()
        val parsed = parserFor(gpx)
        assertEquals("黄山环线", parsed.trackName)
        assertEquals(1, parsed.segments.size)
        val seg = parsed.segments.first()
        assertEquals("黄山环线", seg.name)
        assertEquals(3, seg.points.size)
        assertEquals(30.001, seg.points[1].lat, 1e-9)
        assertEquals(105.5, seg.points[1].eleMeters!!, 1e-6)
    }

    @Test
    fun `missing ele leaves elevation null`() {
        val gpx = """
            <gpx version="1.1">
              <trk><trkseg>
                <trkpt lat="30.0" lon="120.0"></trkpt>
                <trkpt lat="30.001" lon="120.0"></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        val seg = parserFor(gpx).segments.single()
        assertNull(seg.points[0].eleMeters)
    }

    @Test
    fun `falls back to rte rtept when no track exists`() {
        val gpx = """
            <gpx version="1.1">
              <rte><name>备选路线</name>
                <rtept lat="30.0" lon="120.0"><ele>50</ele></rtept>
                <rtept lat="30.001" lon="120.0"><ele>60</ele></rtept>
              </rte>
            </gpx>
        """.trimIndent()
        val parsed = parserFor(gpx)
        assertEquals(1, parsed.segments.size)
        assertEquals(2, parsed.segments[0].points.size)
        assertEquals("备选路线", parsed.segments[0].name)
    }

    @Test
    fun `multiple trkseg stay separate segments without silent merging`() {
        val gpx = """
            <gpx version="1.1">
              <trk>
                <trkseg>
                  <trkpt lat="30.0" lon="120.0"></trkpt>
                  <trkpt lat="30.001" lon="120.0"></trkpt>
                </trkseg>
                <trkseg>
                  <trkpt lat="30.1" lon="120.1"></trkpt>
                  <trkpt lat="30.101" lon="120.1"></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()
        val parsed = parserFor(gpx)
        assertEquals(2, parsed.segments.size)
        assertEquals(2, parsed.segments[1].points.size)
    }

    @Test(expected = GpxParseException::class)
    fun `external entity injection is rejected`() {
        val gpx = """
            <?xml version="1.0"?>
            <!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///C:/Windows/win.ini">]>
            <gpx version="1.1">
              <trk><name>&xxe;</name><trkseg>
                <trkpt lat="30.0" lon="120.0"></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        parserFor(gpx)
    }

    @Test(expected = GpxParseException::class)
    fun `garbage xml is rejected`() {
        parserFor("this is not xml at all <<<<")
    }

    @Test(expected = GpxParseException::class)
    fun `trkpt without coordinates is rejected`() {
        val gpx = """
            <gpx version="1.1">
              <trk><trkseg>
                <trkpt lat="30.0"></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        parserFor(gpx)
    }

    @Test
    fun `empty gpx yields no segments instead of crash`() {
        val parsed = parserFor("""<gpx version="1.1"></gpx>""")
        assertTrue(parsed.segments.isEmpty())
    }
}
