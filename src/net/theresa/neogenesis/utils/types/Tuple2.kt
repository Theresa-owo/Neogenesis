package net.theresa.neogenesis.utils.types

import java.util.*

open class Tuple2<TA, TB>(var a: TA, var b: TB) {

    override fun toString(): String {
        return String.format("Tuple2[A=%s,B=%s]", this.a, this.b)
    }

    override fun equals(var1: Any?): Boolean {
        if (var1 !is Tuple2<*, *>) {
            return false
        } else {
            val var2 = var1
            return this.a == var2.a && this.b == var2.b
        }
    }

    override fun hashCode(): Int {
        return Objects.hash(*arrayOf(this.a, this.b))
    }
}
