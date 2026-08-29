package net.theresa.neogenesis.utils

import kotlin.math.abs

class MathUtils {
    companion object
    {

        private fun _gcd(a: Long, b: Long): Long {
            if (b == 0L) {
                return a
            }
            return _gcd(b, a % b)
        }

        fun gcd(a: Long, b: Long): Long {
            if (a == 0L) {
                return b
            }
            return (if (b < 0) -1 else 1) * _gcd(abs(a.toDouble()).toLong(), abs(b.toDouble()).toLong())
        }
    }
}