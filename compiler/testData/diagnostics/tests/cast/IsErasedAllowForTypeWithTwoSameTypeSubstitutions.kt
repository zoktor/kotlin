open class BaseMulti<out A, B>
class SomeMultiDerived<out D>: BaseMulti<D, Any>()

fun someDerived(t: BaseMulti<String, String>) = t is SomeMultiDerived<Any>
