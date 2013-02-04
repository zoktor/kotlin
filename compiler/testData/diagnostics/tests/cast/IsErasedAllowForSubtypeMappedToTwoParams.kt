trait A
trait B: A
trait C: A

trait Base<out T, out U>
trait Derived<S>: Base<S, S>

// Two covariant parameters. One parameter is not checked, B satisfies "out A" so it is allowed.
// a is Base<+B, +C> => if (a is Derived<?>) a is Derived<TT> where T is +B & +C => a is Derived<+A>
fun test(a: Base<B, C>) = a is Derived<out A>