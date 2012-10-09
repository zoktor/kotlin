package foo

abstract class B {
    abstract fun attachDebugger(tab: String)
}

class A : B() {
    // must be here - before open fun attachDebugger
    private fun attachDebugger(tabId: Int) {
    }

    fun attachDebugger() {
    }

    override fun attachDebugger(tab: String) {
    }


}

fun box(): Boolean {
    return true
}