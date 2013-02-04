trait A
trait B: A
trait C: B

trait Base<out T>
trait Derived<out T>: Base<T>

// a is Base<C> => (C <: A, C <: A, Base<Covariant T>) => a is Base<A> & a is Base<B>
// T is Derived<A> & Derived<B>
// if (a is Derived<?>) => a is Derived<A> & a is Derived<B> => a is T
fun <T: Derived<A>> test(a: Base<C>) where T: Derived<B> = a is T
