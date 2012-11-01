package js;

import java.util.*;
import java.lang.*;

native
public val noImpl: Nothing = throw Exception()

/** Provides [] access to maps */
native public fun <K, V> MutableMap<K, V>.set(key: K, value: V): Unit = noImpl

library("println")
public fun println(): Unit = js.noImpl
library("println")
public fun println(s: Any?): Unit = js.noImpl
library("print")
public fun print(s: Any?): Unit = js.noImpl
//TODO: consistent parseInt
native public fun parseInt(s: String, radix: Int = 10): Int = js.noImpl
library
public fun safeParseInt(s: String): Int? = js.noImpl
library
public fun safeParseDouble(s: String): Double? = js.noImpl