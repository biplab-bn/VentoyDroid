package com.ventoydroid.app.ventoy

/**
 * Generates the ventoy.json control file that the bundled GRUB environment
 * reads from the VTOYEFI partition. Matches the default control flags the
 * official installer writes: no menu timeout and root-level ISO search.
 */
object VentoyJson {

    val default: String = """
        {
            "control": [
                { "VTOY_MENU_TIMEOUT": "0" },
                { "VTOY_DEFAULT_SEARCH_ROOT": "/" }
            ]
        }
    """.trimIndent()
}
