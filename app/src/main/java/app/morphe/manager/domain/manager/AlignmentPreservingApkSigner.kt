package app.morphe.manager.domain.manager

import com.android.apksig.DefaultApkSignerEngine
import com.android.apksig.KeyConfig
import com.android.apksig.apk.ApkSigningBlockNotFoundException
import com.android.apksig.util.DataSources
import app.morphe.patcher.apk.ApkSigner
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import com.android.apksig.apk.ApkUtils as ApkSigUtils

/**
 * Signs modern APKs without rewriting their ZIP local entries.
 *
 * Android 7.0 (API 24) and newer verify APK Signature Scheme v2, so an APK whose declared
 * minSdk is at least 24 does not need a v1/JAR signature. In that case only the APK Signing
 * Block, central directory and EOCD are written; already aligned STORED native libraries keep
 * their exact data offsets.
 *
 * APKs that can run below API 24 retain the regular apksig path because those devices need v1.
 */
internal object AlignmentPreservingApkSigner {
    private const val V2_ONLY_MIN_SDK = 24

    fun sign(
        input: File,
        output: File,
        signerName: String,
        pair: ApkSigner.PrivateKeyCertificatePair,
    ) {
        val keyConfig = KeyConfig.Jca(pair.privateKey)
        val certificates = listOf(pair.certificate)
        val minSdk = RandomAccessFile(input, "r").use { file ->
            ApkSigUtils.getMinSdkVersionFromBinaryAndroidManifest(
                ApkSigUtils.getAndroidManifest(DataSources.asDataSource(file)),
            )
        }

        if (minSdk < V2_ONLY_MIN_SDK) {
            com.android.apksig.ApkSigner.Builder(
                listOf(
                    com.android.apksig.ApkSigner.SignerConfig.Builder(
                        signerName,
                        keyConfig,
                        certificates,
                    ).build(),
                ),
            ).setInputApk(input).setOutputApk(output).build().sign()
            return
        }

        if (input.canonicalFile != output.canonicalFile) {
            Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        signV2InPlace(output, signerName, keyConfig, certificates, minSdk)
    }

    private fun signV2InPlace(
        apkFile: File,
        signerName: String,
        keyConfig: KeyConfig,
        certificates: List<java.security.cert.X509Certificate>,
        minSdk: Int,
    ) = RandomAccessFile(apkFile, "rw").use { file ->
        val apk = DataSources.asDataSource(file)
        val sections = ApkSigUtils.findZipSections(apk)
        val contentsEnd = try {
            ApkSigUtils.findApkSigningBlock(apk, sections).startOffset
        } catch (_: ApkSigningBlockNotFoundException) {
            sections.zipCentralDirectoryOffset
        }

        val centralDirectory = apk.getByteBuffer(
            sections.zipCentralDirectoryOffset,
            sections.zipCentralDirectorySizeBytes.toInt(),
        )
        val eocd = sections.zipEndOfCentralDirectory

        DefaultApkSignerEngine.Builder(
            listOf(DefaultApkSignerEngine.SignerConfig.Builder(signerName, keyConfig, certificates).build()),
            minSdk,
        ).setV1SigningEnabled(false).setV3SigningEnabled(false).build().use { engine ->
            val request = engine.outputZipSections2(
                apk.slice(0, contentsEnd),
                DataSources.asDataSource(centralDirectory.duplicate()),
                DataSources.asDataSource(eocd.duplicate()),
            )
            val padding = ByteBuffer.wrap(ByteArray(request.paddingSizeBeforeApkSigningBlock))
            val signingBlock = ByteBuffer.wrap(request.apkSigningBlock)
            request.done()

            val centralDirectoryOffset = contentsEnd + padding.remaining() + signingBlock.remaining()
            ApkSigUtils.setZipEocdCentralDirectoryOffset(eocd, centralDirectoryOffset)

            val channel = file.channel
            var position = contentsEnd
            for (buffer in arrayOf(padding, signingBlock, centralDirectory, eocd)) {
                while (buffer.hasRemaining()) {
                    position += channel.write(buffer, position)
                }
            }
            file.setLength(position)
            engine.outputDone()
        }
    }
}
