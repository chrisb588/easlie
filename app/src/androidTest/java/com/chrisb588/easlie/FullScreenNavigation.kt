package com.chrisb588.easlie

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick

internal fun ComposeTestRule.openBoardLibrary() {
    if (onAllNodesWithContentDescription("Home").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithContentDescription("Home").performClick()
    }
}

internal fun ComposeTestRule.openBoardActions(name: String) {
    openBoardLibrary()
    onNodeWithContentDescription("Board actions for $name").performClick()
}
