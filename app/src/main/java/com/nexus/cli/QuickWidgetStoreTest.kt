package com.nexus.cli

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QuickWidgetStoreTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultSizeIsS1X1() {
        assertEquals(WidgetBoxSize.S1X1, QuickWidgetStore.sizeOf(ctx, "terminal"))
    }

    @Test
    fun saveAndReadSize() {
        QuickWidgetStore.saveSize(ctx, "terminal", WidgetBoxSize.S2X2)
        assertEquals(WidgetBoxSize.S2X2, QuickWidgetStore.sizeOf(ctx, "terminal"))
    }

    @Test
    fun removeSize() {
        QuickWidgetStore.saveSize(ctx, "browser", WidgetBoxSize.S2X1)
        QuickWidgetStore.removeSize(ctx, "browser")
        assertEquals(WidgetBoxSize.S1X1, QuickWidgetStore.sizeOf(ctx, "browser"))
    }

    @Test
    fun invalidKeyReturnsNull() {
        assertNull(WidgetBoxSize.fromKey("99x99"))
    }
}