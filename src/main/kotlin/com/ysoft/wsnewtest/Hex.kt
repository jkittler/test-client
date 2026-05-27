package com.ysoft.wsnewtest

object Hex {
    /** Space-separated lower-case hex, truncated to [max] bytes for log readability. */
    fun preview(bytes: ByteArray, max: Int = 512): String {
        val shown = bytes.take(max).joinToString(" ") { "%02x".format(it) }
        return if (bytes.size > max) "$shown … (+${bytes.size - max} more)" else shown
    }
}

/** Mask a secret for logging: keep a short prefix, hide the rest. */
fun String?.masked(keep: Int = 6): String = when {
    this == null -> "<none>"
    isEmpty() -> "<empty>"
    length <= keep -> "*".repeat(length)
    else -> take(keep) + "…(${length} chars)"
}
