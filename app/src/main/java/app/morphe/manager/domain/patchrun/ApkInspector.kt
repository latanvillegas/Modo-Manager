package app.morphe.manager.domain.patchrun

import androidx.core.content.pm.PackageInfoCompat
import app.morphe.manager.patcher.patch.ApkArchitectureResolver
import app.morphe.manager.patcher.split.SplitApkInspector
import app.morphe.manager.patcher.split.SplitApkPreparer
import app.morphe.manager.util.PM
import app.morphe.manager.util.sha256OrNull
import java.io.File
import java.util.zip.ZipFile

class ApkInspectionException(message: String) : Exception(message)

class ApkInspector(private val pm: PM) {
    suspend fun inspect(source: File, workspace: File): ApkDescriptor {
        if (!source.isFile || source.length() <= 0L) throw ApkInspectionException("APK input is missing or empty")
        val split = SplitApkPreparer.isSplitArchive(source)
        val sha = source.sha256OrNull() ?: throw ApkInspectionException("APK SHA-256 could not be calculated")
        val abis = ApkArchitectureResolver.abiNames(source)

        return SplitApkInspector.withRepresentativeApk(source, workspace) { representative ->
            val info = pm.getPackageInfo(representative) ?: throw ApkInspectionException("APK manifest could not be parsed")
            val app = info.applicationInfo ?: throw ApkInspectionException("APK application metadata is missing")
            ApkDescriptor(
                packageName = info.packageName,
                versionName = info.versionName,
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                abis = abis,
                minSdk = app.minSdkVersion,
                targetSdk = app.targetSdkVersion,
                containerType = if (split) ApkContainerType.SPLIT_ARCHIVE else ApkContainerType.UNIVERSAL_APK,
                sizeBytes = source.length(),
                sha256 = sha,
                signingCertificateSha256 = pm.getApkFileSignatureHashes(representative),
            )
        }
    }

    fun integrity(file: File, expectedSha256: String? = null): IntegrityCheck {
        val sha = file.sha256OrNull()
        val readable = file.isFile && file.length() > 0L &&
            runCatching { ZipFile(file).use { it.entries().hasMoreElements() } }.getOrDefault(false)
        return IntegrityCheck(readable, sha, expectedSha256)
    }
}
