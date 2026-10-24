package com.bitchat.android.nostr

/**
 * Bech32 encoding/decoding implementation for Nostr
 * Used for npub/nsec encoding
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    
    /**
     * Encode data with HRP (Human Readable Part)
     */
    fun encode(hrp: String, data: ByteArray): String {
        val values = convertBits(data, 8, 5, true).toList()
        val checksum = createChecksum(hrp, values)
        val combined = values + checksum
        
        return hrp + "1" + combined.map { CHARSET[it] }.joinToString("")
    }
    
    /**
     * Decode bech32 string
     * Returns (hrp, data) pair
     */
    fun decode(bech32String: String): Pair<String, ByteArray> {
        val separatorIndex = bech32String.lastIndexOf('1')
        require(separatorIndex >= 0) { "No separator found" }
        
        val hrp = bech32String.substring(0, separatorIndex)
        val dataString = bech32String.substring(separatorIndex + 1)
        
        // Validate HRP contains only ASCII
        require(hrp.all { it.code < 128 }) { "Invalid HRP characters" }
        
        // Convert characters to values
        val values = dataString.map { char ->
            val index = CHARSET.indexOf(char)
            require(index >= 0) { "Invalid character: $char" }
            index
        }
        
        // Verify checksum
        require(values.size >= 6) { "Data too short" }
        val payloadValues = values.dropLast(6)
        val checksum = values.takeLast(6)
        val expectedChecksum = createChecksum(hrp, payloadValues)
        
        require(checksum == expectedChecksum) { "Invalid checksum" }
        
        // Convert back to bytes
        val bytesInt = convertBits(payloadValues.toIntArray(), 5, 8, false)
        val bytes = bytesInt.map { it.toByte() }.toByteArray()
        return Pair(hrp, bytes)
    }
    
    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        return convertBits(data.map { it.toInt() and 0xFF }.toIntArray(), fromBits, toBits, pad)
    }
    
    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1
        
        for (value in data) {
            acc = (acc shl fromBits) or value
            bits += fromBits
            
            while (bits >= toBits) {
                bits -= toBits
                result.add((acc shr bits) and maxv)
            }
        }
        
        if (pad && bits > 0) {
            result.add((acc shl (toBits - bits)) and maxv)
        }
        
        return result.toIntArray()
    }
    
    private fun convertBits(data: List<Int>, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        return convertBits(data.toIntArray(), fromBits, toBits, pad)
    }
    
    private fun createChecksum(hrp: String, values: List<Int>): List<Int> {
        val checksumValues = hrpExpand(hrp) + values + intArrayOf(0, 0, 0, 0, 0, 0)
        val polymod = polymod(checksumValues) xor 1
        
        return (0 until 6).map { i ->
            (polymod shr (5 * (5 - i))) and 31
        }
    }
    
    private fun hrpExpand(hrp: String): IntArray {
        val result = mutableListOf<Int>()
        
        // High bits
        hrp.forEach { c ->
            result.add(c.code shr 5)
        }
        
        // Separator
        result.add(0)
        
        // Low bits
        hrp.forEach { c ->
            result.add(c.code and 31)
        }
        
        return result.toIntArray()
    }
    
    private fun polymod(values: IntArray): Int {
        var chk = 1
        
        for (value in values) {
            val b = chk shr 25
            chk = (chk and 0x1ffffff) shl 5 xor value
            
            for (i in 0 until 5) {
                if ((b shr i) and 1 == 1) {
                    chk = chk xor GENERATOR[i]
                }
            }
        }
        
        return chk
    }
}
