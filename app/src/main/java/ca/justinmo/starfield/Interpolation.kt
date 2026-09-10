package ca.justinmo.starfield

import kotlin.math.pow

object Interpolation {
    fun exp5In(a: Float): Float {
        val value = 2f
        val power = 5f
        val min = value.pow(-power)
        val scale = 1f / (1f - min)
        return (value.pow(power * (a - 1)) - min) * scale
    }
}
