package foo

// test String template must have one or more entries.
public class Fe {
  fun open(method:String, url:String, async:Boolean = true, user:String = "", password:String = "") = true
}

fun box(): Boolean {
    val a = "abc"
    val b = "def"
    val message = "a = $a, b = $b"

    if (message != "a = abc, b = def") return false

    val v1 = null

    if ("returns null" != "returns $v1") return false


    return true && Fe().open("22", "33")
}