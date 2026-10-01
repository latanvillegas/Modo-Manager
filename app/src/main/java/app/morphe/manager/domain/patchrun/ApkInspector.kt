package app.morphe.manager.domain.patchrun

import androidx.core.content.pm.PackageInfoCompat
import app.morphe.manager.patcher.split.SplitApkPreparer
import app.morphe.manager.util.PM
import app.morphe.manager.util.sha256OrNull
import java.io.File
import java.util.zip.ZipFile

class ApkInspectionException(message: String) : Exception(message)

class ApkInspector(private val pm: PM) {
    fun inspect(file: File): ApkDescriptor {
        if (!file.isFile || file.length() <= 0L) throw ApkInspectionException("APK input is missing or empty")
        val split = SplitApkPreparer.isSplitArchive(file)
        if (split) throw ApkInspectionException("Split archive must be normalized before manifest inspection")
        val info = pm.getPackageInfo(file) ?: throw ApkInspectionException("APK manifest could not be parsed")
        val app = info.applicationInfo ?: throw ApkInspectionException("APK application metadata is missing")
        val sha = file.sha256OrNull() ?: throw ApkInspectionException("APK SHA-256 could not be calculated")
        return ApkDescriptor(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            abis = readAbis(file),
            minSdk = app.minSdkVersion,
            targetSdk = app.targetSdkVersion,
            containerType = ApkContainerType.UNIVERSAL_APK,
            sizeBytes = file.length(),
            sha256 = sha,
        )
    }

    fun integrity(file: File, expectedSha256: String? = null): IntegrityCheck {
        val sha = file.sha256OrNull()
        val readable = file.isFile && file.length() > 0L && runCatching { ZipFile(file).use { it.entries().hasMoreElements() } }.getOrDefault(false)
        return IntegrityCheck(readable, sha, expectedSha256)
    }

    internal fun readAbis(file: File): Set<String> = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().mapNotNull { entry ->
                val name = entry.name
                if (!name.startsWith("lib/") || !name.endsWith(".so")) null
                else name.removePrefix("lib/").substringBefore('/').takeIf { it.isNotBlank() }
            }.toSet()
        }
    }.getOrDefault(emptySet())
}
