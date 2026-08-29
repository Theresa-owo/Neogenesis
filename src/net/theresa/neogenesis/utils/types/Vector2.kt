package net.theresa.neogenesis.utils.types

class Vector2(a: Double, b: Double) : Tuple2T<Double?>(a, b) {
    constructor(v: Double) : this(v, v)

    fun add(vec: Vector2): Vector2 {
        return Vector2(this.a!! + vec.a!!, this.b!! + vec.b!!)
    }

    fun sub(vec: Vector2): Vector2 {
        return Vector2(this.a!! - vec.a!!, this.b!! - vec.b!!)
    }

    fun mul(value: Double): Vector2 {
        return Vector2(this.a!! * value, this.b!! * value)
    }

    fun div(value: Double): Vector2 {
        return Vector2(this.a!! / value, this.b!! / value)
    }

    fun neg(): Vector2 {
        return this.mul(-1.0)
    }

    fun inv(): Vector2 {
        return Vector2(1.0 / this.a!!, 1.0 / this.b!!)
    }

    fun to3(z: Double): Vector3 {
        return Vector3(this.a, this.b, z)
    }

    override fun toString(): String {
        return java.lang.String.format("Vector2[X=%s,Y=%s]", this.a, this.b)
    }
}