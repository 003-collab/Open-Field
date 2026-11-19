package com.openfield.survey

import com.openfield.core.device.ScriptRuntime
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Function as JsFunction

/**
 * Runs user-written JavaScript receiver drivers with Mozilla Rhino. Android cannot generate JVM bytecode at runtime, so
 * the interpreter is used (optimizationLevel = -1). That is fast enough for GNSS rates (a few KB/s).
 */
class RhinoRuntime : ScriptRuntime {
    private var global: ScriptableObject? = null

    private inline fun <T> inContext(block: (Context) -> T): T {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            return block(cx)
        } finally {
            Context.exit()
        }
    }

    override fun load(source: String) {
        inContext { cx ->
            val g = cx.initStandardObjects()
            cx.evaluateString(g, source, "driver.js", 1, null)
            global = g
        }
    }

    override fun hasFunction(name: String): Boolean {
        val g = global ?: return false
        return ScriptableObject.getProperty(g, name) is JsFunction
    }

    override fun call(function: String, vararg args: Any?): Any? {
        val g = global ?: return null
        return inContext { cx ->
            val f = ScriptableObject.getProperty(g, function) as? JsFunction ?: return@inContext null
            val jsArgs = Array<Any?>(args.size) { toJs(cx, g, args[it]) }
            fromJs(f.call(cx, g, g, jsArgs))
        }
    }

    override fun expose(name: String, functions: Map<String, (List<Any?>) -> Any?>) {
        val g = global ?: return
        inContext { cx ->
            val obj = cx.newObject(g)
            for ((fname, fn) in functions) {
                ScriptableObject.putProperty(obj, fname, object : BaseFunction() {
                    override fun call(c: Context, s: Scriptable, thisObj: Scriptable?, a: Array<Any?>): Any? {
                        val result = fn(a.map { fromJs(it) })
                        return toJs(c, s, result) ?: Undefined.instance
                    }
                })
            }
            ScriptableObject.putProperty(g, name, obj)
        }
    }

    override fun close() {
        global = null
    }

    private fun toJs(cx: Context, scope: Scriptable, v: Any?): Any? = when (v) {
        null -> null
        is Boolean, is String -> v
        is Number -> v.toDouble()
        is List<*> -> cx.newArray(scope, Array<Any?>(v.size) { toJs(cx, scope, v[it]) })
        is Map<*, *> -> {
            val o = cx.newObject(scope)
            for ((k, value) in v) ScriptableObject.putProperty(o, k.toString(), toJs(cx, scope, value))
            o
        }
        else -> v.toString()
    }

    /** Converts JS values to plain Kotlin values the core understands. */
    private fun fromJs(v: Any?): Any? = when (v) {
        null -> null
        is Undefined -> null
        is Boolean -> v
        is Number -> v
        is CharSequence -> v.toString()          // includes Rhino's ConsString from string concatenation
        is List<*> -> v.map { fromJs(it) }       // NativeArray implements List
        is Map<*, *> -> v.entries.associate { it.key.toString() to fromJs(it.value) }   // NativeObject implements Map
        else -> v.toString()
    }
}
