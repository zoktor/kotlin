package foo

class Foo {
    fun callSendCommand(scriptId: String) {
        sendCommand(object {
            object location {
                val scriptId = scriptId
            }
        })
    }
}

fun sendCommand(params:Any) {
}

fun box():Boolean {
    Foo().callSendCommand("foo")
    return true
}

