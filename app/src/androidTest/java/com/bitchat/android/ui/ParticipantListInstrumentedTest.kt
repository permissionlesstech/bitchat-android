package com.bitchat.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ParticipantListInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun largeParticipantCardComposesViewportAndKeepsLastMemberReachable() {
        val composed = mutableSetOf<Int>()
        compose.setContent {
            LazyColumn {
                item {
                    AnimatedRowColumn(
                        items = (0 until 2_000).toList(), key = { it },
                        modifier = Modifier.testTag("fixture-participants")
                    ) { _, person ->
                        SideEffect { composed.add(person) }
                        Box(Modifier.height(48.dp)) { Text("fixture-person-$person") }
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue("Only a viewport of rows should compose", composed.size < 100) }
        compose.onNodeWithTag("fixture-participants").performScrollToIndex(1_999)
        compose.onNodeWithText("fixture-person-1999").assertIsDisplayed()
    }
}
