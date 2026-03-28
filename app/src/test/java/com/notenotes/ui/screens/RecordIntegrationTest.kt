package com.notenotes.ui.screens

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecordIntegrationTest {

    private lateinit var viewModel: RecordViewModel

    @Before
    fun setup() {
        val app = RuntimeEnvironment.getApplication() as Application
        viewModel = RecordViewModel(app)
    }

    @Test
    fun testInitialUiState() {
        assertEquals(RecordViewModel.UiState.IDLE, viewModel.uiState.value)
    }

    @Test
    fun testSyncBehavior() {
        val initialSync = viewModel.isSynced.value
        assertEquals(true, initialSync)

        viewModel.seekEditHead(0.5f)
        assertEquals(0L, viewModel.punchInPositionMs.value)
    }
}
