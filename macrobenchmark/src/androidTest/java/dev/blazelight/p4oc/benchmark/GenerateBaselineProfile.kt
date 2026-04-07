package dev.blazelight.p4oc.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerateBaselineProfile {
    @get:Rule
    val baselineRule = BaselineProfileRule()

    @Test
    fun generate() = baselineRule.collect(
        packageName = "dev.blazelight.p4oc",
        stableIterations = 3,
        includeInStartupProfile = true,
        profileBlock = BaselineProfileMode.Require,
        startupMode = StartupMode.COLD
    ) {
        // Launch default activity and wait for first frame
        startActivityAndWait()
        // Minimal warm-up: let UI settle
        device.waitForIdle()
    }
}
