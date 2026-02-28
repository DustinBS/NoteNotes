package com.notenotes.export

/**
 * Single source of truth for PDF export dimensions.
 *
 * All values are derived from US Letter at 96 DPI:
 *   8.5" × 96 = 816px total width
 *   0.5" margins each side → 7.5" × 96 = 720px content width
 *
 * Android PrintManager uses "mils" (1/1000 inch) for margins:
 *   0.5" = 500 mils
 */
object PdfConstants {
    /** Content width in CSS pixels (7.5 inches × 96dpi). */
    const val PRINT_WIDTH_PX = 720

    /** Margin in mils (1/1000 inch) for all four edges. */
    const val MARGIN_MILS = 500

    /** Print scale factor (no scaling). */
    const val PRINT_SCALE = 1.0f

    /** Viewport meta width string for prepareForPrint(). */
    const val VIEWPORT_WIDTH = "width=$PRINT_WIDTH_PX"

    /** Delay (ms) for alphaTab to re-render after restoreAfterPrint(). */
    const val RESTORE_RENDER_DELAY_MS = 2000L

    /** Additional delay (ms) after restore before showing WebView. */
    const val WEBVIEW_SHOW_DELAY_MS = 1500L
}
