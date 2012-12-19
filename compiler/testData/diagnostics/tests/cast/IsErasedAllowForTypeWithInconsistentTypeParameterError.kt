trait BaseOne<out T>
trait Derived<out R, S>: <!INCONSISTENT_TYPE_PARAMETER_VALUES!>BaseOne<R>, <!SUPERTYPE_APPEARS_TWICE!>BaseOne<S><!><!>

trait A
trait B: A

fun test(t: BaseOne<B>) = t is Derived<A, out A>