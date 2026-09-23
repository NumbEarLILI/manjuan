package com.numbear.manjuan.core

object NaturalSort : Comparator<String> {
    private val parts = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val left = parts.findAll(a.lowercase()).map { it.value }.toList()
        val right = parts.findAll(b.lowercase()).map { it.value }.toList()
        val count = minOf(left.size, right.size)
        for (index in 0 until count) {
            val x = left[index]
            val y = right[index]
            val xn = x.toLongOrNull()
            val yn = y.toLongOrNull()
            val compared = if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }
}
