package io.embodit.jugglecoach.ui.jc.scene

/**
 * Ids and dp sizes of every attachment panel (design `panels`); the Compose side declares exactly these, once.
 *
 * The design has one standing panel that swaps its content (control / welcome / picker / menu) and grows downwards from
 * its top edge. A pooled panel draws black wherever its glass does not cover it (factory finding on access-check), so
 * each content is its own pre-built panel of its own height, shown alone at the same top edge: the user sees one panel
 * that changes.
 */
object PanelSpec {
    const val CONTROL = "controlPanel"
    const val WELCOME = "welcomePage"
    const val PICKER = "coursePicker"
    const val MENU = "moreMenu"
    const val DIALOG = "blockedDialog"

    /**
     * 440 wide (design 400): the longest English row, "Three-scarf cascade" + counter chip + sound switch + More, needs
     * 408 dp inside the padding. At 0.9 m and 42 degrees left the inner edge moves 1.3 degrees in (-33.1 degrees), still
     * 1.4 degrees clear of the scarf box (-31.7). Recorded in deviations.
     */
    val CONTROL_SIZE = 440f to 252f
    val WELCOME_SIZE = 440f to 300f

    /** 480 x 560 (design 400 x 456): cards wide enough for the English names, plus the first-time hint row. */
    val PICKER_SIZE = 480f to 560f

    /** 13 menu rows + the spike record row (design: 648, spike build 696). */
    val MENU_SIZE = 440f to 696f
    val DIALOG_SIZE = 360f to 200f

    /** Every panel is shown at 0.75 (1 dp = 0.6 mm): the control panel is 0.24 x 0.151 m. */
    const val SCALE = 0.75f

    val pool: List<String> = listOf(CONTROL, WELCOME, PICKER, MENU, DIALOG)

    fun size(id: String): Pair<Float, Float> =
        when (id) {
            CONTROL -> CONTROL_SIZE
            WELCOME -> WELCOME_SIZE
            PICKER -> PICKER_SIZE
            MENU -> MENU_SIZE
            else -> DIALOG_SIZE
        }

    /** Metres per dp as shown. */
    val M_PER_DP: Float
        get() = PanelRig.DP_M * SCALE

    // ---- layout of the dwell targets on the panels (dp from the panel's top-left; the Compose side uses the same numbers)
    const val PAD = 16f
    const val TITLE_H = 48f
    const val CARD_W = 220f
    const val CARD_H = 116f
    const val CARD_GAP = 8f
    const val CARDS_TOP = PAD + TITLE_H + 8f

    /** Centre (dp from top-left) of lesson card [index] 0..5: two columns, three rows. */
    fun cardCenterDp(index: Int): Pair<Float, Float> {
        val col = index % 2
        val row = index / 2
        return (PAD + col * (CARD_W + CARD_GAP) + CARD_W / 2f) to (CARDS_TOP + row * (CARD_H + CARD_GAP) + CARD_H / 2f)
    }

    /** Welcome page: the "OK, let's go" button, full width, 56 dp high, 228 dp from the top. */
    const val WELCOME_BUTTON_TOP = 228f
    const val WELCOME_BUTTON_H = 56f
}
