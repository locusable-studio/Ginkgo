package com.locusable.ginkgo

import kotlin.math.abs

/** Byte-mode QR for short ASCII login URLs, Model 2 versions 5-L and 8-L. */
internal object LoginQr {
    fun encode(url: String): Array<BooleanArray>? {
        if (url.any { it.code !in 32..126 } || url.length !in 1..192) return null
        val version = if (url.length <= 106) 5 else 8
        val blocks = if (version == 5) 1 else 2
        val dataLength = if (version == 5) 108 else 97
        val parityLength = if (version == 5) 26 else 24
        val data = IntArray(blocks * dataLength)
        var bit = 0
        fun append(value: Int, length: Int) {
            for (i in length - 1 downTo 0) {
                data[bit / 8] = data[bit / 8] or (((value ushr i) and 1) shl (7 - bit % 8))
                bit++
            }
        }
        append(4, 4) // Byte mode
        append(url.length, 8)
        url.forEach { append(it.code, 8) }
        append(0, 4) // Terminator also completes the byte for these versions.
        for (i in bit / 8 until data.size) data[i] = if ((i - bit / 8) % 2 == 0) 236 else 17

        val divisor = IntArray(parityLength).also { it[it.lastIndex] = 1 }
        var root = 1
        repeat(parityLength) {
            for (i in divisor.indices) {
                divisor[i] = multiply(divisor[i], root) xor (divisor.getOrNull(i + 1) ?: 0)
            }
            root = multiply(root, 2)
        }
        val parity = Array(blocks) { block ->
            val rest = IntArray(parityLength)
            for (i in 0 until dataLength) {
                val factor = data[block * dataLength + i] xor rest[0]
                for (j in rest.indices) rest[j] = (rest.getOrNull(j + 1) ?: 0) xor multiply(divisor[j], factor)
            }
            rest
        }
        val codewords = IntArray(blocks * (dataLength + parityLength)) { i ->
            if (i < data.size) data[i % blocks * dataLength + i / blocks]
            else parity[i % blocks][(i - data.size) / blocks]
        }
        return (0..7).map { mask -> matrix(version, mask, codewords) }.minBy(::penalty)
    }

    private fun multiply(a: Int, b: Int): Int {
        var value = a
        var multiplier = b
        var product = 0
        while (multiplier != 0) {
            if (multiplier and 1 != 0) product = product xor value
            value = (value shl 1) xor (if (value and 128 == 0) 0 else 0x11d)
            multiplier = multiplier ushr 1
        }
        return product
    }

    private fun matrix(version: Int, mask: Int, codewords: IntArray): Array<BooleanArray> {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val fixed = Array(size) { BooleanArray(size) }
        fun place(x: Int, y: Int, dark: Boolean) {
            if (x in 0 until size && y in 0 until size) {
                modules[y][x] = dark
                fixed[y][x] = true
            }
        }
        for (i in 0 until size) {
            place(6, i, i % 2 == 0)
            place(i, 6, i % 2 == 0)
        }
        for ((x, y) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
            for (dy in -4..4) for (dx in -4..4) {
                val distance = maxOf(abs(dx), abs(dy))
                place(x + dx, y + dy, distance != 2 && distance != 4)
            }
        }
        val centers = if (version == 5) intArrayOf(6, 30) else intArrayOf(6, 24, 42)
        for (y in centers) for (x in centers) {
            if ((x == 6 && (y == 6 || y == centers.last())) || (y == 6 && x == centers.last())) continue
            for (dy in -2..2) for (dx in -2..2) place(x + dx, y + dy, maxOf(abs(dx), abs(dy)) != 1)
        }
        fun Int.dark(i: Int) = (this ushr i) and 1 != 0
        fun bch(data: Int, shift: Int, generator: Int): Int {
            var remainder = data shl shift
            for (i in 31 - Integer.numberOfLeadingZeros(remainder) downTo shift) {
                if (remainder.dark(i)) remainder = remainder xor (generator shl (i - shift))
            }
            return (data shl shift) or remainder
        }
        val format = bch(8 or mask, 10, 0x537) xor 0x5412 // L correction level
        for (i in 0..5) place(8, i, format.dark(i))
        place(8, 7, format.dark(6))
        place(8, 8, format.dark(7))
        place(7, 8, format.dark(8))
        for (i in 9..14) place(14 - i, 8, format.dark(i))
        for (i in 0..7) place(size - 1 - i, 8, format.dark(i))
        for (i in 8..14) place(8, size - 15 + i, format.dark(i))
        place(8, size - 8, true)
        if (version >= 7) {
            val info = bch(version, 12, 0x1f25)
            for (i in 0 until 18) {
                place(size - 11 + i % 3, i / 3, info.dark(i))
                place(i / 3, size - 11 + i % 3, info.dark(i))
            }
        }
        var offset = 0
        var right = size - 1
        var upward = true
        while (right > 0) {
            if (right == 6) right--
            for (vertical in 0 until size) {
                val y = if (upward) size - 1 - vertical else vertical
                for (x in right downTo right - 1) {
                    if (fixed[y][x]) continue
                    val value = codewords.getOrNull(offset / 8)?.dark(7 - offset % 8) ?: false
                    val flip = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (y / 2 + x / 3) % 2 == 0
                        5 -> (x * y) % 2 + (x * y) % 3 == 0
                        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                        else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
                    }
                    modules[y][x] = value xor flip
                    offset++
                }
            }
            upward = !upward
            right -= 2
        }
        return modules
    }

    private fun penalty(matrix: Array<BooleanArray>): Int {
        val n = matrix.size
        var cost = 0
        var dark = 0
        for (i in 0 until n) {
            for (vertical in listOf(false, true)) {
                var run = 0
                var last = false
                var bits = 0
                for (j in 0 until n) {
                    val cell = if (vertical) matrix[j][i] else matrix[i][j]
                    run = if (cell == last) run + 1 else 1
                    last = cell
                    if (run == 5) cost += 3 else if (run > 5) cost++
                    bits = ((bits shl 1) or if (cell) 1 else 0) and 0x7ff
                    if (j >= 10 && (bits == 0x5d0 || bits == 0x05d)) cost += 40
                    if (!vertical) {
                        if (cell) dark++
                        if (i > 0 && j > 0 && cell == matrix[i - 1][j] && cell == matrix[i][j - 1] &&
                            cell == matrix[i - 1][j - 1]) cost += 3
                    }
                }
            }
        }
        return cost + abs(dark * 20 - n * n * 10) / (n * n) * 10
    }
}
