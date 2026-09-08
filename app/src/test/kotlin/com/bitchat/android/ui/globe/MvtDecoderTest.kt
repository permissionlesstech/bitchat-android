package com.bitchat.android.ui.globe

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MvtDecoderTest {
    @Test
    fun decodesShortbreadGlobeLayersAndLocalizedNames() {
        val tile = GlobeTileKey(2, 2, 1)
        val bytes = message(
            bytesField(3, oceanLayer()),
            bytesField(3, boundaryLayer()),
            bytesField(3, boundaryLabelLayer()),
            bytesField(3, placeLabelLayer())
        )

        val decoded = MvtDecoder(preferredLanguage = "de").decode(bytes, tile)

        assertEquals(1, decoded.oceanPolygons.size)
        assertEquals(1, decoded.oceanPolygons.single().rings.size)
        assertEquals(4, decoded.oceanPolygons.single().rings.single().size)
        assertEquals(true, decoded.oceanPolygons.single().rings.single().isMvtExterior)

        val border = decoded.borders.single()
        assertEquals(2, border.adminLevel)
        assertFalse(border.maritime)
        assertTrue(border.disputed)
        assertEquals(2, border.ring.size)

        val country = decoded.boundaryLabels.single()
        assertEquals("Testland", country.name)
        assertEquals(MapLabelKind.COUNTRY, country.kind)
        assertEquals(8_000_000L, country.importance)

        val capital = decoded.placeLabels.single()
        assertEquals("Prüfstadt", capital.name)
        assertEquals(MapLabelKind.CAPITAL, capital.kind)
        assertEquals(3_700_000L, capital.importance)
        assertTrue(capital.lat in -85.1f..85.1f)
        assertTrue(capital.lon in -180f..180f)
    }

    @Test(expected = MvtDecodingException::class)
    fun rejectsOversizedTilesBeforeParsing() {
        MvtDecoder().decode(
            ByteArray(MvtDecoder.MAX_TILE_BYTES + 1),
            GlobeTileKey(0, 0, 0)
        )
    }

    @Test
    fun skipsUnsupportedLayersBeforeParsingTheirFeatures() {
        val malformedFeature = byteArrayOf(0)
        val bytes = message(
            bytesField(
                3,
                layer(
                    name = "streets",
                    keys = emptyList(),
                    values = emptyList(),
                    features = listOf(malformedFeature)
                )
            ),
            bytesField(3, oceanLayer())
        )

        val decoded = MvtDecoder().decode(bytes, GlobeTileKey(0, 0, 0))

        assertEquals(1, decoded.oceanPolygons.size)
    }

    private fun oceanLayer(): ByteArray {
        val geometry = intArrayOf(
            command(MOVE_TO, 1),
            zigZag(200),
            zigZag(200),
            command(LINE_TO, 3),
            zigZag(3000),
            zigZag(0),
            zigZag(0),
            zigZag(3000),
            zigZag(-3000),
            zigZag(0),
            command(CLOSE_PATH, 1)
        )
        return layer(
            name = "ocean",
            keys = emptyList(),
            values = emptyList(),
            features = listOf(feature(type = 3, geometry = geometry))
        )
    }

    private fun boundaryLayer(): ByteArray {
        val keys = listOf("admin_level", "maritime", "disputed")
        val values = listOf(uintValue(2), boolValue(false), boolValue(true))
        val geometry = intArrayOf(
            command(MOVE_TO, 1),
            zigZag(400),
            zigZag(1000),
            command(LINE_TO, 1),
            zigZag(2500),
            zigZag(0)
        )
        return layer(
            name = "boundaries",
            keys = keys,
            values = values,
            features = listOf(
                feature(
                    tags = intArrayOf(0, 0, 1, 1, 2, 2),
                    type = 2,
                    geometry = geometry
                )
            )
        )
    }

    private fun boundaryLabelLayer(): ByteArray {
        val keys = listOf("admin_level", "name", "way_area")
        val values = listOf(uintValue(2), stringValue("Testland"), uintValue(8_000_000))
        return layer(
            name = "boundary_labels",
            keys = keys,
            values = values,
            features = listOf(
                feature(
                    tags = intArrayOf(0, 0, 1, 1, 2, 2),
                    type = 1,
                    geometry = pointGeometry(1900, 1800)
                )
            )
        )
    }

    private fun placeLabelLayer(): ByteArray {
        val keys = listOf("name", "name_de", "kind", "population")
        val values = listOf(
            stringValue("Test City"),
            stringValue("Prüfstadt"),
            stringValue("capital"),
            uintValue(3_700_000)
        )
        return layer(
            name = "place_labels",
            keys = keys,
            values = values,
            features = listOf(
                feature(
                    tags = intArrayOf(0, 0, 1, 1, 2, 2, 3, 3),
                    type = 1,
                    geometry = pointGeometry(2100, 2200)
                )
            )
        )
    }

    private fun layer(
        name: String,
        keys: List<String>,
        values: List<ByteArray>,
        features: List<ByteArray>
    ): ByteArray = message(
        varintField(15, 2),
        bytesField(1, name.encodeToByteArray()),
        *features.map { bytesField(2, it) }.toTypedArray(),
        *keys.map { bytesField(3, it.encodeToByteArray()) }.toTypedArray(),
        *values.map { bytesField(4, it) }.toTypedArray(),
        varintField(5, EXTENT.toLong())
    )

    private fun feature(
        tags: IntArray = intArrayOf(),
        type: Int,
        geometry: IntArray
    ): ByteArray = message(
        if (tags.isEmpty()) byteArrayOf() else bytesField(2, packed(tags)),
        varintField(3, type.toLong()),
        bytesField(4, packed(geometry))
    )

    private fun pointGeometry(x: Int, y: Int): IntArray = intArrayOf(
        command(MOVE_TO, 1),
        zigZag(x),
        zigZag(y)
    )

    private fun stringValue(value: String): ByteArray =
        bytesField(1, value.encodeToByteArray())

    private fun uintValue(value: Long): ByteArray = varintField(5, value)

    private fun boolValue(value: Boolean): ByteArray =
        varintField(7, if (value) 1 else 0)

    private fun bytesField(number: Int, bytes: ByteArray): ByteArray =
        message(varint((number shl 3 or 2).toLong()), varint(bytes.size.toLong()), bytes)

    private fun varintField(number: Int, value: Long): ByteArray =
        message(varint((number shl 3).toLong()), varint(value))

    private fun packed(values: IntArray): ByteArray =
        message(*values.map { varint(it.toLong()) }.toTypedArray())

    private fun command(id: Int, count: Int): Int = count shl 3 or id

    private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)

    private fun message(vararg pieces: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        pieces.forEach(output::write)
        return output.toByteArray()
    }

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val output = ByteArrayOutputStream()
        while (true) {
            if ((remaining and -128L) == 0L) {
                output.write(remaining.toInt())
                return output.toByteArray()
            }
            output.write((remaining.toInt() and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
    }

    companion object {
        private const val EXTENT = 4096
        private const val MOVE_TO = 1
        private const val LINE_TO = 2
        private const val CLOSE_PATH = 7
    }
}
