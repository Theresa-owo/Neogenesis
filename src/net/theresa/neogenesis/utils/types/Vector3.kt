package net.theresa.neogenesis.utils.types

class Vector3(a: Double?, b: Double?, c: Double?) : Tuple3T<Double?>(a, b, c) {
    constructor(v: Double) : this(v, v, v)

    fun to2(): Vector2 {
        return Vector2(a!!, b!!)
    }

    override fun toString(): String {
        return java.lang.String.format("Vector3[X=%s,Y=%s,Z=%s]", this.a, this.b, this.c)
    }
}
