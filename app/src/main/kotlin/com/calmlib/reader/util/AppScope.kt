package com.calmlib.reader.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime coroutine scope for writes that MUST complete even while the
 * launching activity is being torn down. The reading-position save used
 * lifecycleScope, which cancels at onDestroy — quitting the app could cancel
 * the final save mid-write, which is why the position "sometimes" didn't stick.
 */
object AppScope {
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
