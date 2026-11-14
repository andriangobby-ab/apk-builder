package com.selfapk.game000000000000

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private lateinit var iconPreview: ImageView
    
    private var selectedZipUri: Uri? = null
    private var selectedIconUri: Uri? = null

    companion object {
        private const val REQ_ZIP = 100
        private const val REQ_ICON = 101
    }

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

    // ==================== ЭКРАН НАСТРОЙКИ И СОЗДАНИЯ APK ====================
    private fun showBuilderScreen() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 60, 50, 60)
        }

        val titleLabel = TextView(this).apply {
            text = "🎮 Создатель APK для HTML5 игр"
            textSize = 20f
            setPadding(0, 0, 0, 30)
        }

        nameInput = EditText(this).apply {
            hint = "Название игры (латиницей)"
            setText("My Awesome Game")
            setPadding(20, 20, 20, 20)
        }

        val btnPickIcon = Button(this).apply {
            text = "🖼️ Выбрать иконку (PNG / JPG)"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                startActivityForResult(intent, REQ_ICON)
            }
        }

        iconPreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(150, 150).apply { setMargins(0, 10, 0, 20) }
            setImageResource(android.R.drawable.sym_def_app_icon)
        }

        val btnPickZip = Button(this).apply {
            text = "📦 Выбрать ZIP-архив с игрой"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/zip" }
                startActivityForResult(intent, REQ_ZIP)
            }
        }

        statusText = TextView(this).apply {
            text = "1. Введите название\n2. Выберите иконку (по желанию)\n3. Выберите ZIP с игрой\nГотовый APK сохранится в «Загрузки»."
            setPadding(0, 30, 0, 0)
        }

        layout.addView(titleLabel)
        layout.addView(nameInput)
        layout.addView(btnPickIcon)
        layout.addView(iconPreview)
        layout.addView(btnPickZip)
        layout.addView(statusText)
        setContentView(layout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        if (requestCode == REQ_ICON) {
            selectedIconUri = data?.data
            try {
                val inputStream = contentResolver.openInputStream(selectedIconUri!!)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                iconPreview.setImageBitmap(bitmap)
                Toast.makeText(this, "Иконка выбрана!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка загрузки иконки", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQ_ZIP) {
            selectedZipUri = data?.data
            val title = nameInput.text.toString().ifBlank { "MyGame" }
            statusText.text = "⏳ Собираем APK... Подожди пару секунд..."

            thread {
                try {
                    val outDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val outApk = File(outDir, "${title.replace(" ", "_")}.apk")
                    buildCustomizedApk(selectedZipUri!!, selectedIconUri, title, outApk)
                    runOnUiThread {
                        statusText.text = "✅ Успешно!\nAPK сохранен в Загрузки:\n${outApk.absolutePath}"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread { statusText.text = "❌ Ошибка: ${e.message}" }
                }
            }
        }
    }

    // ==================== СБОРКА И КАСТОМИЗАЦИЯ APK ====================
    private fun buildCustomizedApk(gameZipUri: Uri, iconUri: Uri?, gameTitle: String, signedApk: File) {
        val selfApkFile = File(applicationInfo.sourceDir)
        val unsignedTemp = File(cacheDir, "temp_unsigned.apk")
        val randomId = UUID.randomUUID().toString().replace("-", "").lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(12)

        // Конвертируем выбранную иконку в ByteArray (если выбрана)
        val customIconBytes = iconUri?.let { uri ->
            contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                val scaled = Bitmap.createScaledBitmap(bmp, 192, 192, true)
                val bos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 90, bos)
                bos.toByteArray()
            }
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(unsignedTemp))).use { zipOut ->
            zipOut.setLevel(1)

            // 1. Копируем себя (заменяя манифест, иконки и пропуская старую подпись)
            ZipInputStream(BufferedInputStream(FileInputStream(selfApkFile))).use { selfIn ->
                var entry = selfIn.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.startsWith("META-INF/")) {
                        val newEntry = ZipEntry(name)

                        if (name == "AndroidManifest.xml") {
                            val bytes = patchManifest(selfIn.readBytes(), gameTitle, randomId)
                            zipOut.putNextEntry(newEntry)
                            zipOut.write(bytes)
                        } else if (name == "resources.arsc") {
                            val bytes = selfIn.readBytes()
                            newEntry.method = ZipEntry.STORED
                            newEntry.size = bytes.size.toLong()
                            newEntry.crc = CRC32().apply { update(bytes) }.value
                            zipOut.putNextEntry(newEntry)
                            zipOut.write(bytes)
                        } else if (customIconBytes != null && (name.contains("mipmap") || name.contains("drawable")) && name.endsWith(".png")) {
                            // Заменяем стандартные иконки приложения на картинку пользователя
                            newEntry.method = ZipEntry.DEFLATED
                            zipOut.putNextEntry(newEntry)
                            zipOut.write(customIconBytes)
                        } else {
                            zipOut.putNextEntry(newEntry)
                            selfIn.copyTo(zipOut, 128 * 1024)
                        }
                        zipOut.closeEntry()
                    }
                    entry = selfIn.nextEntry
                }
            }

            // 2. Потоково дописываем игровые файлы из ZIP в assets/game/
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
        val pkgStr = "com.selfapk.game$id"
        val titleStr = title.padEnd(28, ' ').take(28)

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
