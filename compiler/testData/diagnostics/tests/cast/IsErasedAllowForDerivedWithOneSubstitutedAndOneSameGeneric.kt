open class BaseTwo<A, B>
open class DerivedWithOne<D>: BaseTwo<D, String>()

fun <T, U> testing(a: BaseTwo<T, U>) = a is DerivedWithOne<T>
