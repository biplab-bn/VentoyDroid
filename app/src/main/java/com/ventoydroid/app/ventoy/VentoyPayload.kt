package com.ventoydroid.app.ventoy

import android.content.Context
import org.tukaani.xz.XZInputStream
import java.io.InputStream
import java.security.MessageDigest

/** Version + asset access for the bundled Ventoy payload. */
interface VentoyPayload {
    val version: String
    fun bootImg(): InputStream            // 512 bytes, GRUB MBR boot code
    fun coreImgXz(): InputStream          // xz of core.img (2047 sectors)
    fun ventoyDiskImgXz(): InputStream    // xz of the 32 MiB VTOYEFI partition image

    /** Returns a list of integrity problems; empty means the payload is good. */
    fun verifyIntegrity(): List<String> = emptyList()
}

/** Loads the payload from APK assets and verifies SHA-256 of every artifact. */
class AssetVentoyPayload(private val context: Context) : VentoyPayload {

    override val version: String by lazy {
        context.assets.open("ventoy/version").bufferedReader().use { it.readText().trim() }
    }

    override fun bootImg(): InputStream = context.assets.open("ventoy/boot.img")

    override fun coreImgXz(): InputStream = context.assets.open("ventoy/core.img.xz")

    override fun ventoyDiskImgXz(): InputStream = context.assets.open("ventoy/ventoy.disk.img.xz")

    /** Decompresses core.img (2,047 sectors) fully into memory. */
    fun coreImgBytes(): ByteArray = XZInputStream(coreImgXz()).use { it.readBytes() }

    /** Verifies the embedded checksum files against actual asset content. */
    override fun verifyIntegrity(): List<String> {
        val problems = mutableListOf<String>()
        checkSha("ventoy/boot.img", "86cafd854fe5e1ead227447950064cdbe153c94bad26524efb60419eb1f19c15")
            ?.let { problems.add(it) }
        checkXzSha("ventoy/core.img.xz", "1c5f76f34e7af423aa491218c313258e0b35c0ca9ae0a7e42b66246fd0934b8b")
            ?.let { problems.add(it) }
        checkXzSha("ventoy/ventoy.disk.img.xz", "058558d63faee19d559c5aae46f7f45ffb6b080ab7e38f2918d1638af5a7b1be")
            ?.let { problems.add(it) }
        return problems
    }

    private fun sha256(stream: InputStream): String =
        MessageDigest.getInstance("SHA-256").let { md ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }

    private fun checkSha(asset: String, expected: String): String? {
        val actual = sha256(context.assets.open(asset))
        return if (!actual.equals(expected, ignoreCase = true)) {
            "$asset: sha256 mismatch ($actual)"
        } else null
    }

    private fun checkXzSha(asset: String, expectedOfDecompressed: String): String? {
        val actual = sha256(XZInputStream(context.assets.open(asset)))
        return if (!actual.equals(expectedOfDecompressed, ignoreCase = true)) {
            "$asset (decompressed): sha256 mismatch ($actual)"
        } else null
    }
}
