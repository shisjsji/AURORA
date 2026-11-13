package com.aurora.lsp

/**
 * Friendly names for the device codes the module reports.
 *
 * <p>The probe writes "product/device" (e.g. 25098PN5AC/pandora), and the community card has to
 * show something a person recognises. Matching is on either half, in declaration order, so a new
 * codename can be added without touching call sites. Unknown devices fall back to the product
 * code: an honest identifier beats a wrong name.
 */
object ModelNames {

    private val MAP = listOf(
        "25098PN5AC" to "17 Pro",
        "pandora" to "17 Pro",
        "2509FPN0BC" to "17 Pro Max",
        "popsicle" to "17 Pro Max",
        "2512BPNDAC" to "17 Ultra",
        "nezha" to "17 Ultra",
        "25113PN0EC" to "17",
        "pudding" to "17",
        "M531DA" to "17T",
        "chagall" to "17T",
        "M098FE" to "K100 Pro Max",
        "songyuan" to "K100 Pro Max",
        "25091RP04C" to "Pad 8 Pro",
        "piano" to "Pad 8 Pro",
        "25019PNF3C" to "15 Ultra",
        "xuanyuan" to "15 Ultra"
    )

    fun friendly(model: String): String {
        val raw = model.trim()
        if (raw.isEmpty()) return "未知机型"
        for ((key, label) in MAP) {
            if (raw.contains(key, ignoreCase = true)) return label
        }
        return raw.substringBefore('/').ifBlank { raw }
    }
}