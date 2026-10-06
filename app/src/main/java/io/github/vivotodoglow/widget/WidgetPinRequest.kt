package io.github.vivotodoglow.widget

/** Accepted means the launcher received a request, not that a widget was installed. */
object WidgetPinRequest {
    enum class Result { REQUESTED, MANUAL }
    fun request(supported: () -> Boolean, pin: () -> Boolean): Result = try {
        if (supported() && pin()) Result.REQUESTED else Result.MANUAL
    } catch (_: RuntimeException) {
        Result.MANUAL
    }
}
