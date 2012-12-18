package html.localstorage

native
public trait Storage {
    val long:Long

    fun key(index:Long)
    fun getItem(key: String): String? = noImpl
    fun setItem(key: String, value: Any?): Unit = noImpl
    fun removeItem(key: String): Unit = noImpl
}

native
public val localStorage: Storage = noImpl