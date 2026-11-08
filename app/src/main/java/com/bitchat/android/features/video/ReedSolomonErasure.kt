package com.bitchat.android.features.video

/**
 * Systematic Reed-Solomon erasure code over GF(2^8) with a Cauchy generator.
 *
 * Parity row `j` (0-based) for source symbol `i` uses coefficient `1 / (i XOR (k + j))`.
 * Every square submatrix of a Cauchy matrix is invertible, so any `k` of the `k + r`
 * symbols reconstruct the block (MDS). Field polynomial: x^8 + x^4 + x^3 + x^2 + 1 (0x11D).
 */
object ReedSolomonErasure {
    private const val FIELD_SIZE = 256
    private val exp = IntArray(FIELD_SIZE * 2)
    private val log = IntArray(FIELD_SIZE)

    init {
        var value = 1
        for (power in 0 until FIELD_SIZE - 1) {
            exp[power] = value
            log[value] = power
            value = value shl 1
            if (value and 0x100 != 0) value = value xor 0x11D
        }
        for (power in FIELD_SIZE - 1 until exp.size) exp[power] = exp[power - (FIELD_SIZE - 1)]
    }

    fun multiply(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    fun inverse(a: Int): Int {
        require(a != 0) { "zero has no inverse" }
        return exp[(FIELD_SIZE - 1) - log[a]]
    }

    fun coefficient(sourceCount: Int, parityRow: Int, sourceIndex: Int): Int =
        inverse(sourceIndex xor (sourceCount + parityRow))

    /** Returns `parityCount` symbols, each as long as the (equal-length) [sources]. */
    fun encode(sources: List<ByteArray>, parityCount: Int): List<ByteArray> {
        val k = sources.size
        require(k in 1..128 && parityCount in 1..128 && k + parityCount <= FIELD_SIZE)
        val length = sources.first().size
        require(sources.all { it.size == length })
        return List(parityCount) { row ->
            val parity = ByteArray(length)
            for (i in 0 until k) {
                addScaled(parity, sources[i], coefficient(k, row, i))
            }
            parity
        }
    }

    /**
     * Rebuilds missing source symbols.
     *
     * [sources] holds the `k` source slots (null where lost) and [parities] maps a parity row
     * to its symbol. Returns the completed source list, or null when fewer than `k` symbols
     * are available.
     */
    fun reconstruct(sources: List<ByteArray?>, parities: Map<Int, ByteArray>): List<ByteArray>? {
        val k = sources.size
        val missing = sources.indices.filter { sources[it] == null }
        if (missing.isEmpty()) return sources.map { it!! }
        if (parities.size < missing.size) return null
        val length = (sources.firstOrNull { it != null } ?: parities.values.first()).size
        if (sources.any { it != null && it.size != length } || parities.values.any { it.size != length }) {
            return null
        }
        val rows = parities.keys.sorted().take(missing.size)
        // Right-hand side: parity minus the contribution of the symbols we already have.
        val rhs = rows.map { row ->
            val value = parities.getValue(row).copyOf()
            for (i in 0 until k) {
                val source = sources[i] ?: continue
                addScaled(value, source, coefficient(k, row, i))
            }
            value
        }
        val matrix = Array(rows.size) { r -> IntArray(missing.size) { c -> coefficient(k, rows[r], missing[c]) } }
        val solved = solve(matrix, rhs) ?: return null
        val output = sources.toMutableList()
        missing.forEachIndexed { index, sourceIndex -> output[sourceIndex] = solved[index] }
        return output.map { it!! }
    }

    /** Gauss-Jordan elimination over GF(256); [rhs] rows are byte vectors. */
    private fun solve(matrix: Array<IntArray>, rhs: List<ByteArray>): List<ByteArray>? {
        val n = matrix.size
        val values = rhs.map { it.copyOf() }.toMutableList()
        for (column in 0 until n) {
            val pivot = (column until n).firstOrNull { matrix[it][column] != 0 } ?: return null
            if (pivot != column) {
                val row = matrix[pivot]; matrix[pivot] = matrix[column]; matrix[column] = row
                val value = values[pivot]; values[pivot] = values[column]; values[column] = value
            }
            val scale = inverse(matrix[column][column])
            for (c in 0 until n) matrix[column][c] = multiply(matrix[column][c], scale)
            scaleInPlace(values[column], scale)
            for (row in 0 until n) {
                if (row == column) continue
                val factor = matrix[row][column]
                if (factor == 0) continue
                for (c in 0 until n) matrix[row][c] = matrix[row][c] xor multiply(factor, matrix[column][c])
                addScaled(values[row], values[column], factor)
            }
        }
        return values
    }

    private fun addScaled(target: ByteArray, source: ByteArray, factor: Int) {
        if (factor == 0) return
        val logFactor = log[factor]
        for (index in target.indices) {
            val byte = source[index].toInt() and 0xFF
            if (byte != 0) {
                target[index] = (target[index].toInt() xor exp[log[byte] + logFactor]).toByte()
            }
        }
    }

    private fun scaleInPlace(target: ByteArray, factor: Int) {
        val logFactor = log[factor]
        for (index in target.indices) {
            val byte = target[index].toInt() and 0xFF
            target[index] = if (byte == 0) 0 else exp[log[byte] + logFactor].toByte()
        }
    }
}
