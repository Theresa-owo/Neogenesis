package net.theresa.neogenesis.utils.types

class Position @JvmOverloads constructor(
    val anchorX: Double,
    val anchorY: Double,
    val offsetX: Double,
    val offsetY: Double,
    scale: Fraction = Fraction(1)
) {
    val scale: Fraction = scale

    constructor(pos: Position) : this(pos.anchorX, pos.anchorY, pos.offsetX, pos.offsetY, pos.scale)

    override fun toString(): String {
        return String.format(
            "Position[AX=%.2f,AY=%.2f,OX=%.2f,OY=%.2f,S=%s]", this.anchorX, this.anchorY, this.offsetX,
            this.offsetY, this.scale
        )
    }

    fun transform(diff: Vector2): Tuple2<Vector2, Fraction> {
        return Tuple2(
            Vector2(diff.a!! * this.anchorX + this.offsetX, diff.b!! * this.anchorY + this.offsetY),
            this.scale
        )
    }
}
