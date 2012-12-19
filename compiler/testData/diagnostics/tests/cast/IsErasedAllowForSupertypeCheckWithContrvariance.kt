open class A
open class B: A()

open class Base<in T>
class SubBase: Base<A>()

fun test(f: SubBase) = f is Base<B>