package com.streamvault.feature.live.phone

import com.streamvault.core.ui.companion.companionPageBrand
import com.streamvault.core.ui.companion.companionPageStyles

/** Text is inserted by the script from localized Android resources after pairing. */
internal fun phoneOrderingPage(brand: String, error: String, initialCategory: Long?): String = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><style>${companionPageStyles()}
main{max-width:900px}.brand{margin-bottom:24px}.toolbar{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0}
.toolbar button,.toolbar select,.toolbar input{width:auto;min-width:0;flex:1 1 130px}.toolbar button{min-height:44px}
.list{padding:0;list-style:none}.item{display:flex;align-items:center;gap:12px;border-bottom:1px solid #343747;padding:10px 0}
.item input{width:22px;min-height:22px}.item span{flex:1;overflow-wrap:anywhere}.item button{width:auto;min-height:40px;padding:8px 12px}
.content{padding:20px}.message{min-height:24px;color:#c4b5f6}.hidden{display:none}.secondary{background:#252b40;color:#edf0fa;border-color:#454b60}
</style><script defer src="/app.js"></script></head><body data-category="${initialCategory ?: ""}"><main>
${companionPageBrand(brand)}
<header><h1 id="title"></h1><p id="message" class="message" role="status" data-error="${error.escapeAttribute()}"></p></header>
<section class="form-card"><div class="content"><div id="root"></div></div></section>
</main></body></html>
""".trimIndent()

private fun String.escapeAttribute() = replace("&", "&amp;").replace("\"", "&quot;")
    .replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
