trait BaseOne<out T>
trait Derived<out R, S>: <!INCONSISTENT_TYPE_PARAMETER_VALUES!>BaseOne<R>, <!SUPERTYPE_APPEARS_TWICE!>BaseOne<S><!><!>

trait A
trait B: A

// t is BaseOne<B> => (B <: A, Derived<Covariant R, Invariant S>) if (t is Derived<?, ?>) => t is Derived<-B, B> => check (t is Derived(A, +A)) is OK
fun test(t: BaseOne<B>) = t is Derived<A, out A>