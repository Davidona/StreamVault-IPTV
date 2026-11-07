package com.streamvault.core.ui.companion

/** Shared visual shell for local QR companion pages. Text is supplied by the owning feature. */
fun companionPageStyles(): String = """
        *{box-sizing:border-box}
        :root{color-scheme:dark;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#eff2f8;background:#0b0e14}
        body{margin:0;min-height:100vh;min-height:100svh;padding:48px 24px;background:radial-gradient(ellipse at 50% 0,rgba(101,85,211,.14),transparent 65%);-webkit-font-smoothing:antialiased}
        main{max-width:600px;margin:0 auto}
        .brand{display:flex;align-items:center;gap:11px;font-size:20px;font-weight:750;letter-spacing:-.6px;margin-bottom:46px}
        .brand-mark{display:grid;place-items:center;width:40px;height:40px;border-radius:12px;color:#d7cfff;background:linear-gradient(140deg,#7660d1,#493381);box-shadow:0 4px 20px #7960d122}
        .brand-mark svg{width:30px;height:30px}
        header{margin-bottom:28px}
        .eyebrow{display:inline-flex;align-items:center;gap:7px;font-size:11px;font-weight:700;letter-spacing:1.4px;text-transform:uppercase;color:#aba2d5;margin-bottom:15px}
        .status-dot{height:6px;width:6px;border-radius:50%;background:#aa97ef;box-shadow:0 0 10px #aa97ef66}
        h1{font-size:clamp(28px,5vw,36px);line-height:1.2;letter-spacing:-1.2px;margin:0 0 12px;font-weight:750;overflow-wrap:anywhere}
        p{font-size:14px;line-height:1.65;color:#9ca4b6;margin:0}
        .form-card{background:linear-gradient(145deg,#191c28,#131620);border:1px solid #2b2e3f;border-radius:22px;box-shadow:0 24px 80px #00000035;overflow:hidden}
        .source-section{padding:24px 28px;background:#ffffff02;border-bottom:1px solid #2b2e3f}
        .details-section{padding:8px 28px 6px}
        label{display:block;margin:19px 0 9px;font-size:12px;font-weight:600;color:#c4c8d5;letter-spacing:.2px}
        .source-section label{margin-top:0}
        input,select{display:block;width:100%;min-width:0;min-height:50px;padding:13px 14px;border-radius:10px;border:1px solid #333747;background:#0f121c;color:#f0f1f6;font:inherit;font-size:16px;transition:border-color .15s,box-shadow .15s}
        select{cursor:pointer;padding-inline-end:32px}
        input:hover,select:hover{border-color:#515368}
        input:focus-visible,select:focus-visible{outline:0;border-color:#ac96f3;box-shadow:0 0 0 3px #a68be51c}
        input:autofill{box-shadow:0 0 0 100px #191c28 inset}
        .credentials-row{display:grid;grid-template-columns:1fr 1fr;gap:16px}
        .credentials-row>div{min-width:0}
        .field-group{display:none}.field-group.active{display:block}
        .field-group p{font-size:12px;margin-top:10px}
        .submit-section{padding:24px 28px 28px}
        button{display:flex;align-items:center;justify-content:center;gap:14px;width:100%;min-height:52px;padding:14px 20px;border:1px solid #c4b5f6;border-radius:11px;background:linear-gradient(110deg,#c0acf6,#a98aeb);color:#1d1236;font:inherit;font-weight:750;font-size:15px;cursor:pointer;box-shadow:0 4px 20px #a98aeb16;transition:filter .15s,transform .15s}
        button:hover{filter:brightness(1.09)}button:active{transform:translateY(1px)}
        button:focus-visible{outline:2px solid #e0d6ff;outline-offset:4px}
        .button-arrow{font-size:21px;line-height:1}[dir=rtl] .button-arrow{transform:scaleX(-1)}
        .warning{display:flex;align-items:flex-start;gap:11px;border:1px solid #d4a76526;border-radius:12px;background:#d4a76507;padding:14px 16px;margin-top:20px}
        .warning p{font-size:12px;line-height:1.6;color:#b9a789}
        .warning-icon{display:grid;place-items:center;flex-shrink:0;width:17px;height:17px;border:1px solid #b9a789;border-radius:50%;font-size:11px;font-weight:700;color:#b9a789;margin-top:2px}
        footer{padding:20px 20px 0;color:#828a9e;font-size:11px;line-height:1.7;text-align:center}
        .result{text-align:center;padding:48px 28px}.result p{max-width:420px;margin:0 auto 14px}.result .result-detail{color:#e8eaf2;white-space:pre-line;overflow-wrap:anywhere}.result .result-detail:last-of-type{margin-bottom:20px}
        .result-icon{display:grid;place-items:center;width:64px;height:64px;margin:0 auto 24px;border-radius:50%;font-size:30px}
        .success{color:#9cd6bc;background:#9cd6bc12;border:1px solid #9cd6bc40}
        .pending{color:#c4b5f6;background:#c4b5f612;border:1px solid #c4b5f640}
        .failure{color:#e6ad9b;background:#e6ad9b12;border:1px solid #e6ad9b40}
        @media(max-width:480px){body{padding:28px 20px}.brand{margin-bottom:34px}.source-section{padding:20px}.details-section{padding:4px 20px}.submit-section{padding:22px 20px}.credentials-row{grid-template-columns:1fr;gap:0}.form-card{border-radius:18px}}
        @media(prefers-reduced-motion:reduce){*{transition:none!important}}
    """.trimIndent()

fun companionPageBrand(title: String): String = """
        <div class="brand">
          <span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 32 32" fill="none"><path d="M8 9h16v11H8z" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="m14 12 6 3.5-6 3.5v-7Z" fill="currentColor"/><path d="M12 24h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg></span>
          <span>${title.companionEscapeHtml()}</span>
        </div>
    """.trimIndent()

private fun String.companionEscapeHtml(): String = replace("&", "&amp;")
    .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
