package app.morphe.manager.domain.patchrun

import java.io.File
import java.util.zip.ZipFile

data class ApkPostValidation(
    val archiveReadable: Boolean,
    val manifestReadable: Boolean,
    val dexEntries: Int,
    val dexHeadersValid: Boolean,
    val packageMatches: Boolean,
    val versionMatches: Boolean,
) {
    val valid get() = archiveReadable && manifestReadable && dexHeadersValid && packageMatches && versionMatches
}

/**
 * Structural validation after signing. It deliberately does not know any application profile:
 * identity is compared with the input descriptor and DEX is checked only when present.
 */
class FinalApkPostValidator(private val pm: app.morphe.manager.util.PM) {
    fun validate(file: File, input: ApkDescriptor): ApkPostValidation {
        val info = pm.getPackageInfo(file)
        var archiveReadable = false
        var dexEntries = 0
        var dexHeadersValid = true
        runCatching {
            ZipFile(file).use { zip ->
                archiveReadable = true
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.matches(Regex("""classes(\\d*)?\\.dex"""))) continue
                    dexEntries++
                    val header = ByteArray(4)
                    val count = zip.getInputStream(entry).use { it.read(header) }
                    if (count != 4 || !header.contentEquals(byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte()))) {
                        dexHeadersValid = false
                    }
                }
            }
        }.onFailure { archiveReadable = false }

        return ApkPostValidation(
            archiveReadable = archiveReadable,
            manifestReadable = info != null,
            dexEntries = dexEntries,
            dexHeadersValid = dexHeadersValid,
            packageMatches = info?.packageName == input.packageName,
            versionMatches = info?.versionName == input.versionName &&
                info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) } == input.versionCode,
        )
    }
}
