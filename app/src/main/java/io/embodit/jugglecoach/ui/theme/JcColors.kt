package io.embodit.jugglecoach.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design package `palette`). This is the only file with colour literals. Four
 * `ColorScheme` roles are fixed here and injected into `PicoTheme` by [JuggleTheme]; the others inherit SpatialUI
 * Vibrant. The brand tokens are used by the 3D content (paths, scarves, discs, rings) and the lesson-card drawings only.
 */
object JcColors {
    // ---- ColorScheme roles with a fixed design value
    val LabelPrimaryLight = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error
    val Alert = Color(0xFFFF8A1F) // design-style: fixed-figma-color alert
    val Passable = Color(0xFF7CF2A3) // design-style: fixed-figma-color passable
    val Interaction = Color(0xFFFFD24A) // design-style: fixed-figma-color interaction: dwell progress, main button, selection

    // ---- brand tokens (design `palette.brandTokens`)
    val ScarfCoral = Color(0xFFFF7A59) // design-style: fixed-figma-color scarfCoral: right hand
    val ScarfSky = Color(0xFF6FBFFF) // design-style: fixed-figma-color scarfSky: left hand
    val ScarfLemon = Color(0xFFFFD24A) // design-style: fixed-figma-color scarfLemon: third scarf
    val ArcWhite = Color(0xFFFFFFFF) // design-style: fixed-figma-color arcWhite: peak rings, count-in digits, gaze dot
    val Amber = Color(0xFFFFB84A) // design-style: fixed-figma-color amber: gaze dot on the transient head fallback
    val Charcoal = Color(0xFF2B2622) // design-style: fixed-figma-color charcoal: dwell disc face
    val Lake = Color(0xFF1A8A98) // design-style: fixed-figma-color lake: app icon ground

    /** Ink on yellow (main button text): charcoal reads 11.6 : 1 on #FFD24A. */
    val Ink = Charcoal

    val scarves = listOf(ScarfCoral, ScarfSky, ScarfLemon)
}
