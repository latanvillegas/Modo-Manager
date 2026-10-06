package app.morphe.manager.patcher.util

import app.morphe.manager.patcher.logger.Logger
import app.morphe.manager.patcher.patch.PatchBundle
import java.io.File

/**
 * Applies bundle-declared native payloads to an already prepared mono-APK.
 *
 * This contains no application-specific rules: selection, target entry and both hashes come
 * exclusively from the selected patch bundles. It intentionally runs before Session so the
 * patcher's final APK writer can restore native-library alignment.
 */
object NativePayloadApplier {
    data class Selection(
        val bundle: PatchBundle,
        /** Selection keys as stored by Manager; these may be disambiguated per app. */
        val patchNames: Set<String>,
        /**
         * Selected Manager key -> bundle-declared patch name. Keeping the mapping intact lets us
         * detect two distinct selected keys that schema v1 would otherwise collapse to one native
         * payload owner. Legacy callers default to identity names.
         */
        val declaredPatchNamesByKey: Map<String, String> =
            patchNames.associateWith { it },
        /**
         * All Manager keys available from this bundle for the target app, not just selected ones.
         * When supplied, schema v1 native binding fails closed if a selected declared name belongs
         * to more than one available key because the manifest cannot identify which homonym owns it.
         */
        val availableDeclaredPatchNamesByKey: Map<String, String> = declaredPatchNamesByKey,
    ) {
        val declaredPatchNames: Set<String>
            get() = patchNames.mapTo(linkedSetOf()) { key ->
                declaredPatchNamesByKey[key] ?: key
            }
    }

    fun resolve(
        selections: Collection<Selection>,
        selectedAbi: String? = null,
    ): List<Pair<PatchBundle, PatchBundle.NativePayload>> {
        val selected = selections.flatMap { selection ->
            val payloads = selection.bundle.nativePayloadsFor(selection.declaredPatchNames)
            val nativeDeclaredNames = payloads.mapTo(hashSetOf()) { it.patchName }
            val ambiguousNativeNames = selection.availableDeclaredPatchNamesByKey.values
                .groupingBy { it }
                .eachCount()
                .filter { (declaredName, count) ->
                    count > 1 && declaredName in nativeDeclaredNames
                }
                .keys
            require(ambiguousNativeNames.isEmpty()) {
                "Selected native patches are ambiguous by declared name: " +
                    ambiguousNativeNames.sorted().joinToString(",")
            }
            payloads.map { payload -> selection.bundle to payload }
        }
        val duplicateTargets = selected.groupBy { (_, payload) -> payload.apkEntry }
            .filterValues { it.size > 1 }
            .keys
        require(duplicateTargets.isEmpty()) {
            "Selected native payloads conflict on APK entries: ${duplicateTargets.sorted().joinToString(",")}"
        }
        selectedAbi?.let { requestedAbi ->
            require(requestedAbi in Abi.NAMES) { "Unsupported selected ABI: $requestedAbi" }
            val mismatches = selected.mapNotNull { (_, payload) ->
                val payloadAbi = Abi.namedIn(payload.apkEntry) ?: return@mapNotNull null
                payload.takeIf { payloadAbi != requestedAbi }?.let {
                    "${it.id}:${it.apkEntry} ($payloadAbi)"
                }
            }
            require(mismatches.isEmpty()) {
                "Native payload ABI does not match selected ABI $requestedAbi: ${mismatches.joinToString(",")}"
            }
        }
        return selected
    }

    suspend fun apply(
        apkFile: File,
        selections: Collection<Selection>,
        workspace: File,
        logger: Logger,
        selectedAbi: String? = null,
    ): List<PatchBundle.NativePayload> {
        val selected = resolve(selections, selectedAbi)
        if (selected.isEmpty()) return emptyList()

        val payloadDir = workspace.resolve("native-payloads").also {
            check(it.mkdirs() || it.isDirectory) { "Could not create native payload workspace" }
        }
        selected.forEachIndexed { index, (bundle, payload) ->
            // Payload IDs are bundle-local. Keep extraction paths bundle-local too so two
            // independent bundles may safely use the same ID without sharing a filesystem target.
            val bundlePayloadDir = payloadDir.resolve("bundle-$index").also {
                check(it.mkdirs() || it.isDirectory) { "Could not create bundle payload workspace" }
            }
            val extracted = bundle.extractNativePayload(payload, bundlePayloadDir)
            NativeLibStripper.replaceNativeLibrary(
                apkFile,
                NativeLibStripper.NativeReplacement(
                    apkEntry = payload.apkEntry,
                    payload = extracted,
                    expectedOriginalSha256 = payload.originalSha256,
                    expectedReplacementSha256 = payload.replacementSha256,
                ),
                logger,
            )
        }
        return selected.map { it.second }
    }
}
