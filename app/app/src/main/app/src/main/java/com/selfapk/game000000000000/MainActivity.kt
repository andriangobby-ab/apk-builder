package com.selfapk.game000000000000

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.webkit.WebViewAssetLoader
import com.android.apksig.ApkSigner
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.*
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var nameInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasEmbeddedGame()) {
            startGameWebView()
        } else {
            showBuilderScreen()
        }
    }

    private fun hasEmbeddedGame(): Boolean {
        return try {
            assets.open("game/index.html").close()
            true
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startGameWebView() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)

        val webView = WebView(this)
        setContentView(webView)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/game/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest) =
                loader.shouldInterceptRequest(req.url)
        }
        webView.loadUrl("https://appassets.androidplatform.net/game/index.html")
    }

    private fun showBuilderScreen() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 100, 60, 60)
        }

    nameInput = EditText(this).apply {
            hint = "Название игры (латиницей)"
            setText("My Awesome Game")
        }

        val btnPick = Button(this).apply {
            text = "📦 Выбрать ZIP и создать APK"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/zip" }
                startActivityForResult(intent, 100)
            }
        }

        statusText = TextView(this).apply {
            text = "Выбери ZIP с index.html и картинками .jpg любого размера.\nГотовый APK появится в папке Загрузки (Downloads)."
            setPadding(0, 40, 0, 0)
        }

        layout.addView(nameInput)
        layout.addView(btnPick)
        layout.addView(statusText)
        setContentView(layout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val zipUri = data?.data ?: return
        val title = nameInput.text.toString().ifBlank { "MyGame" }
        statusText.text = "⏳ Собираем APK... Подожди..."

        thread {
            try {
                val outDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outApk = File(outDir, "${title.replace(" ", "_")}.apk")
                buildSelfClonedApk(zipUri, title, outApk)
                runOnUiThread {
                    statusText.text = "✅ Готово! Твой APK лежит в папке «Загрузки»:\n${outApk.absolutePath}"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { statusText.text = "❌ Ошибка: ${e.message}" }
            }
        }
    }

    private fun buildSelfClonedApk(gameZipUri: Uri, gameTitle: String, signedApk: File) {
        val selfApkFile = File(applicationInfo.sourceDir)
        val unsignedTemp = File(cacheDir, "temp_unsigned.apk")
        val randomId = UUID.randomUUID().toString().replace("-", "").lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(12)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(unsignedTemp))).use { zipOut ->
            zipOut.setLevel(1)

            ZipInputStream(BufferedInputStream(FileInputStream(selfApkFile))).use { selfIn ->
                var entry = selfIn.nextEntry
                while (entry != null) {
                    if (!entry.name.startsWith("META-INF/")) {
                        val newEntry = ZipEntry(entry.name)
                        if (entry.name == "AndroidManifest.xml") {
                            val bytes = patchManifest(selfIn.readBytes(), gameTitle, randomId)
                            zipOut.putNextEntry(newEntry)
                            zipOut.write(bytes)
                        } else if (entry.name == "resources.arsc") {
                            val bytes = selfIn.readBytes()
                            newEntry.method = ZipEntry.STORED
                            newEntry.size = bytes.size.toLong()
                            newEntry.crc = CRC32().apply { update(bytes) }.value
                            zipOut.putNextEntry(newEntry)
                            zipOut.write(bytes)
                        } else {
                            zipOut.putNextEntry(newEntry)
                            selfIn.copyTo(zipOut, 128 * 1024)
                        }
                        zipOut.closeEntry()
                    }
                    entry = selfIn.nextEntry
                }
            }

            contentResolver.openInputStream(gameZipUri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw, 256 * 1024)).use { gameIn ->
                    var entry = gameIn.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val cleanName = entry.name.trimStart('/', '\\')
                            zipOut.putNextEntry(ZipEntry("assets/game/$cleanName"))
                            gameIn.copyTo(zipOut, 256 * 1024)
                            zipOut.closeEntry()
                        }
                        entry = gameIn.nextEntry
                    }
                }
            }
        }

        signWithBuiltInCert(unsignedTemp, signedApk)
        unsignedTemp.delete()
    }

    private fun patchManifest(bytes: ByteArray, title: String, id: String): ByteArray {
        val pkgStr = "com.selfapk.game$id" // Ровно 26 символов (14 + 12)
        val titleStr = title.padEnd(28, ' ').take(28) // Ровно 28 символов

        val oldPkg = "com.selfapk.game000000000000".toByteArray(StandardCharsets.UTF_16LE)
        val newPkg = pkgStr.toByteArray(StandardCharsets.UTF_16LE)
        val oldTitle = "HTML_GAME_000000000000000000".toByteArray(StandardCharsets.UTF_16LE)
        val newTitle = titleStr.toByteArray(StandardCharsets.UTF_16LE)

        val out = bytes.copyOf()
        for (i in 0..out.size - oldPkg.size) {
            if (out.sliceArray(i until i + oldPkg.size).contentEquals(oldPkg)) {
                System.arraycopy(newPkg, 0, out, i, newPkg.size)
                break
            }
        }
        for (i in 0..out.size - oldTitle.size) {
            if (out.sliceArray(i until i + oldTitle.size).contentEquals(oldTitle)) {
                System.arraycopy(newTitle, 0, out, i, newTitle.size)
                break
            }
        }
        return out
    }

    private fun signWithBuiltInCert(unsigned: File, signed: File) {
        val ks = java.security.KeyStore.getInstance("PKCS12")
        assets.open("debug.p12").use { ks.load(it, "android".toCharArray()) }
        val key = ks.getKey("androiddebugkey", "android".toCharArray()) as java.security.PrivateKey
        val cert = ks.getCertificate("androiddebugkey") as java.security.cert.X509Certificate

        val config = ApkSigner.SignerConfig.Builder("CERT", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .build()
            .sign()
    }
}
