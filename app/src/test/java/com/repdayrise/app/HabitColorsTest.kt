package com.repdayrise.app

import androidx.compose.ui.graphics.Color
import com.repdayrise.app.ui.theme.HabitColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitColorsTest {
    @Test
    fun customColourRoundTripsThroughItsIndex() {
        val c = Color(0xFF12AB90)
        val index = HabitColors.custom(c)
        assertTrue(HabitColors.isCustom(index))
        assertEquals(c, HabitColors.of(index))
    }

    @Test
    fun customColourIgnoresAlphaAndNeverCollidesWithPresets() {
        val index = HabitColors.custom(Color(0x80FF0000))
        assertTrue(index < 0)
        assertEquals(Color(0xFFFF0000), HabitColors.of(index))
    }

    @Test
    fun presetIndicesStillMapToThePaletteAndWrap() {
        assertFalse(HabitColors.isCustom(3))
        assertEquals(HabitColors.palette[3], HabitColors.of(3))
        assertEquals(HabitColors.palette[0], HabitColors.of(HabitColors.palette.size))
    }
}
