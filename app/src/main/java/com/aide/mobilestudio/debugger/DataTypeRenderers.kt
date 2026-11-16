package com.aide.mobilestudio.debugger

/**
 * Data Type Renderers - extension point for custom type renderers.
 * Register custom renderers for specific types to control how values are displayed.
 */
object DataTypeRenderers {

    private val renderers = mutableMapOf<String, (Any) -> String>()

    init {
        registerDefaults()
    }

    /** Render a variable using the appropriate renderer */
    fun render(typeName: String, value: Any): String {
        val renderer = renderers[typeName] ?: { v -> v.toString() }
        return renderer(value)
    }

    /** Register a custom renderer for a type pattern */
    fun register(typePattern: String, renderer: (Any) -> String) {
        renderers[typePattern] = renderer
    }

    private fun registerDefaults() {
        register("java.lang.String") { v -> "\"$v\"" }
        register("java.lang.Integer") { v -> v.toString() }
        register("java.lang.Long") { v -> v.toString() }
        register("java.lang.Boolean") { v -> v.toString() }
        register("java.lang.Double") { v -> v.toString() }
        register("java.lang.Float") { v -> v.toString() }
    }
}
