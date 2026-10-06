package app.morphe.manager.patcher.patch

import android.os.Build
import android.os.Parcelable
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromDex
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.jar.JarFile

@Parcelize
data class PatchBundle(val patchesJar: String) : Parcelable {
    /**
     * The [java.util.jar.Manifest] of [patchesJar].
     */
    @IgnoredOnParcel
    private val manifest by lazy {
        try {
            JarFile(patchesJar).use { it.manifest }
        } catch (_: IOException) {
            null
        }
    }

    @IgnoredOnParcel
    val manifestAttributes by lazy {
        if (manifest != null)
            ManifestAttributes(
                name = readManifestAttribute("Name"),
                version = readManifestAttribute("Version"),
                description = readManifestAttribute("Description"),
                source = readManifestAttribute("Source"),
                author = readManifestAttribute("Author"),
                contact = readManifestAttribute("Contact"),
                website = readManifestAttribute("Website"),
                license = readManifestAttribute("License"),
                patcherVersion = readManifestAttribute("Patcher-Version"),
            ) else
            null
    }

    private fun readManifestAttribute(name: String) = manifest?.mainAttributes?.getValue(name)
        ?.takeIf { it.isNotBlank() } // If empty, set it to null instead.

    data class ManifestAttributes(
        val name: String?,
        val version: String?,
        val description: String?,
        val source: String?,
        val author: String?,
        val contact: String?,
        val website: String?,
        val license: String?,
        val patcherVersion: String?
    )


    enum class Capability { DEX, RESOURCE, NATIVE }

    data class NativePayload(
        val id: String,
        val apkEntry: String,
        val payloadEntry: String,
        val patchName: String,
        val originalSha256: String,
        val replacementSha256: String,
    )

    data class NativePayloadManifest(
        val capabilities: Set<Capability>,
        val payloads: List<NativePayload>,
    )

    /**
     * Optional native payload manifest carried inside the bundle. Legacy bundles simply return
     * null and continue to work unchanged.
     *
     * Format: META-INF/morphe/native-payloads.properties
     * capabilities=DEX,RESOURCE,NATIVE
     * payload.<id>.apkEntry=lib/<abi>/<name>.so
     * payload.<id>.entry=payload/native/<abi>/<name>.so
     * payload.<id>.patchName=<exact patch name>
     * payload.<id>.originalSha256=<64 hex>
     * payload.<id>.replacementSha256=<64 hex>
     */
    fun nativePayloadManifest(): NativePayloadManifest? = JarFile(patchesJar).use { jar ->
        val manifestEntry = jar.getJarEntry(NATIVE_PAYLOAD_MANIFEST) ?: return null
        val properties = java.util.Properties().apply {
            jar.getInputStream(manifestEntry).use(::load)
        }
        val capabilityNames = properties.getProperty("capabilities").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val capabilities = capabilityNames.map { raw ->
            runCatching { Capability.valueOf(raw.uppercase()) }
                .getOrElse { error("Unknown patch bundle capability: $raw") }
        }.toSet()

        val payloadKey = Regex("""payload\.([^.]+)\.(apkEntry|entry|patchName|originalSha256|replacementSha256)""")
        val ids = properties.stringPropertyNames()
            .mapNotNull { key -> payloadKey.matchEntire(key)?.groupValues?.get(1) }
            .distinct().sorted()

        val payloads = ids.map { id ->
            require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Unsafe native payload id: $id" }
            fun required(suffix: String) = properties.getProperty("payload.$id.$suffix")
                ?.takeIf { it.isNotBlank() }
                ?: error("Native payload $id is missing $suffix")
            val apkEntry = required("apkEntry")
            require(
                apkEntry.matches(Regex("""lib/[^/]+/[^/]+\.so""")) &&
                    !apkEntry.contains("..") &&
                    !apkEntry.startsWith("/")
            ) { "Unsafe native APK entry: $apkEntry" }
            val payloadEntry = required("entry")
            require(
                payloadEntry.matches(Regex("""payload/native/[^/]+/[^/]+\.so""")) &&
                    !payloadEntry.contains("..") &&
                    !payloadEntry.startsWith("/")
            ) { "Unsafe native payload entry: $payloadEntry" }
            NativePayload(
                id = id,
                apkEntry = apkEntry,
                payloadEntry = payloadEntry,
                patchName = required("patchName"),
                originalSha256 = requiredHash(required("originalSha256"), id, "originalSha256"),
                replacementSha256 = requiredHash(required("replacementSha256"), id, "replacementSha256"),
            )
        }
        if (payloads.isNotEmpty() && Capability.NATIVE !in capabilities) {
            error("Bundle declares native payloads without NATIVE capability")
        }
        NativePayloadManifest(capabilities, payloads)
    }

    /**
     * Extracts one declared payload after verifying its bytes. Paths are never trusted as
     * filesystem paths; the payload is read only as a JAR entry and copied to [directory].
     */
    fun extractNativePayload(payload: NativePayload, directory: File): File = JarFile(patchesJar).use { jar ->
        require(payload.payloadEntry.startsWith("payload/native/") && !payload.payloadEntry.contains("..")) {
            "Unsafe native payload entry: ${payload.payloadEntry}"
        }
        val entry = jar.getJarEntry(payload.payloadEntry)
            ?: error("Bundle is missing native payload ${payload.payloadEntry}")
        require(!entry.isDirectory) { "Native payload is a directory: ${payload.payloadEntry}" }

        directory.mkdirs()
        val target = File(directory, "${payload.id}.so")
        try {
            jar.getInputStream(entry).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            val actual = sha256(target)
            check(actual.equals(payload.replacementSha256, ignoreCase = true)) {
                "Native payload SHA-256 mismatch for ${payload.id}: expected " +
                    "${payload.replacementSha256}, got $actual"
            }
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun requiredHash(value: String, id: String, field: String): String {
        require(value.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Native payload $id has invalid $field"
        }
        return value.lowercase()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    object Loader {
        private fun loadBundle(bundle: PatchBundle): Collection<Patch<*>> {
            validateDexEntries(bundle.patchesJar)
            val patchFiles = runCatching {
                val jarFile = File(bundle.patchesJar)
                loadPatchesFromDex(
                    setOf(jarFile),
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
                        null
                    } else {
                        // Must pass in any directory that exists.
                        // Directory is ignored with Android 8.0+, but is required
                        // for Android 8.0 otherwise NPE occurs.
                        jarFile.parentFile
                    }
                ).byPatchesFile
            }.getOrElse { error ->
                throw IllegalStateException("Patch bundle is corrupted or incomplete", error)
            }
            val entry = patchFiles.entries.singleOrNull()
                ?: throw IllegalStateException("Unexpected patch bundle load result for ${bundle.patchesJar}")

            return entry.value
        }

        private fun metadataFor(bundle: PatchBundle) = loadBundle(bundle).map(::PatchInfo)

        fun metadata(bundles: Iterable<PatchBundle>) =
            bundles.associateWith(::metadataFor)

        fun metadata(bundle: PatchBundle) = metadataFor(bundle)

        fun patches(bundles: Iterable<PatchBundle>, packageName: String): Map<PatchBundle, Map<String, Patch<*>>> =
            bundles.associateWith { bundle ->
                // Filtered and keyed exactly like PatchBundleInfo.Global.forPackage, so a selection
                // made against the metadata resolves to the same patch here
                val relevant = loadBundle(bundle)
                    .map { patch -> PatchInfo(patch) to patch }
                    .filter { (info, _) -> info.compatibleWith(packageName) }

                uniqueNames(relevant.map { (info, _) -> info })
                    .mapIndexed { index, key -> key to relevant[index].second }
                    .toMap()
            }

        private fun validateDexEntries(jarPath: String) {
            JarFile(jarPath).use { jar ->
                val dexEntries = jar.entries().toList().filter { entry ->
                    val name = entry.name.lowercase()
                    name.endsWith(".dex")
                }
                if (dexEntries.isEmpty()) {
                    throw IllegalStateException("Patch bundle is missing dex entries")
                }
                val hasEmptyDex = dexEntries.any { it.size <= 0L }
                if (hasEmptyDex) {
                    throw IllegalStateException("Patch bundle contains empty dex entries")
                }
            }
        }
    }
    companion object {
        private const val NATIVE_PAYLOAD_MANIFEST = "META-INF/morphe/native-payloads.properties"
    }
}

