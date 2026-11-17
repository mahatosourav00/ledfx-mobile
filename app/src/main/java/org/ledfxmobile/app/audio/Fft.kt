package org.ledfxmobile.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 FFT for a fixed power-of-two size. */
class Fft(val size: Int) {
    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)
    private val bitReverse = IntArray(size)

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
        for (i in 0 until size / 2) {
            cosTable[i] = cos(2.0 * PI * i / size).toFloat()
            sinTable[i] = -sin(2.0 * PI * i / size).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            bitReverse[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until size) {
            val j = bitReverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val l = j + half
                    val tr = wr * re[l] - wi * im[l]
                    val ti = wr * im[l] + wi * re[l]
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += len
            }
            len *= 2
        }
    }
}
