package net.theresa.neogenesis.utils.types

import java.util.*

open class Tuple3<TA, TB, TC>(val a: TA, val b: TB, val c: TC) {
    fun a(): TA {
        return this.a
    }

    fun b(): TB {
        return this.b
    }

    fun c(): TC {
        return this.c
    }

    fun i(): TA {
        return this.a
    }

    fun j(): TB {
        return this.b
    }

    fun k(): TC {
        return this.c
    }

    fun p(): TA {
        return this.a
    }

    fun q(): TB {
        return this.b
    }

    fun r(): TC {
        return this.c
    }

    fun u(): TA {
        return this.a
    }

    fun v(): TB {
        return this.b
    }

    fun w(): TC {
        return this.c
    }

    fun x(): TA {
        return this.a
    }

    fun y(): TB {
        return this.b
    }

    fun z(): TC {
        return this.c
    }

    override fun toString(): String {
        return String.format("Tuple3[A=%s,B=%s,C=%s]", this.a, this.b, this.c)
    }

    override fun equals(`object`: Any?): Boolean {
        if (`object` !is Tuple3<*, *, *>) {
            return false
        }
        val tuple = `object` as Tuple3<TA, TB, TC>
        return this.a == tuple.a && this.b == tuple.b && this.c == tuple.c
    }

    override fun hashCode(): Int {
        return Objects.hash(this.a, this.b, this.c)
    }
}