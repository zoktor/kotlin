package js

public native open class Json() {
    public fun <T> get(propertyName: String): T = noImpl
    public fun set(propertyName: String, value: Any?): Unit = noImpl
}

//library("jsonFromTuples")
//public fun json(vararg pairs: Pair<String, Any?>): Json = js.noImpl

//library("jsonFromTuples")
//public fun json2(pairs: Array<Pair<String, Any?>>): Json = js.noImpl

library("jsonAddProperties")
public fun Json.add(other: Json): Json = noImpl

public native class JSON {
    public native class object {
        public fun stringify(o: Any?): String = noImpl

        public fun stringify(o: Any?, replacer: ((key: String, value: Any?)->Unit)?, space: Int? = null): String = noImpl

        public fun stringify(o: Any?, replacer: ((key: String, value: Any?)->Unit)?, space: String? = null): String = noImpl

        public fun stringify(o: Any?, replacer: Array<String>?, space: Int?): String = noImpl

        public fun stringify(o: Any?, replacer: Array<String>?, space: String? = null): String = noImpl

        public fun parse<T>(text: String, reviver: ((key: String, value: Any?)->Unit)? = null): T = noImpl
    }
}