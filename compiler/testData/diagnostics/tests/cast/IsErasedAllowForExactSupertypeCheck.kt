open class Base<A>
class Some: Base<Int>()

fun f(a: Some) = a is Base<Int>