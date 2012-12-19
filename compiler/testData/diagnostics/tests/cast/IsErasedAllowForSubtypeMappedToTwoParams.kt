trait A
trait B: A
trait C: A

trait Base<out T, out U>
trait Derived<S>: Base<S, S>

fun test(a: Base<B, C>) = a is Derived<out A>