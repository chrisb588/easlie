package com.chrisb588.easlie

import com.chrisb588.easlie.ui.theme.Appearance
import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    @Test fun explicitChoicesIgnoreSystemChangesAndDefaultFollowsThem() {
        for (systemDark in listOf(false, true)) {
            assertFalse(Appearance.LIGHT.isDark(systemDark))
            assertTrue(Appearance.DARK.isDark(systemDark))
            assertEquals(systemDark, Appearance.SYSTEM.isDark(systemDark))
        }
    }
}
