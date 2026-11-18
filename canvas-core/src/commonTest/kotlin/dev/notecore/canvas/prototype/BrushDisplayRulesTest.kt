package dev.notecore.canvas.prototype

import kotlin.test.*

class BrushDisplayRulesTest {
    private fun brush(kind: BrushKind, argb: Int) = BrushSpec(kind, 1, 4.0, argb)

    @Test fun monolineAndPressureKeepCanonicalColor() {
        assertEquals(0xff253247.toInt(), BrushDisplayRules.displayArgb(brush(BrushKind.MONOLINE, 0xff253247.toInt())))
        assertEquals(0xff253247.toInt(), BrushDisplayRules.displayArgb(brush(BrushKind.PRESSURE, 0xff253247.toInt())))
    }

    @Test fun highlighterScalesOnlyAlphaChannel() {
        val displayed = BrushDisplayRules.displayArgb(brush(BrushKind.HIGHLIGHTER, 0xff253247.toInt()))
        assertEquals(0x7f253247, displayed)
        assertEquals(0x253247, displayed and 0x00ffffff)
    }

    @Test fun highlighterMultipliesExistingAlpha() {
        assertEquals(0x40253247, BrushDisplayRules.displayArgb(brush(BrushKind.HIGHLIGHTER, 0x80253247.toInt())))
        assertEquals(0x00253247, BrushDisplayRules.displayArgb(brush(BrushKind.HIGHLIGHTER, 0x00253247)))
    }
}
