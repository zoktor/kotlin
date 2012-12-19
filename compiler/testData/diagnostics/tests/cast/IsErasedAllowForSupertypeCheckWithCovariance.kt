open class A
open class B: A()

open class Base<out T>
class SubBase: Base<B>()

fun test(f: SubBase) = f is Base<A>