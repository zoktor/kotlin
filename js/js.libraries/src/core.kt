package js

native
public val noImpl: Nothing = throw Exception()

/** Provides [] access to maps */
native public fun <K, V> MutableMap<K, V>.set(key: K, value: V): Unit = noImpl

native public fun println(): Unit = noImpl
native public fun println(s: Any?): Unit = noImpl
native public fun print(s: Any?): Unit = noImpl
//TODO: consistent parseInt
native public fun parseInt(s: String, radix: Int = 10): Int = noImpl
library
public fun safeParseInt(s: String): Int? = noImpl
library
public fun safeParseDouble(s: String): Double? = noImpl