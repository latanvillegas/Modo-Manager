/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.patch

import app.morphe.manager.patcher.logger.Logger
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.Option as PatchOption
import kotlin.reflect.KType

/**
 * Returns [value] as [type], or null when it cannot stand for one, since the patcher aborts the
 * run over a value of any other type. An unmodelled type is passed through untouched.
 */
fun coerceOptionValue(type: KType, value: Any?): Any? {
    if (value == null) return null

    if (type.classifier == List::class) {
        val elementType = type.arguments.firstOrNull()?.type ?: return value
        val elements = when (value) {
            is List<*>  -> value
            is Array<*> -> value.asList()
            // How the list editor and imported profiles carry a list
            is String   -> value.split(',').map(String::trim).filter(String::isNotEmpty)
            else        -> return null
        }

        return elements.map { element -> coerceOptionValue(elementType, element) ?: return null }
    }

    return when (type.classifier) {
        String::class  -> when (value) {
            is String             -> value
            is Number, is Boolean -> value.toString()
            else                  -> null
        }

        Boolean::class -> when (value) {
            is Boolean -> value
            is String  -> value.trim().lowercase().toBooleanStrictOrNull()
            else       -> null
        }

        Int::class     -> value.integralOrNull()?.takeIf { it in INT_RANGE }?.toInt()
        Long::class    -> value.integralOrNull()
        Float::class   -> value.decimalOrNull()?.toFloat()
        Double::class  -> value.decimalOrNull()
        else           -> value
    }
}

/**
 * Validates persisted option paths before any patch mutates the APK. A stale patch/option name
 * must not be silently ignored because that would make the run differ from the user's selection.
 */
fun Map<String, Patch<*>>.requirePatchOptionsAvailable(
    options: Map<String, Map<String, Any?>>,
) {
    options.forEach { (patchName, patchOptions) ->
        val patch = this[patchName]
            ?: throw IllegalArgumentException("Options reference unavailable patch \"$patchName\"")
        val missingOptions = patchOptions.keys - patch.options.keys
        require(missingOptions.isEmpty()) {
            "Patch \"$patchName\" no longer has options: ${missingOptions.sorted().joinToString(",")}"
        }
    }
}

/**
 * Applies [options] to the patches of one bundle, each value converted to the type its option
 * declares. Which patches [options] covers is decided before the run, not here.
 */
fun Map<String, Patch<*>>.applyPatchOptions(
    options: Map<String, Map<String, Any?>>,
    logger: Logger
) = options.forEach { (patchName, patchOptions) ->
    val patch = this[patchName] ?: return@forEach

    patchOptions.forEach setOption@{ (key, value) ->
        // Writing null would erase the default the patch falls back to
        if (value == null) return@setOption

        if (key !in patch.options) {
            return@setOption logger.warn("Patch \"$patchName\" does not have the option \"$key\"")
        }

        val option = patch.options[key]
        val coerced = coerceOptionValue(option.type, value) ?: return@setOption logger.warn(
            "Option \"$key\" of the \"$patchName\" patch does not accept \"$value\" as ${option.type}"
        )

        // A persisted dropdown value may outlive the bundle version that declared it.
        // The patcher's generic validator does not necessarily enforce membership in values,
        // so fail closed here before an obsolete preset can overwrite the current default.
        val declaredValues = option.values
        if (declaredValues != null && declaredValues.values.none { it == coerced }) {
            return@setOption logger.warn(
                "Option \"$key\" of the \"$patchName\" patch rejected stale or invalid value \"$coerced\""
            )
        }

        // PatchOption is star-projected after lookup by key, so invoking its validator
        // requires restoring the matching generic view for this already-coerced value.
        @Suppress("UNCHECKED_CAST")
        fun acceptsCoercedValue(): Boolean {
            val typedOption = option as PatchOption<Any?>
            return option.validator(typedOption, coerced)
        }
        if (!acceptsCoercedValue()) {
            return@setOption logger.warn(
                "Option \"$key\" of the \"$patchName\" patch rejected stale or invalid value \"$coerced\""
            )
        }

        patch.options[key] = coerced
    }
}

private val INT_RANGE = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()

/** The whole number [this] stands for, or null when it is none. */
private fun Any.integralOrNull(): Long? = when (this) {
    is Long                   -> this
    is Int, is Short, is Byte -> (this as Number).toLong()
    is Float, is Double       -> (this as Number).toDouble().toWholeOrNull()
    is String                 -> trim().let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toWholeOrNull() }
    else                      -> null
}

/** The decimal [this] stands for, or null when it is none. */
private fun Any.decimalOrNull(): Double? = when (this) {
    is Number -> toDouble()
    is String -> trim().toDoubleOrNull()
    else      -> null
}

/** Rejects fractions, NaN and infinities, which no integer option can hold. */
private fun Double.toWholeOrNull(): Long? = takeIf { it == it.toLong().toDouble() }?.toLong()
