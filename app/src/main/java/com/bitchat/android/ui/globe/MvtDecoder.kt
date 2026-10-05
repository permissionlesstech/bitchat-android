package com.bitchat.android.ui.globe

import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToLong

internal data class DecodedGlobeTile(
    val oceanPolygons: List<OceanPolygon> = emptyList(),
    val borders: List<BorderLine> = emptyList(),
    val boundaryLabels: List<MapLabel> = emptyList(),
    val placeLabels: List<MapLabel> = emptyList()
)

/**
 * Small, defensive Mapbox Vector Tile decoder for the four Shortbread layers used by the
 * globe. Keeping this decoder focused avoids shipping a second rendering engine or a
 * protobuf runtime simply to turn streamed coordinates back into latitude/longitude.
 */
internal class MvtDecoder(
    private val preferredLanguage: String = Locale.getDefault().language
) {
    fun decode(bytes: ByteArray, tile: GlobeTileKey): DecodedGlobeTile {
        if (bytes.size > MAX_TILE_BYTES) {
            throw MvtDecodingException("Vector tile exceeds the supported size")
        }

        val result = MutableDecodedTile()
        val reader = ProtoReader(bytes)
        var layerCount = 0
        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            if (tag.fieldNumber == TILE_LAYER_FIELD && tag.wireType == WIRE_LENGTH_DELIMITED) {
                layerCount++
                if (layerCount > MAX_LAYERS) {
                    throw MvtDecodingException("Vector tile contains too many layers")
                }
                decodeLayer(reader.readBytes(), tile, result)
            } else {
                reader.skip(tag.wireType)
            }
        }
        return result.freeze()
    }

    private fun decodeLayer(
        bytes: ByteArray,
        tile: GlobeTileKey,
        output: MutableDecodedTile
    ) {
        // Shortbread contains many rendering layers (roads, buildings, POIs, and more).
        // Find the layer name without copying its features, then completely skip layers
        // this globe never uses.
        val layerName = readLayerName(bytes) ?: return
        if (layerName !in SUPPORTED_LAYERS) return

        val reader = ProtoReader(bytes)
        var name = layerName
        var extent = DEFAULT_EXTENT
        val keys = ArrayList<String>()
        val values = ArrayList<Any?>()
        val rawFeatures = ArrayList<ByteArray>()

        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            when {
                tag.fieldNumber == LAYER_NAME_FIELD &&
                    tag.wireType == WIRE_LENGTH_DELIMITED -> {
                    name = reader.readString()
                }
                tag.fieldNumber == LAYER_FEATURE_FIELD &&
                    tag.wireType == WIRE_LENGTH_DELIMITED -> {
                    if (rawFeatures.size >= MAX_FEATURES_PER_LAYER) {
                        throw MvtDecodingException("Vector tile layer contains too many features")
                    }
                    rawFeatures.add(reader.readBytes())
                }
                tag.fieldNumber == LAYER_KEY_FIELD &&
                    tag.wireType == WIRE_LENGTH_DELIMITED -> {
                    if (keys.size >= MAX_DICTIONARY_ENTRIES) {
                        throw MvtDecodingException("Vector tile key dictionary is too large")
                    }
                    keys.add(reader.readString())
                }
                tag.fieldNumber == LAYER_VALUE_FIELD &&
                    tag.wireType == WIRE_LENGTH_DELIMITED -> {
                    if (values.size >= MAX_DICTIONARY_ENTRIES) {
                        throw MvtDecodingException("Vector tile value dictionary is too large")
                    }
                    values.add(decodeValue(reader.readBytes()))
                }
                tag.fieldNumber == LAYER_EXTENT_FIELD && tag.wireType == WIRE_VARINT -> {
                    extent = reader.readVarint().toInt()
                    if (extent !in 1..MAX_EXTENT) {
                        throw MvtDecodingException("Unsupported vector tile extent")
                    }
                }
                else -> reader.skip(tag.wireType)
            }
        }

        rawFeatures.forEach { featureBytes ->
            decodeFeature(name, featureBytes, keys, values, extent, tile, output)
        }
    }

    private fun readLayerName(bytes: ByteArray): String? {
        val reader = ProtoReader(bytes)
        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            if (
                tag.fieldNumber == LAYER_NAME_FIELD &&
                tag.wireType == WIRE_LENGTH_DELIMITED
            ) {
                return reader.readString()
            }
            reader.skip(tag.wireType)
        }
        return null
    }

    private fun decodeFeature(
        layerName: String,
        bytes: ByteArray,
        keys: List<String>,
        values: List<Any?>,
        extent: Int,
        tile: GlobeTileKey,
        output: MutableDecodedTile
    ) {
        val reader = ProtoReader(bytes)
        var geometryType = GEOMETRY_UNKNOWN
        var rawTags = IntArray(0)
        var geometry = IntArray(0)

        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            when {
                tag.fieldNumber == FEATURE_TAGS_FIELD -> {
                    rawTags = reader.readPackedUInt32(tag.wireType, MAX_TAG_VALUES)
                }
                tag.fieldNumber == FEATURE_TYPE_FIELD && tag.wireType == WIRE_VARINT -> {
                    geometryType = reader.readVarint().toInt()
                }
                tag.fieldNumber == FEATURE_GEOMETRY_FIELD -> {
                    geometry = reader.readPackedUInt32(tag.wireType, MAX_GEOMETRY_VALUES)
                }
                else -> reader.skip(tag.wireType)
            }
        }
        if (geometry.isEmpty()) return

        val properties = decodeProperties(rawTags, keys, values)
        when (layerName) {
            OCEAN_LAYER -> {
                if (geometryType != GEOMETRY_POLYGON) return
                val paths = decodeGeometry(geometry, geometryType)
                val rings = paths.mapNotNull { path ->
                    path.toGeoRing(
                        tile = tile,
                        extent = extent,
                        minimumPoints = 3,
                        isMvtExterior = path.signedAreaTwice() > 0L
                    )
                }
                if (rings.isNotEmpty()) output.oceanPolygons.add(OceanPolygon(rings))
            }
            BOUNDARIES_LAYER -> {
                if (geometryType != GEOMETRY_LINESTRING) return
                val maritime = properties.booleanValue("maritime")
                val disputed = properties.booleanValue("disputed")
                val adminLevel = properties.intValue("admin_level")
                decodeGeometry(geometry, geometryType).forEach { path ->
                    val ring = path.toGeoRing(tile, extent, minimumPoints = 2) ?: return@forEach
                    output.borders.add(
                        BorderLine(
                            ring = ring,
                            maritime = maritime,
                            disputed = disputed,
                            adminLevel = adminLevel
                        )
                    )
                }
            }
            BOUNDARY_LABELS_LAYER -> {
                if (geometryType != GEOMETRY_POINT) return
                val name = properties.localizedName() ?: return
                val adminLevel = properties.intValue("admin_level")
                val kind = if (adminLevel == 2) MapLabelKind.COUNTRY else MapLabelKind.STATE
                val importance = properties.longValue("way_area") ?: 0L
                decodeGeometry(geometry, geometryType).forEach { path ->
                    val point = path.firstOrNull() ?: return@forEach
                    output.boundaryLabels.add(
                        point.toMapLabel(tile, extent, name, kind, importance)
                    )
                }
            }
            PLACE_LABELS_LAYER -> {
                if (geometryType != GEOMETRY_POINT) return
                val name = properties.localizedName() ?: return
                val kind = mapPlaceKind(properties.stringValue("kind"))
                val population = properties.longValue("population") ?: 0L
                decodeGeometry(geometry, geometryType).forEach { path ->
                    val point = path.firstOrNull() ?: return@forEach
                    output.placeLabels.add(
                        point.toMapLabel(tile, extent, name, kind, population)
                    )
                }
            }
        }
    }

    private fun decodeProperties(
        rawTags: IntArray,
        keys: List<String>,
        values: List<Any?>
    ): Map<String, Any?> {
        if (rawTags.size % 2 != 0) {
            throw MvtDecodingException("Vector tile feature has malformed tags")
        }
        return buildMap(rawTags.size / 2) {
            var index = 0
            while (index < rawTags.size) {
                val keyIndex = rawTags[index]
                val valueIndex = rawTags[index + 1]
                if (keyIndex !in keys.indices || valueIndex !in values.indices) {
                    throw MvtDecodingException("Vector tile feature references an invalid tag")
                }
                put(keys[keyIndex], values[valueIndex])
                index += 2
            }
        }
    }

    private fun decodeValue(bytes: ByteArray): Any? {
        val reader = ProtoReader(bytes)
        var value: Any? = null
        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            value = when {
                tag.fieldNumber == VALUE_STRING_FIELD &&
                    tag.wireType == WIRE_LENGTH_DELIMITED -> reader.readString()
                tag.fieldNumber == VALUE_FLOAT_FIELD &&
                    tag.wireType == WIRE_FIXED32 -> Float.fromBits(reader.readFixed32())
                tag.fieldNumber == VALUE_DOUBLE_FIELD &&
                    tag.wireType == WIRE_FIXED64 -> Double.fromBits(reader.readFixed64())
                tag.fieldNumber == VALUE_INT_FIELD &&
                    tag.wireType == WIRE_VARINT -> reader.readVarint()
                tag.fieldNumber == VALUE_UINT_FIELD &&
                    tag.wireType == WIRE_VARINT -> reader.readVarint()
                tag.fieldNumber == VALUE_SINT_FIELD &&
                    tag.wireType == WIRE_VARINT -> decodeZigZag64(reader.readVarint())
                tag.fieldNumber == VALUE_BOOL_FIELD &&
                    tag.wireType == WIRE_VARINT -> reader.readVarint() != 0L
                else -> {
                    reader.skip(tag.wireType)
                    value
                }
            }
        }
        return value
    }

    private fun decodeGeometry(encoded: IntArray, geometryType: Int): List<List<TilePoint>> {
        val paths = ArrayList<MutableList<TilePoint>>()
        var currentPath: MutableList<TilePoint>? = null
        var cursorX = 0
        var cursorY = 0
        var index = 0

        while (index < encoded.size) {
            val commandInteger = encoded[index++]
            val command = commandInteger and COMMAND_ID_MASK
            val count = commandInteger ushr COMMAND_COUNT_SHIFT
            if (count <= 0 || count > MAX_COMMAND_COUNT) {
                throw MvtDecodingException("Vector tile geometry has an invalid command count")
            }
            when (command) {
                COMMAND_MOVE_TO, COMMAND_LINE_TO -> {
                    repeat(count) {
                        if (index + 1 >= encoded.size) {
                            throw MvtDecodingException("Vector tile geometry is truncated")
                        }
                        cursorX += decodeZigZag32(encoded[index++])
                        cursorY += decodeZigZag32(encoded[index++])
                        val targetPath = if (
                            geometryType == GEOMETRY_POINT ||
                            command == COMMAND_MOVE_TO ||
                            currentPath == null
                        ) {
                            ArrayList<TilePoint>().also { newPath ->
                                currentPath = newPath
                                paths.add(newPath)
                            }
                        } else {
                            requireNotNull(currentPath)
                        }
                        targetPath.add(TilePoint(cursorX, cursorY))
                    }
                }
                COMMAND_CLOSE_PATH -> {
                    if (geometryType != GEOMETRY_POLYGON || count != 1 || currentPath == null) {
                        throw MvtDecodingException("Vector tile geometry has an invalid close command")
                    }
                }
                else -> throw MvtDecodingException("Vector tile geometry uses an unknown command")
            }
        }
        return paths
    }

    private fun List<TilePoint>.toGeoRing(
        tile: GlobeTileKey,
        extent: Int,
        minimumPoints: Int,
        isMvtExterior: Boolean? = null
    ): GeoRing? {
        if (size < minimumPoints) return null
        val coords = FloatArray(size * 2)
        forEachIndexed { index, point ->
            coords[index * 2] = GlobeTileSelector.tilePointToLatitude(
                tile.y, point.y, extent, tile.zoom
            ).toFloat()
            coords[index * 2 + 1] = GlobeTileSelector.tilePointToLongitude(
                tile.x, point.x, extent, tile.zoom
            ).toFloat()
        }
        return GeoRing(
            coords = coords,
            size = size,
            isMvtExterior = isMvtExterior
        )
    }

    private fun List<TilePoint>.signedAreaTwice(): Long {
        if (size < 3) return 0L
        var area = 0L
        var previous = last()
        for (point in this) {
            area += previous.x.toLong() * point.y - point.x.toLong() * previous.y
            previous = point
        }
        return area
    }

    private fun TilePoint.toMapLabel(
        tile: GlobeTileKey,
        extent: Int,
        name: String,
        kind: MapLabelKind,
        importance: Long
    ): MapLabel {
        return MapLabel(
            name = name,
            lat = GlobeTileSelector.tilePointToLatitude(
                tile.y, y, extent, tile.zoom
            ).toFloat(),
            lon = GlobeTileSelector.tilePointToLongitude(
                tile.x, x, extent, tile.zoom
            ).toFloat(),
            kind = kind,
            importance = importance
        )
    }

    private fun Map<String, Any?>.localizedName(): String? {
        val preferred = preferredLanguage
            .takeIf { it.matches(LANGUAGE_CODE) }
            ?.let { stringValue("name_$it") }
        return sequenceOf(preferred, stringValue("name"), stringValue("name_en"))
            .filterNotNull()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
    }

    private fun Map<String, Any?>.stringValue(key: String): String? = get(key) as? String

    private fun Map<String, Any?>.intValue(key: String): Int? {
        return when (val value = get(key)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun Map<String, Any?>.longValue(key: String): Long? {
        return when (val value = get(key)) {
            is Number -> value.toDouble().roundToLong()
            is String -> value.toDoubleOrNull()?.roundToLong()
            else -> null
        }
    }

    private fun Map<String, Any?>.booleanValue(key: String): Boolean {
        return when (val value = get(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value == "1" || value.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun mapPlaceKind(kind: String?): MapLabelKind {
        return when (kind) {
            "capital", "state_capital" -> MapLabelKind.CAPITAL
            "city" -> MapLabelKind.CITY
            "town" -> MapLabelKind.TOWN
            "village", "hamlet" -> MapLabelKind.VILLAGE
            else -> MapLabelKind.OTHER
        }
    }

    private data class TilePoint(val x: Int, val y: Int)

    private class MutableDecodedTile {
        val oceanPolygons = ArrayList<OceanPolygon>()
        val borders = ArrayList<BorderLine>()
        val boundaryLabels = ArrayList<MapLabel>()
        val placeLabels = ArrayList<MapLabel>()

        fun freeze() = DecodedGlobeTile(
            oceanPolygons = oceanPolygons,
            borders = borders,
            boundaryLabels = boundaryLabels,
            placeLabels = placeLabels
        )
    }

    companion object {
        internal const val MAX_TILE_BYTES = 2 * 1024 * 1024
        private const val MAX_LAYERS = 64
        private const val MAX_FEATURES_PER_LAYER = 100_000
        private const val MAX_DICTIONARY_ENTRIES = 100_000
        private const val MAX_TAG_VALUES = 100_000
        private const val MAX_GEOMETRY_VALUES = 1_000_000
        private const val MAX_COMMAND_COUNT = 1_000_000
        private const val MAX_EXTENT = 65_536
        private const val DEFAULT_EXTENT = 4096

        private const val TILE_LAYER_FIELD = 3
        private const val LAYER_NAME_FIELD = 1
        private const val LAYER_FEATURE_FIELD = 2
        private const val LAYER_KEY_FIELD = 3
        private const val LAYER_VALUE_FIELD = 4
        private const val LAYER_EXTENT_FIELD = 5
        private const val FEATURE_TAGS_FIELD = 2
        private const val FEATURE_TYPE_FIELD = 3
        private const val FEATURE_GEOMETRY_FIELD = 4
        private const val VALUE_STRING_FIELD = 1
        private const val VALUE_FLOAT_FIELD = 2
        private const val VALUE_DOUBLE_FIELD = 3
        private const val VALUE_INT_FIELD = 4
        private const val VALUE_UINT_FIELD = 5
        private const val VALUE_SINT_FIELD = 6
        private const val VALUE_BOOL_FIELD = 7

        private const val OCEAN_LAYER = "ocean"
        private const val BOUNDARIES_LAYER = "boundaries"
        private const val BOUNDARY_LABELS_LAYER = "boundary_labels"
        private const val PLACE_LABELS_LAYER = "place_labels"
        private val SUPPORTED_LAYERS = setOf(
            OCEAN_LAYER,
            BOUNDARIES_LAYER,
            BOUNDARY_LABELS_LAYER,
            PLACE_LABELS_LAYER
        )

        private const val GEOMETRY_UNKNOWN = 0
        private const val GEOMETRY_POINT = 1
        private const val GEOMETRY_LINESTRING = 2
        private const val GEOMETRY_POLYGON = 3
        private const val COMMAND_MOVE_TO = 1
        private const val COMMAND_LINE_TO = 2
        private const val COMMAND_CLOSE_PATH = 7
        private const val COMMAND_ID_MASK = 0x7
        private const val COMMAND_COUNT_SHIFT = 3
        private val LANGUAGE_CODE = Regex("[a-z]{2,3}")

        private fun decodeZigZag32(value: Int): Int = (value ushr 1) xor -(value and 1)
        private fun decodeZigZag64(value: Long): Long = (value ushr 1) xor -(value and 1L)
    }
}

internal class MvtDecodingException(message: String) : Exception(message)

private data class ProtoTag(val fieldNumber: Int, val wireType: Int)

private class ProtoReader(private val bytes: ByteArray) {
    private var position = 0
    val isAtEnd: Boolean get() = position >= bytes.size

    fun readTag(): ProtoTag {
        val rawTag = readVarint()
        val fieldNumber = (rawTag ushr 3).toInt()
        val wireType = (rawTag and 0x7).toInt()
        if (fieldNumber <= 0) throw MvtDecodingException("Invalid protobuf field")
        return ProtoTag(fieldNumber, wireType)
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (position >= bytes.size) throw MvtDecodingException("Truncated protobuf value")
            val value = bytes[position++].toInt() and 0xff
            result = result or ((value and 0x7f).toLong() shl shift)
            if ((value and 0x80) == 0) return result
            shift += 7
        }
        throw MvtDecodingException("Malformed protobuf value")
    }

    fun readFixed32(): Int {
        requireAvailable(4)
        val result =
            (bytes[position].toInt() and 0xff) or
                ((bytes[position + 1].toInt() and 0xff) shl 8) or
                ((bytes[position + 2].toInt() and 0xff) shl 16) or
                ((bytes[position + 3].toInt() and 0xff) shl 24)
        position += 4
        return result
    }

    fun readFixed64(): Long {
        requireAvailable(8)
        var result = 0L
        for (offset in 0 until 8) {
            result = result or ((bytes[position + offset].toLong() and 0xffL) shl (offset * 8))
        }
        position += 8
        return result
    }

    fun readBytes(): ByteArray {
        val length = readLength()
        requireAvailable(length)
        return bytes.copyOfRange(position, position + length).also { position += length }
    }

    fun readString(): String {
        val value = readBytes()
        if (value.size > MAX_STRING_BYTES) {
            throw MvtDecodingException("Vector tile string is too long")
        }
        return String(value, StandardCharsets.UTF_8)
    }

    fun readPackedUInt32(wireType: Int, maximumValues: Int): IntArray {
        return when (wireType) {
            WIRE_VARINT -> intArrayOf(readVarint().checkedUInt32())
            WIRE_LENGTH_DELIMITED -> {
                val packedReader = ProtoReader(readBytes())
                val result = ArrayList<Int>()
                while (!packedReader.isAtEnd) {
                    if (result.size >= maximumValues) {
                        throw MvtDecodingException("Packed protobuf field is too large")
                    }
                    result.add(packedReader.readVarint().checkedUInt32())
                }
                result.toIntArray()
            }
            else -> throw MvtDecodingException("Unexpected protobuf wire type")
        }
    }

    fun skip(wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> {
                requireAvailable(8)
                position += 8
            }
            WIRE_LENGTH_DELIMITED -> {
                val length = readLength()
                requireAvailable(length)
                position += length
            }
            WIRE_FIXED32 -> {
                requireAvailable(4)
                position += 4
            }
            else -> throw MvtDecodingException("Unsupported protobuf wire type")
        }
    }

    private fun readLength(): Int {
        val length = readVarint()
        if (length < 0L || length > Int.MAX_VALUE) {
            throw MvtDecodingException("Invalid protobuf length")
        }
        return length.toInt()
    }

    private fun requireAvailable(count: Int) {
        if (count < 0 || position > bytes.size - count) {
            throw MvtDecodingException("Truncated protobuf field")
        }
    }

    private fun Long.checkedUInt32(): Int {
        if (this < 0L || this > Int.MAX_VALUE) {
            throw MvtDecodingException("Protobuf integer exceeds uint32")
        }
        return toInt()
    }

    companion object {
        private const val MAX_STRING_BYTES = 16 * 1024
    }
}

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH_DELIMITED = 2
private const val WIRE_FIXED32 = 5
