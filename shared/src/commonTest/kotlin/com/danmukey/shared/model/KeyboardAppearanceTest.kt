package com.danmukey.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyboardAppearanceTest {
    @Test
    fun heightPresetCyclesAndInvalidStorageFallsBackToStandard() {
        assertEquals(KeyboardHeightPreset.Standard, KeyboardHeightPreset.Compact.next())
        assertEquals(KeyboardHeightPreset.Tall, KeyboardHeightPreset.Standard.next())
        assertEquals(KeyboardHeightPreset.Compact, KeyboardHeightPreset.Tall.next())
        assertEquals(KeyboardHeightPreset.Tall, KeyboardHeightPreset.fromStorage("Tall"))
        assertEquals(KeyboardHeightPreset.Standard, KeyboardHeightPreset.fromStorage("unknown"))
    }

    @Test
    fun columnPresetCyclesAndInvalidStorageFallsBackToDouble() {
        assertEquals(KeyboardColumnPreset.Double, KeyboardColumnPreset.Single.next())
        assertEquals(KeyboardColumnPreset.Triple, KeyboardColumnPreset.Double.next())
        assertEquals(KeyboardColumnPreset.Single, KeyboardColumnPreset.Triple.next())
        assertEquals(KeyboardColumnPreset.Triple, KeyboardColumnPreset.fromStorage("Triple"))
        assertEquals(KeyboardColumnPreset.Double, KeyboardColumnPreset.fromStorage(null))
    }
}
