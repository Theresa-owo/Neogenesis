package net.theresa.neogenesis.utils.types

import net.theresa.neogenesis.utils.MathUtils
import java.util.*


class Fraction @JvmOverloads constructor(num: Long = 0, den: Long = 1) : Comparable<Fraction?> {
    val num: Long
    val den: Long

    init {
        if (den == 0L) {
            throw RuntimeException(String.format("Invalid Fraction: %d/%d", num, den))
        }
        val divisor: Long = MathUtils.gcd(num, den)
        this.num = num / divisor
        this.den = den / divisor
    }

    fun add(frac: Fraction): Fraction {
        return Fraction(this.num * frac.den + this.den * frac.num, this.den * frac.den)
    }

    fun sub(frac: Fraction): Fraction {
        return Fraction(this.num * frac.den - this.den * frac.num, this.den * frac.den)
    }

    fun mul(frac: Fraction): Fraction {
        return Fraction(this.num * frac.num, this.den * frac.den)
    }

    fun div(frac: Fraction): Fraction {
        return Fraction(this.num * frac.den, this.den * frac.num)
    }

    fun neg(): Fraction {
        return Fraction(-this.num, this.den)
    }

    fun inv(): Fraction {
        return Fraction(this.den, this.num)
    }

    fun toDouble(): Double {
        return num.toDouble() / this.den
    }

    override fun toString(): String {
        return String.format("Fraction[%d/%d=%f]", this.num, this.den, this.toDouble())
    }

    override fun equals(`object`: Any?): Boolean {
        if (`object` !is Fraction) {
            return false
        }
        val frac = `object`
        return this.num == frac.num && this.den == frac.den
    }

    override fun hashCode(): Int {
        return Objects.hash(this.num, this.den)
    }

    override fun compareTo(other: Fraction?): Int {
        val a = this.num * other!!.den
        val b = this.den * other!!.num
        return java.lang.Long.compare(a, b)
    }

    fun appr(value: Double): Fraction {
        return this.mul(Fraction((value / this.toDouble()).toInt().toLong()))
    }

    companion object {

        fun max(vararg fracs: Fraction): Fraction? {
            if (fracs.size == 0) {
                return null
            }
            var result = fracs[0]
            for (i in 1..<fracs.size) {
                if (result.compareTo(fracs[i]) < 0) {
                    result = fracs[i]
                }
            }
            return result
        }

        fun min(vararg fracs: Fraction): Fraction? {
            if (fracs.size == 0) {
                return null
            }
            var result = fracs[0]
            for (i in 1..<fracs.size) {
                if (result.compareTo(fracs[i]) > 0) {
                    result = fracs[i]
                }
            }
            return result
        }
    }
}
