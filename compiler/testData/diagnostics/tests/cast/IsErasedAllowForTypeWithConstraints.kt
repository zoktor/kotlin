trait A
trait B: A
trait C: B

trait Base<out T>
trait Derived<out T>: Base<T>

fun <T: Derived<A>> test(a: Base<C>) where T: Derived<B> = a is T
