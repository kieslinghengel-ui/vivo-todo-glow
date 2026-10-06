package io.github.vivotodoglow.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetPinRequestTest {
    @Test fun unsupportedLauncherNeverRequestsPin() {
        assertEquals(WidgetPinRequest.Result.MANUAL, WidgetPinRequest.request({ false }) { error("must not call") })
    }
    @Test fun rejectedRequestOffersManualInstructions() {
        assertEquals(WidgetPinRequest.Result.MANUAL, WidgetPinRequest.request({ true }) { false })
    }
    @Test fun launcherExceptionOffersManualInstructions() {
        assertEquals(WidgetPinRequest.Result.MANUAL, WidgetPinRequest.request({ true }) { throw IllegalStateException() })
    }
    @Test fun acceptedRequestIsNotReportedAsInstalled() {
        assertEquals(WidgetPinRequest.Result.REQUESTED, WidgetPinRequest.request({ true }) { true })
    }
    @Test fun supportQueryExceptionOffersManualInstructions() {
        assertEquals(WidgetPinRequest.Result.MANUAL, WidgetPinRequest.request({ throw SecurityException() }) { true })
    }
}
