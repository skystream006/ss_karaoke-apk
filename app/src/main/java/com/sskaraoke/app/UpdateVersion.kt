package com.sskaraoke.app

/** Numeric comparison without integer overflow, including arbitrarily large components. */
internal class UpdateVersion private constructor(private val components: List<String>) :
    Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int {
        for (index in 0 until maxOf(components.size, other.components.size)) {
            val left = components.getOrElse(index) { "0" }
            val right = other.components.getOrElse(index) { "0" }
            val comparison = left.length.compareTo(right.length).takeIf { it != 0 }
                ?: left.compareTo(right)
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        fun parse(value: String?): UpdateVersion? {
            if (value == null) return null
            val parts = value.removePrefix("v").split('.')
            if (parts.any { it.isEmpty() || it.any { digit -> digit !in '0'..'9' } }) return null
            return UpdateVersion(parts.map {
                it.trimStart('0').ifEmpty { "0" }
            })
        }
    }
}
