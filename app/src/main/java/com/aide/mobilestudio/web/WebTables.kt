package com.aide.mobilestudio.web

/**
 * Compact but real keyword/members tables backing [WebCompletionEngine].
 * Everything here is plain JVM data (no Android imports) so it is unit-testable.
 *
 * Ordering inside each table matters: it is preserved (as usefulness order) in the
 * completion results because the engine filters without re-sorting.
 */
object WebTables {

    // ═══════════════════════════════ HTML ═══════════════════════════════

    /** ~120 common HTML tags, most useful first. */
    val HTML_TAGS: List<String> = listOf(
        "html", "head", "title", "meta", "link", "style", "script", "body", "noscript", "template",
        "slot", "div", "span", "p", "a", "img", "br", "hr", "ul", "ol", "li", "dl", "dt", "dd",
        "menu", "h1", "h2", "h3", "h4", "h5", "h6",
        "header", "footer", "nav", "section", "article", "aside", "main", "address", "hgroup",
        "search", "form", "input", "button", "select", "option", "optgroup", "textarea", "label",
        "fieldset", "legend", "output", "progress", "meter", "datalist",
        "table", "caption", "thead", "tbody", "tfoot", "tr", "td", "th", "col", "colgroup",
        "video", "audio", "source", "track", "picture", "canvas", "map", "area", "figure",
        "figcaption", "embed", "object", "iframe", "details", "summary", "dialog",
        "strong", "em", "b", "i", "u", "s", "small", "mark", "del", "ins", "sub", "sup", "code",
        "kbd", "samp", "var", "pre", "q", "blockquote", "cite", "abbr", "dfn", "time", "data",
        "bdi", "bdo", "ruby", "rt", "rp", "wbr", "base",
        "svg", "path", "circle", "rect", "line", "polyline", "polygon", "ellipse", "text", "g",
        "defs", "use", "symbol"
    )

    /** Void elements: never closed, completed as `<tag ` instead of `<tag></tag>`. */
    val VOID_TAGS: Set<String> = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source",
        "track", "wbr"
    )

    val GLOBAL_ATTRS: List<String> = listOf(
        "id", "class", "style", "title", "lang", "dir", "hidden", "tabindex", "draggable",
        "contenteditable", "role", "spellcheck", "translate"
    )

    /** Attributes that complete as a bare word (no `=""`). */
    val BOOLEAN_ATTRS: Set<String> = setOf(
        "disabled", "checked", "required", "readonly", "multiple", "selected", "async", "defer",
        "autofocus", "hidden", "open", "loop", "muted", "controls", "autoplay", "playsinline",
        "novalidate", "reversed", "nomodule", "default", "download", "ismap", "declare"
    )

    /** Attributes specific to a tag, most useful first (globals are appended automatically). */
    val TAG_ATTRS: Map<String, List<String>> = mapOf(
        "a" to listOf("href", "target", "rel", "download", "hreflang", "type"),
        "img" to listOf("src", "alt", "width", "height", "loading", "srcset", "sizes", "decoding"),
        "input" to listOf(
            "type", "name", "value", "placeholder", "required", "disabled", "checked", "min",
            "max", "maxlength", "minlength", "pattern", "autocomplete", "readonly", "step",
            "multiple", "accept", "list", "size", "src", "alt", "form"
        ),
        "button" to listOf("type", "disabled", "form", "name", "value", "autofocus"),
        "form" to listOf("action", "method", "enctype", "target", "novalidate", "autocomplete", "name"),
        "select" to listOf("name", "multiple", "size", "required", "disabled", "autofocus", "form"),
        "option" to listOf("value", "selected", "disabled", "label"),
        "textarea" to listOf(
            "name", "rows", "cols", "placeholder", "required", "disabled", "readonly",
            "maxlength", "wrap", "form"
        ),
        "label" to listOf("for"),
        "link" to listOf("rel", "href", "type", "media", "integrity", "crossorigin", "as", "disabled"),
        "script" to listOf("src", "type", "async", "defer", "crossorigin", "integrity", "nomodule"),
        "style" to listOf("media", "type"),
        "meta" to listOf("name", "content", "charset", "http-equiv"),
        "video" to listOf(
            "src", "controls", "autoplay", "loop", "muted", "poster", "preload", "width",
            "height", "playsinline"
        ),
        "audio" to listOf("src", "controls", "autoplay", "loop", "muted", "preload"),
        "source" to listOf("src", "srcset", "type", "media", "sizes"),
        "iframe" to listOf(
            "src", "width", "height", "title", "loading", "allow", "sandbox", "referrerpolicy",
            "name", "allowfullscreen"
        ),
        "td" to listOf("colspan", "rowspan", "headers", "scope"),
        "th" to listOf("colspan", "rowspan", "headers", "scope", "abbr"),
        "ol" to listOf("start", "reversed", "type"),
        "li" to listOf("value"),
        "canvas" to listOf("width", "height"),
        "details" to listOf("open", "name"),
        "dialog" to listOf("open"),
        "track" to listOf("src", "kind", "srclang", "label", "default"),
        "area" to listOf("href", "shape", "coords", "alt", "target"),
        "base" to listOf("href", "target"),
        "blockquote" to listOf("cite"),
        "q" to listOf("cite"),
        "del" to listOf("cite", "datetime"),
        "ins" to listOf("cite", "datetime"),
        "time" to listOf("datetime"),
        "fieldset" to listOf("disabled", "form", "name"),
        "output" to listOf("for", "form", "name"),
        "object" to listOf("data", "type", "width", "height", "name", "form"),
        "progress" to listOf("value", "max"),
        "meter" to listOf("value", "min", "max", "low", "high", "optimum"),
        "svg" to listOf("width", "height", "viewBox", "fill", "xmlns"),
        "path" to listOf("d", "fill", "stroke", "stroke-width"),
        "html" to listOf("xmlns")
    )

    /** Value lists that depend on both the tag and the attribute. */
    val TAG_ATTR_VALUES: Map<String, Map<String, List<String>>> = mapOf(
        "input" to mapOf(
            "type" to listOf(
                "text", "password", "email", "number", "search", "tel", "url", "checkbox",
                "radio", "date", "time", "datetime-local", "month", "week", "range", "color",
                "file", "hidden", "submit", "reset", "button", "image"
            ),
            "autocomplete" to listOf(
                "on", "off", "name", "email", "username", "new-password", "current-password",
                "one-time-code", "tel", "url", "street-address", "postal-code", "cc-number"
            ),
            "accept" to listOf("image/*", "video/*", "audio/*")
        ),
        "script" to mapOf(
            "type" to listOf("text/javascript", "module", "importmap", "application/json", "speculationrules")
        ),
        "link" to mapOf(
            "rel" to listOf(
                "stylesheet", "icon", "preconnect", "preload", "prefetch", "dns-prefetch",
                "manifest", "alternate", "canonical", "license", "modulepreload"
            ),
            "as" to listOf("style", "script", "font", "image", "fetch", "document", "audio", "video"),
            "media" to listOf("screen", "print", "all", "(prefers-color-scheme: dark)")
        ),
        "a" to mapOf(
            "target" to listOf("_blank", "_self", "_parent", "_top"),
            "rel" to listOf("noreferrer", "noopener", "external", "nofollow", "tag", "license", "author", "bookmark")
        ),
        "form" to mapOf(
            "method" to listOf("get", "post", "dialog"),
            "enctype" to listOf(
                "application/x-www-form-urlencoded", "multipart/form-data", "text/plain"
            ),
            "target" to listOf("_blank", "_self", "_parent", "_top"),
            "autocomplete" to listOf("on", "off")
        ),
        "button" to mapOf("type" to listOf("submit", "reset", "button")),
        "meta" to mapOf(
            "name" to listOf("viewport", "description", "keywords", "author", "robots", "theme-color", "color-scheme"),
            "http-equiv" to listOf("content-type", "refresh", "x-ua-compatible", "content-security-policy")
        ),
        "img" to mapOf("loading" to listOf("lazy", "eager"), "decoding" to listOf("async", "sync", "auto")),
        "iframe" to mapOf(
            "loading" to listOf("lazy", "eager"),
            "sandbox" to listOf(
                "allow-scripts", "allow-same-origin", "allow-forms", "allow-popups", "allow-modals"
            )
        ),
        "video" to mapOf("preload" to listOf("none", "metadata", "auto")),
        "audio" to mapOf("preload" to listOf("none", "metadata", "auto")),
        "track" to mapOf("kind" to listOf("subtitles", "captions", "descriptions", "chapters", "metadata")),
        "ol" to mapOf("type" to listOf("1", "a", "A", "i", "I")),
        "th" to mapOf("scope" to listOf("col", "row", "colgroup", "rowgroup")),
        "area" to mapOf("shape" to listOf("rect", "circle", "poly", "default")),
        "style" to mapOf("media" to listOf("screen", "print", "all", "(prefers-color-scheme: dark)"))
    )

    /** Value lists keyed by attribute alone, used when the tag has no specific entry. */
    val ATTR_VALUES: Map<String, List<String>> = mapOf(
        "target" to listOf("_blank", "_self", "_parent", "_top"),
        "dir" to listOf("ltr", "rtl", "auto"),
        "rel" to listOf("stylesheet", "icon", "noopener", "noreferrer", "alternate", "canonical")
    )

    fun tagAttrs(tag: String): List<String> = TAG_ATTRS[tag] ?: emptyList()

    fun tagAttrValues(tag: String, attr: String): List<String> =
        TAG_ATTR_VALUES[tag]?.get(attr) ?: ATTR_VALUES[attr] ?: emptyList()

    // ═══════════════════════════════ CSS ═══════════════════════════════

    /** At-rules with their insertion text (label, insert, cursorOffsetFromEnd). */
    val AT_RULES: List<WebCompletion> = listOf(
        at("@media", "@media "),
        at("@import", "@import url(\"\");", 3),
        at("@keyframes", "@keyframes name {\n    \n}", 1),
        at("@font-face", "@font-face {\n    \n}", 1),
        at("@supports", "@supports "),
        at("@container", "@container "),
        at("@layer", "@layer "),
        at("@page", "@page "),
        at("@property", "@property "),
        at("@charset", "@charset \"UTF-8\";"),
        at("@scope", "@scope "),
        at("@counter-style", "@counter-style ")
    )

    val MEDIA_FEATURES: List<String> = listOf(
        "min-width", "max-width", "min-height", "max-height", "orientation", "aspect-ratio",
        "prefers-color-scheme", "prefers-reduced-motion", "prefers-contrast", "hover", "pointer",
        "resolution", "min-resolution", "max-resolution", "display-mode", "color-scheme"
    )

    /** Property names (selector position inside a block). Order = usefulness. */
    val CSS_PROPERTIES: List<String> = listOf(
        "display", "position", "top", "right", "bottom", "left", "inset", "z-index",
        "flex", "flex-direction", "flex-wrap", "flex-grow", "flex-shrink", "flex-basis",
        "justify-content", "align-items", "align-content", "align-self", "order", "gap",
        "row-gap", "column-gap",
        "grid-template-columns", "grid-template-rows", "grid-column", "grid-row", "grid-area",
        "grid-auto-flow", "grid-auto-rows", "grid-auto-columns", "place-items", "place-content",
        "width", "height", "min-width", "max-width", "min-height", "max-height",
        "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
        "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
        "border", "border-radius", "border-color", "border-width", "border-style",
        "color", "background", "background-color", "background-image", "background-size",
        "background-repeat", "background-position", "background-clip", "background-attachment",
        "font", "font-family", "font-size", "font-weight", "font-style", "line-height",
        "letter-spacing", "text-align", "text-transform", "text-decoration", "text-overflow",
        "text-shadow", "white-space", "word-break", "overflow-wrap", "vertical-align",
        "overflow", "overflow-x", "overflow-y", "visibility", "opacity", "cursor",
        "pointer-events", "user-select", "box-sizing", "box-shadow", "outline",
        "transform", "transform-origin", "translate", "rotate", "scale",
        "transition", "transition-property", "transition-duration", "transition-timing-function",
        "transition-delay",
        "animation", "animation-name", "animation-duration", "animation-timing-function",
        "animation-delay", "animation-iteration-count", "animation-direction", "animation-fill-mode",
        "animation-play-state",
        "object-fit", "object-position", "aspect-ratio", "float", "clear", "list-style",
        "list-style-type", "table-layout", "border-collapse", "caption-side", "empty-cells",
        "content", "columns", "column-count", "column-width", "resize", "direction", "filter",
        "mix-blend-mode", "isolation", "will-change", "touch-action", "scroll-behavior",
        "scroll-snap-align", "appearance", "accent-color", "caret-color", "color-scheme", "all"
    )

    /** Value keywords per property (~60 of the most common properties). */
    val CSS_PROPERTY_VALUES: Map<String, List<String>> = mapOf(
        "display" to listOf(
            "block", "inline", "inline-block", "flex", "inline-flex", "grid", "inline-grid",
            "none", "contents", "table", "flow-root", "list-item"
        ),
        "position" to listOf("static", "relative", "absolute", "fixed", "sticky"),
        "flex-direction" to listOf("row", "row-reverse", "column", "column-reverse"),
        "flex-wrap" to listOf("nowrap", "wrap", "wrap-reverse"),
        "flex" to listOf("none", "auto", "initial", "1"),
        "justify-content" to listOf(
            "flex-start", "flex-end", "center", "space-between", "space-around", "space-evenly",
            "stretch", "start", "end"
        ),
        "align-items" to listOf("flex-start", "flex-end", "center", "baseline", "stretch", "start", "end"),
        "align-content" to listOf(
            "flex-start", "flex-end", "center", "space-between", "space-around", "stretch", "start", "end"
        ),
        "align-self" to listOf("auto", "flex-start", "flex-end", "center", "baseline", "stretch"),
        "place-items" to listOf("center", "start", "end", "stretch"),
        "grid-auto-flow" to listOf("row", "column", "dense", "row dense", "column dense"),
        "grid-template-columns" to listOf("none", "auto", "auto-fill", "auto-fit"),
        "grid-template-rows" to listOf("none", "auto", "auto-fill", "auto-fit"),
        "text-align" to listOf("left", "right", "center", "justify", "start", "end"),
        "font-weight" to listOf(
            "normal", "bold", "bolder", "lighter",
            "100", "200", "300", "400", "500", "600", "700", "800", "900"
        ),
        "font-style" to listOf("normal", "italic", "oblique"),
        "font-family" to listOf(
            "system-ui", "sans-serif", "serif", "monospace", "cursive", "fantasy", "ui-sans-serif",
            "ui-monospace"
        ),
        "text-transform" to listOf("none", "uppercase", "lowercase", "capitalize"),
        "text-decoration" to listOf("none", "underline", "overline", "line-through", "wavy"),
        "text-overflow" to listOf("clip", "ellipsis"),
        "white-space" to listOf("normal", "nowrap", "pre", "pre-wrap", "pre-line", "break-spaces"),
        "word-break" to listOf("normal", "break-all", "keep-all", "break-word"),
        "overflow-wrap" to listOf("normal", "break-word", "anywhere"),
        "overflow" to listOf("visible", "hidden", "scroll", "auto", "clip"),
        "overflow-x" to listOf("visible", "hidden", "scroll", "auto", "clip"),
        "overflow-y" to listOf("visible", "hidden", "scroll", "auto", "clip"),
        "visibility" to listOf("visible", "hidden", "collapse"),
        "cursor" to listOf(
            "pointer", "default", "grab", "grabbing", "not-allowed", "text", "move", "wait",
            "crosshair", "progress", "help", "zoom-in", "zoom-out", "none"
        ),
        "pointer-events" to listOf("auto", "none"),
        "user-select" to listOf("auto", "none", "text", "all", "contain"),
        "box-sizing" to listOf("content-box", "border-box"),
        "border-style" to listOf("none", "solid", "dashed", "dotted", "double", "groove", "ridge", "inset", "outset"),
        "border-collapse" to listOf("collapse", "separate"),
        "background-size" to listOf("cover", "contain", "auto"),
        "background-repeat" to listOf("repeat", "no-repeat", "repeat-x", "repeat-y", "round", "space"),
        "background-position" to listOf("center", "top", "bottom", "left", "right"),
        "background-clip" to listOf("border-box", "padding-box", "content-box", "text"),
        "background-attachment" to listOf("scroll", "fixed", "local"),
        "object-fit" to listOf("fill", "contain", "cover", "none", "scale-down"),
        "vertical-align" to listOf(
            "baseline", "top", "middle", "bottom", "sub", "super", "text-top", "text-bottom"
        ),
        "list-style" to listOf("none", "disc", "circle", "square", "decimal"),
        "list-style-type" to listOf(
            "none", "disc", "circle", "square", "decimal", "decimal-leading-zero",
            "lower-roman", "upper-roman", "lower-alpha", "upper-alpha", "lower-greek"
        ),
        "caption-side" to listOf("top", "bottom"),
        "empty-cells" to listOf("show", "hide"),
        "table-layout" to listOf("auto", "fixed"),
        "transition-timing-function" to listOf(
            "ease", "linear", "ease-in", "ease-out", "ease-in-out", "step-start", "step-end"
        ),
        "animation-timing-function" to listOf(
            "ease", "linear", "ease-in", "ease-out", "ease-in-out", "step-start", "step-end"
        ),
        "animation-direction" to listOf("normal", "reverse", "alternate", "alternate-reverse"),
        "animation-fill-mode" to listOf("none", "forwards", "backwards", "both"),
        "animation-iteration-count" to listOf("infinite"),
        "animation-play-state" to listOf("running", "paused"),
        "transform-style" to listOf("flat", "preserve-3d"),
        "resize" to listOf("none", "both", "horizontal", "vertical", "block", "inline"),
        "direction" to listOf("ltr", "rtl"),
        "fill" to listOf("none", "currentColor"),
        "stroke" to listOf("none", "currentColor"),
        "touch-action" to listOf("auto", "none", "pan-x", "pan-y", "manipulation", "pinch-zoom"),
        "isolation" to listOf("auto", "isolate"),
        "mix-blend-mode" to listOf(
            "normal", "multiply", "screen", "overlay", "darken", "lighten", "color-dodge",
            "difference", "exclusion", "hue", "saturation", "color", "luminosity"
        ),
        "will-change" to listOf("auto", "transform", "opacity", "scroll-position", "contents"),
        "scroll-behavior" to listOf("auto", "smooth"),
        "appearance" to listOf("none", "auto"),
        "color-scheme" to listOf("normal", "light", "dark", "light dark"),
        "all" to listOf("initial", "inherit", "unset", "revert")
    )

    /** Units offered after a numeric value token. */
    val CSS_UNITS: List<String> = listOf(
        "px", "rem", "em", "%", "vh", "vw", "vmin", "vmax", "dvh", "svh", "lvh", "ch", "ex",
        "cm", "mm", "in", "pt", "pc", "deg", "rad", "grad", "turn", "s", "ms", "fr", "Hz", "kHz"
    )

    // ══════════════════════════ JavaScript / TypeScript ══════════════════════════

    val JS_KEYWORDS: List<WebCompletion> = listOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "false", "finally", "for",
        "function", "from", "get", "if", "import", "in", "instanceof", "let", "new", "null",
        "of", "return", "set", "static", "super", "switch", "this", "throw", "true", "try",
        "typeof", "undefined", "var", "void", "while", "yield"
    ).map { WebCompletion(it, it, "keyword", WebCompletion.Kind.KEYWORD) }

    val TS_TYPE_KEYWORDS: List<WebCompletion> = listOf(
        "interface", "type", "enum", "implements", "readonly", "public", "private", "protected",
        "abstract", "declare", "namespace", "keyof", "infer", "satisfies", "as", "asserts", "is",
        "any", "unknown", "never", "string", "number", "boolean", "object", "symbol", "bigint", "void"
    ).map { WebCompletion(it, it, "TS keyword", WebCompletion.Kind.KEYWORD) }

    /** Common globals: `label` is completed, `detail` hints where it comes from. */
    val JS_GLOBALS: List<WebCompletion> = buildList {
        fun g(label: String, detail: String, insert: String = label) =
            add(WebCompletion(label, insert, detail, WebCompletion.Kind.GLOBAL))
        g("console", "console")
        g("document", "DOM")
        g("window", "DOM")
        g("navigator", "Web API")
        g("location", "Web API")
        g("history", "Web API")
        g("localStorage", "storage")
        g("sessionStorage", "storage")
        g("Math", "built-in")
        g("JSON", "built-in")
        g("Object", "built-in")
        g("Array", "built-in")
        g("Promise", "built-in")
        g("Map", "built-in")
        g("Set", "built-in")
        g("WeakMap", "built-in")
        g("WeakSet", "built-in")
        g("Date", "built-in")
        g("RegExp", "built-in")
        g("Error", "built-in")
        g("TypeError", "built-in")
        g("SyntaxError", "built-in")
        g("Symbol", "built-in")
        g("BigInt", "built-in")
        g("Number", "built-in")
        g("String", "built-in")
        g("Boolean", "built-in")
        g("Reflect", "built-in")
        g("Intl", "built-in")
        g("globalThis", "built-in")
        g("fetch", "fetch API", "fetch()")
        g("setTimeout", "timer", "setTimeout()")
        g("setInterval", "timer", "setInterval()")
        g("clearTimeout", "timer", "clearTimeout()")
        g("clearInterval", "timer", "clearInterval()")
        g("requestAnimationFrame", "animation", "requestAnimationFrame()")
        g("cancelAnimationFrame", "animation", "cancelAnimationFrame()")
        g("queueMicrotask", "built-in", "queueMicrotask()")
        g("structuredClone", "built-in", "structuredClone()")
        g("alert", "dialog", "alert()")
        g("confirm", "dialog", "confirm()")
        g("prompt", "dialog", "prompt()")
        g("atob", "encoding", "atob()")
        g("btoa", "encoding", "btoa()")
        g("getComputedStyle", "DOM", "getComputedStyle()")
        g("matchMedia", "DOM", "matchMedia()")
        g("encodeURIComponent", "encoding", "encodeURIComponent()")
        g("decodeURIComponent", "encoding", "decodeURIComponent()")
        g("parseInt", "built-in", "parseInt()")
        g("parseFloat", "built-in", "parseFloat()")
        g("isNaN", "built-in", "isNaN()")
        g("AbortController", "fetch API", "new AbortController()")
        g("URL", "Web API", "new URL()")
        g("URLSearchParams", "Web API", "new URLSearchParams()")
        g("FormData", "Web API", "new FormData()")
        g("Headers", "fetch API", "new Headers()")
        g("Request", "fetch API", "new Request()")
        g("Response", "fetch API", "new Response()")
        g("Blob", "file API", "new Blob()")
        g("File", "file API")
        g("FileReader", "file API", "new FileReader()")
        g("WebSocket", "network", "new WebSocket()")
        g("Worker", "workers", "new Worker()")
        g("Event", "DOM", "new Event()")
        g("CustomEvent", "DOM", "new CustomEvent()")
        g("MutationObserver", "DOM", "new MutationObserver()")
        g("IntersectionObserver", "DOM", "new IntersectionObserver()")
        g("ResizeObserver", "DOM", "new ResizeObserver()")
        g("TextEncoder", "encoding", "new TextEncoder()")
        g("TextDecoder", "encoding", "new TextDecoder()")
        g("performance", "Web API")
        g("crypto", "Web API")
        g("screen", "DOM")
    }

    /** DOM helpers offered at top level; insertion spells out the receiver. */
    val JS_DOM_METHODS: List<WebCompletion> = listOf(
        dom("getElementById", "document.getElementById(\"\")", 1),
        dom("querySelector", "document.querySelector(\"\")", 1),
        dom("querySelectorAll", "document.querySelectorAll(\"\")", 1),
        dom("createElement", "document.createElement(\"\")", 1),
        dom("createTextNode", "document.createTextNode(\"\")", 1),
        dom("addEventListener", "addEventListener(\"\")", 1),
        dom("removeEventListener", "removeEventListener(\"\")", 1),
        dom("appendChild", "appendChild()"),
        dom("removeChild", "removeChild()"),
        dom("setAttribute", "setAttribute(\"\")", 1),
        dom("getAttribute", "getAttribute(\"\")", 1)
    )

    /** Frequent JS/TS snippets: (label, insert, cursorOffsetFromEnd). */
    val JS_SNIPPETS: List<WebCompletion> = listOf(
        snip("for", "for (let i = 0; i < array.length; i++) {\n    \n}", 1),
        snip("forof", "for (const item of items) {\n    \n}", 1),
        snip("fn", "function name(params) {\n    \n}", 1),
        snip("class", "class Name {\n    constructor() {\n        \n    }\n}", 1),
        snip("tryc", "try {\n    \n} catch (error) {\n    \n}", 1),
        snip("asyncfn", "async function name(params) {\n    \n}", 1),
        snip("imr", "import React from 'react';", 0, "React import"),
        snip("useState", "const [state, setState] = useState(null);", 0, "React hook"),
        snip("log", "console.log();", 1)
    )

    /** TS utility types: label keeps the type params, insert leaves the caret inside `<>`. */
    val TS_UTILITY_TYPES: List<WebCompletion> = listOf(
        utype("Partial<T>"), utype("Required<T>"), utype("Readonly<T>"), utype("Record<K, V>"),
        utype("Pick<T, K>"), utype("Omit<T, K>"), utype("Exclude<T, U>"), utype("Extract<T, U>"),
        utype("NonNullable<T>"), utype("ReturnType<T>"), utype("Parameters<T>"),
        utype("InstanceType<T>"), utype("Awaited<T>")
    )

    /** Members offered after `<receiver>.`, keyed by receiver identifier. */
    val JS_MEMBERS: Map<String, List<WebCompletion>> = mapOf(
        "console" to members("f",
            "log", "error", "warn", "info", "debug", "table", "dir", "trace", "group", "groupEnd",
            "time", "timeEnd", "count", "assert", "clear"
        ),
        "document" to buildList {
            fun fn(label: String, insert: String = "$label()", offset: Int = 0) =
                add(WebCompletion(label, insert, "document", WebCompletion.Kind.FUNCTION, offset))
            fn("getElementById", "getElementById(\"\")", 1)
            fn("querySelector", "querySelector(\"\")", 1)
            fn("querySelectorAll", "querySelectorAll(\"\")", 1)
            fn("createElement", "createElement(\"\")", 1)
            fn("createTextNode", "createTextNode(\"\")", 1)
            fn("createDocumentFragment")
            fn("addEventListener", "addEventListener(\"\")", 1)
            fn("removeEventListener", "removeEventListener(\"\")", 1)
            members("v", "body", "head", "title", "documentElement", "forms", "images", "links",
                "activeElement", "cookie", "readyState").forEach { add(it) }
        },
        "window" to buildList {
            fun fn(label: String, insert: String = "$label()", offset: Int = 0) =
                add(WebCompletion(label, insert, "window", WebCompletion.Kind.FUNCTION, offset))
            fn("addEventListener", "addEventListener(\"\")", 1)
            fn("removeEventListener", "removeEventListener(\"\")", 1)
            fn("requestAnimationFrame", "requestAnimationFrame()")
            fn("setTimeout", "setTimeout()")
            fn("setInterval", "setInterval()")
            fn("open", "open()")
            fn("close")
            fn("alert", "alert()")
            fn("confirm", "confirm()")
            fn("prompt", "prompt()")
            fn("scrollTo", "scrollTo()")
            fn("scrollBy", "scrollBy()")
            fn("getComputedStyle", "getComputedStyle()")
            fn("matchMedia", "matchMedia(\"\")", 1)
            members("v", "innerWidth", "innerHeight", "outerWidth", "outerHeight", "location",
                "navigator", "document", "devicePixelRatio", "origin").forEach { add(it) }
        },
        "Math" to members("f",
            "abs", "ceil", "floor", "round", "trunc", "sign", "max", "min", "pow", "sqrt", "cbrt",
            "hypot", "random", "log", "log2", "log10", "exp", "sin", "cos", "tan", "atan2", "clz32",
            "fround"
        ) + members("v", "PI", "E", "LN2", "LN10", "SQRT2"),
        "JSON" to members("f", "parse", "stringify", "raw"),
        "Object" to members("f",
            "keys", "values", "entries", "assign", "freeze", "isFrozen", "create",
            "defineProperty", "getPrototypeOf", "setPrototypeOf", "fromEntries", "is", "hasOwn",
            "seal", "groupBy"
        ),
        "Array" to members("f", "isArray", "from", "of", "fromAsync"),
        "Promise" to members("f",
            "all", "allSettled", "any", "race", "resolve", "reject", "withResolvers", "try"
        ),
        "Reflect" to members("f",
            "apply", "construct", "defineProperty", "deleteProperty", "get", "has", "ownKeys", "set"
        ),
        "Number" to members("f",
            "isInteger", "isFinite", "isNaN", "isSafeInteger", "parseFloat", "parseInt"
        ) + members("v",
            "MAX_SAFE_INTEGER", "MIN_SAFE_INTEGER", "EPSILON", "POSITIVE_INFINITY",
            "NEGATIVE_INFINITY", "NaN", "MAX_VALUE", "MIN_VALUE"
        ),
        "String" to members("f", "fromCharCode", "fromCodePoint", "raw"),
        "localStorage" to members("f", "getItem", "setItem", "removeItem", "key", "clear") +
            members("v", "length"),
        "sessionStorage" to members("f", "getItem", "setItem", "removeItem", "key", "clear") +
            members("v", "length"),
        "location" to buildList {
            members("v", "href", "host", "hostname", "pathname", "search", "hash", "origin",
                "protocol", "port", "searchParams").forEach { add(it) }
            fun fn(label: String) = add(WebCompletion(label, "$label()", "location", WebCompletion.Kind.FUNCTION))
            fn("reload"); fn("replace"); fn("assign"); fn("toString")
        },
        "navigator" to members("v",
            "userAgent", "language", "languages", "platform", "clipboard", "geolocation", "onLine",
            "mediaDevices", "serviceWorker", "hardwareConcurrency", "maxTouchPoints"
        ) + members("f", "vibrate", "sendBeacon", "canShare", "share"),
        "performance" to members("f", "now", "mark", "measure", "getEntriesByType", "toJSON") +
            members("v", "timeOrigin"),
        "crypto" to members("f", "randomUUID", "getRandomValues") + members("v", "subtle"),
        "URL" to members("f", "createObjectURL", "revokeObjectURL", "parse", "canParse"),
        "classList" to members("f", "add", "remove", "toggle", "contains", "item", "replace") +
            members("v", "value"),
        "style" to members("f", "setProperty", "getPropertyValue", "removeProperty") +
            members("v", "cssText", "length")
    )

    /** Members offered after a `document.querySelector(...)` / `getElementById(...)` chain. */
    val ELEMENT_MEMBERS: List<WebCompletion> = buildList {
        fun fn(label: String, insert: String = "$label()", offset: Int = 0) =
            add(WebCompletion(label, insert, "element", WebCompletion.Kind.FUNCTION, offset))
        fn("addEventListener", "addEventListener(\"\")", 1)
        fn("removeEventListener", "removeEventListener(\"\")", 1)
        fn("setAttribute", "setAttribute(\"\")", 1)
        fn("getAttribute", "getAttribute(\"\")", 1)
        fn("hasAttribute", "hasAttribute(\"\")", 1)
        fn("removeAttribute", "removeAttribute(\"\")", 1)
        fn("querySelector", "querySelector(\"\")", 1)
        fn("querySelectorAll", "querySelectorAll(\"\")", 1)
        fn("closest", "closest(\"\")", 1)
        fn("appendChild", "appendChild()")
        fn("removeChild", "removeChild()")
        fn("insertAdjacentHTML", "insertAdjacentHTML(\"\")", 1)
        fn("scrollIntoView", "scrollIntoView()")
        fn("getBoundingClientRect", "getBoundingClientRect()")
        fn("focus"); fn("blur"); fn("click"); fn("remove"); fn("toggleAttribute", "toggleAttribute(\"\")", 1)
        members("v", "classList", "style", "dataset", "children", "textContent", "innerHTML",
            "innerText", "value", "src", "href", "id", "tagName", "parentElement", "parentNode",
            "offsetWidth", "offsetHeight", "disabled", "checked", "hidden").forEach { add(it) }
    }

    // ═══════════════════════════ helpers ═══════════════════════════

    private fun at(label: String, insert: String, offset: Int = 0) =
        WebCompletion(label, insert, "at-rule", WebCompletion.Kind.KEYWORD, offset)

    private fun snip(label: String, insert: String, offset: Int, detail: String = "snippet") =
        WebCompletion(label, insert, detail, WebCompletion.Kind.SNIPPET, offset)

    private fun dom(label: String, insert: String, offset: Int = 0) =
        WebCompletion(label, insert, "DOM", WebCompletion.Kind.FUNCTION, offset)

    private fun utype(label: String): WebCompletion {
        val base = label.substringBefore('<')
        return WebCompletion(label, "$base<>", "utility type", WebCompletion.Kind.TYPE, 1)
    }

    private fun members(kindBadge: String, vararg names: String): List<WebCompletion> {
        val kind = when (kindBadge) {
            "f" -> WebCompletion.Kind.FUNCTION
            "v" -> WebCompletion.Kind.GLOBAL
            else -> WebCompletion.Kind.KEYWORD
        }
        return names.map { n ->
            WebCompletion(n, n, "", kind)
        }
    }
}
