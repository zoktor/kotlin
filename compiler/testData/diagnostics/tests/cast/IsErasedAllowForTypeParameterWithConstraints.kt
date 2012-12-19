trait A
trait B: A
trait C: B

trait Base<out T>
trait Derived<out T>: Base<T>

fun <T: B> test(a: Base<C>) where T: A = a is Derived<T>
