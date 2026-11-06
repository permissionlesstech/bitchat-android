package com.bitchat.android.features.video

/** Minimal Annex B helpers: just enough to split access units and lift SPS/PPS out of IDRs. */
object H264NalUnits {
    const val TYPE_IDR = 5
    const val TYPE_SPS = 7
    const val TYPE_PPS = 8
    const val TYPE_AUD = 9
    const val TYPE_FILLER = 12

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** NAL units (without start codes) in an Annex B buffer. */
    fun split(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>() // payload offset, start-code length
        var index = 0
        while (index + 3 <= data.size) {
            if (data[index].toInt() == 0 && data[index + 1].toInt() == 0) {
                if (data[index + 2].toInt() == 1) {
                    starts += (index + 3) to 3
                    index += 3
                    continue
                }
                if (index + 4 <= data.size && data[index + 2].toInt() == 0 && data[index + 3].toInt() == 1) {
                    starts += (index + 4) to 4
                    index += 4
                    continue
                }
            }
            index++
        }
        return starts.mapIndexedNotNull { position, (offset, _) ->
            val end = if (position + 1 < starts.size) {
                starts[position + 1].first - starts[position + 1].second
            } else data.size
            if (end > offset) data.copyOfRange(offset, end) else null
        }
    }

    fun type(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    fun join(nals: List<ByteArray>): ByteArray {
        val output = java.io.ByteArrayOutputStream(nals.sumOf { it.size + 4 })
        nals.forEach { nal ->
            output.write(START_CODE)
            output.write(nal)
        }
        return output.toByteArray()
    }

    fun containsType(data: ByteArray, type: Int): Boolean = split(data).any { type(it) == type }

    /** SPS and PPS (each with a start code) as MediaFormat `csd-0` / `csd-1`, or null. */
    fun parameterSets(data: ByteArray): Pair<ByteArray, ByteArray>? {
        val nals = split(data)
        val sps = nals.firstOrNull { type(it) == TYPE_SPS } ?: return null
        val pps = nals.firstOrNull { type(it) == TYPE_PPS } ?: return null
        return (START_CODE + sps) to (START_CODE + pps)
    }

    /** Drops filler NAL units, which some CBR encoders emit to pad the bitrate; pure airtime cost. */
    fun withoutFiller(data: ByteArray): ByteArray {
        val nals = split(data)
        if (nals.none { type(it) == TYPE_FILLER }) return data
        return join(nals.filter { type(it) != TYPE_FILLER })
    }

    /** Access unit with SPS, PPS and AUD removed, for muxing into MP4 where csd carries them. */
    fun withoutParameterSets(data: ByteArray): ByteArray =
        join(split(data).filter { type(it) != TYPE_SPS && type(it) != TYPE_PPS && type(it) != TYPE_AUD })
}
