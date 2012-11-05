package foo

import java.lang.*

fun box():Boolean {
    try {
        if (true) {
            throw java.lang.NumberFormatException()
        }
    }
    catch (e:IllegalStateException) {
        return false;
    }
    catch (e:NumberFormatException) {
        return true;
    }

    return false;
}