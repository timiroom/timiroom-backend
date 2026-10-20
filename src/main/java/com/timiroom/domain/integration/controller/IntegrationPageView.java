package com.timiroom.domain.integration.controller;

import org.springframework.web.util.HtmlUtils;

/** Shared presentation for the standalone integration login and consent pages. */
final class IntegrationPageView {
    private IntegrationPageView() {}

    static String start(String title, String pageClass) {
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>" + HtmlUtils.htmlEscape(title) + " · Timiroom</title>"
            + STYLE + "</head><body><main class=\"" + pageClass + "\">"
            + "<div class=\"brand\"><span class=\"brand-mark\" aria-hidden=\"true\">T</span>Timiroom</div>"
            + "<h1>" + HtmlUtils.htmlEscape(title) + "</h1>";
    }

    static String end() { return "</main></body></html>"; }

    private static final String STYLE = """
        <style>
        :root{color-scheme:light;--bg:#f7f6f3;--surface:#fff;--border:#e4e2db;--border-2:#d0cec6;--text-1:#1a1916;--text-2:#6b6960}
        *{box-sizing:border-box}
        body{margin:0;padding:48px 24px 64px;background:var(--bg);color:var(--text-1);font-family:'Pretendard','Noto Sans KR',-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;font-size:14px;line-height:1.7}
        main{width:100%;max-width:632px;margin:0 auto;padding:28px;background:var(--surface);border:1px solid var(--border);border-radius:16px}
        main.login{max-width:480px;margin-top:6vh}
        .brand{display:flex;align-items:center;gap:9px;font-size:14px;font-weight:700;margin-bottom:28px}
        .brand-mark{display:inline-flex;align-items:center;justify-content:center;width:28px;height:28px;border-radius:8px;background:var(--text-1);color:var(--surface);font-size:16px;font-weight:800}
        h1{font-size:24px;line-height:1.35;letter-spacing:-.03em;margin:0 0 12px;font-weight:800}
        p{margin:0 0 24px;color:var(--text-2);overflow-wrap:anywhere}
        strong{font-weight:600;color:var(--text-1)}
        fieldset{min-width:0;margin:24px 0;padding:16px;border:1px solid var(--border);border-radius:12px}
        legend{padding:0 6px;font-size:12px;font-weight:700;color:var(--text-2)}
        label{font-size:13px;font-weight:600}
        .field-label{display:block;margin-bottom:8px}
        select{display:block;width:100%;height:144px;padding:8px;border:1px solid var(--border-2);border-radius:8px;background:var(--surface);color:var(--text-1);font:inherit;accent-color:var(--text-1)}
        option{padding:8px;border-radius:4px;overflow-wrap:anywhere}
        small{display:block;margin-top:8px;font-size:12px;color:var(--text-2)}
        .permission{display:flex;align-items:flex-start;gap:10px;padding:12px 0;line-height:1.6;font-weight:400;cursor:pointer}
        .permission+.permission{border-top:1px solid var(--border)}
        input[type=checkbox]{flex-shrink:0;width:16px;height:16px;margin:3px 0 0;accent-color:var(--text-1);cursor:pointer}
        .notice{padding:14px 16px;border-radius:8px;background:var(--bg);font-size:12px;margin-bottom:24px}
        .actions{display:flex;gap:10px;flex-wrap:wrap}
        button,.provider{display:inline-flex;align-items:center;justify-content:center;gap:10px;min-height:44px;padding:11px 18px;border:1px solid var(--border-2);border-radius:8px;background:var(--surface);color:var(--text-2);font:inherit;font-size:13px;font-weight:600;text-decoration:none;cursor:pointer}
        button.primary{flex:1;background:var(--text-1);border-color:var(--text-1);color:var(--surface)}
        button:hover,.provider:hover{background:var(--bg);color:var(--text-1)}
        button.primary:hover{background:#2b2a25;color:var(--surface)}
        .providers{display:grid;gap:12px}
        .provider{width:100%;min-height:48px;color:var(--text-1)}
        .login small{margin-top:24px;text-align:center}
        :focus-visible{outline:2px solid var(--text-1);outline-offset:3px}
        @media(max-width:480px){body{padding:24px 16px 40px}main{padding:24px 20px}main.login{margin-top:16px}h1{font-size:22px}fieldset{padding:12px}select{font-size:16px}.actions{flex-direction:column}.actions button{width:100%}.permission{min-height:44px}}
        </style>
        """;
}
