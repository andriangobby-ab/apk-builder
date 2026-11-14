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
    private lateinit var pkgInput: EditText
    private lateinit var versionInput: EditText
    private lateinit var orientationSpinner: Spinner
    private lateinit var fullscreenCheck: CheckBox
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

    // ==================== РЕЖИМ ИГРЫ (БЕЗ PULL-TO-REFRESH И ОТСКОКОВ) ====================
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun startGameWebView() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)

        val webView = WebView(this).apply {
            // Полностью убираем системный эффект резинки (overscroll) при попытке свайпа вверх или вниз
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        setContentView(webView)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/game/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            // Отключаем масштабирование жестами, чтобы игра не прыгала при случайных касаниях
            supportZoom()
            builtInZoomControls = false
            displayZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest) =
                loader.shouldInterceptRequest(req.url)

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/game/index.html")
    }

    // ==================== РЕЖИМ КОНСТРУКТОРА APK (FreeAppToApk Style) ====================
    private fun showBuilderScreen() {
        val scrollView = ScrollView(this).apply {
            setPadding(40, 40, 40, 40)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleLabel = TextView(this).apply {
            text = "🚀 HTML5 Game APK Studio Pro"
            textSize = 20f
            setPadding(0, 0, 0, 20)
        }

        val lbl1 = TextView(this).apply { text = "Название игры (App Name):" }
        nameInput = EditText(this).apply {
            setText("My Awesome Game")
        }

        val lbl2 = TextView(this).apply { text = "Уникальный ID пакета (Package Name):" }
        pkgInput = EditText(this).apply {
            setText("com.mycompany.mygame")
        }

        val lbl3 = TextView(this).apply { text = "Версия (Version Name):" }
        versionInput = EditText(this).apply {
            setText("1.0.0")
        }

        val lbl4 = TextView(this).apply { text = "Ориентация экрана:" }
        orientationSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Альбомная (Landscape)", "Портретная (Portrait)", "Автоповорот (Sensor)"))
            setSelection(0)
        }

        fullscreenCheck = CheckBox(this).apply {
            text = "Полноэкранный режим (скрыть статус-бар)"
            isChecked = true
        }

        val btnPickIcon = Button(this).apply {
            text = "🖼️ Выбрать иконку приложения"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                startActivityForResult(intent, REQ_ICON)
            }
        }

        iconPreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(140, 140).apply { setMargins(0, 10, 0, 20) }
            setImageResource(android.R.drawable.sym_def_app_icon)
        }

        val btnPickZip = Button(this).apply {
            text = "📦 Выбрать ZIP-архив с игрой и создать APK"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/zip" }
                startActivityForResult(intent, REQ_ZIP)
            }
        }

        statusText = TextView(this).apply {
            text = "Настройте параметры выше и выберите ZIP-архив.\nГотовый APK появится в папке Загрузки."
            setPadding(0, 30, 0, 50)
        }

        layout.addView(titleLabel)
        layout.addView(lbl1)
        layout.addView(nameInput)
        layout.addView(lbl2)
        layout.addView(pkgInput)
        layout.addView(lbl3)
        layout.addView(versionInput)
        layout.addView(lbl4)
        layout.addView(orientationSpinner)
        layout.addView(fullscreenCheck)
        layout.addView(Space(this).apply { minimumHeight = 20 })
        layout.addView(btnPickIcon)
        layout.addView(iconPreview)
        layout.addView(btnPickZip)
        layout.addView(statusText)

        scrollView.addView(layout)
        setContentView(scrollView)
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
                Toast.makeText(this, "Иконка загружена!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка выбора иконки", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQ_ZIP) {
            selectedZipUri = data?.data
            val gameTitle = nameInput.text.toString().ifBlank { "MyGame" }
            val customPkg = pkgInput.text.toString().ifBlank { "com.game.app" }
            val orientationChoice = orientationSpinner.selectedItemPosition
            val isFullscreen = fullscreenCheck.isChecked

            statusText.text = "⏳ Компилируем APK без ограничений по весу..."

            thread {
                try {
                    val outDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val outApk = File(outDir, "${gameTitle.replace(" ", "_")}.apk")
                    
                    buildProApk(selectedZipUri!!, selectedIconUri, gameTitle, customPkg, orientationChoice, isFullscreen, outApk)
                    
                    runOnUiThread {
                        statusText.text = "✅ Готово! APK сохранен в «Загрузки»:\n${outApk.absolutePath}"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread { statusText.text = "❌ Ошибка: ${e.message}" }
                }
            }
        }
    }

    // ==================== ЯДРО СБОРКИ (ZIP64 + ИКОНКА + МАНИФЕСТ) ====================
    private fun buildProApk(
        gameZipUri: Uri, 
        iconUri: Uri?, 
        gameTitle: String, 
        customPkg: String, 
        orientation: Int, 
        isFullscreen: Boolean, 
        signedApk: File
    ) {
        val selfApkFile = File(applicationInfo.sourceDir)
        val unsignedTemp = File(cacheDir, "temp_unsigned.apk")
        val randomSuffix = UUID.randomUUID().toString().replace("-", "").lowercase().take(6)
        val finalPkg = "$customPkg.$randomSuffix".take(50)

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

            ZipInputStream(BufferedInputStream(FileInputStream(selfApkFile))).use { selfIn ->
                var entry = selfIn.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.startsWith("META-INF/")) {
                        val newEntry = ZipEntry(name)

                        if (name == "AndroidManifest.xml") {
                            val bytes = patchManifestAndSettings(selfIn.readBytes(), gameTitle, finalPkg)
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

            // Потоковое добавление гигабайтов игры в assets/game/
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

    private fun patchManifestAndSettings(bytes: ByteArray, title: String, pkg: String): ByteArray {
        val oldPkg = "com.selfapk.game000000000000".toByteArray(StandardCharsets.UTF_16LE)
        val newPkg = pkg.padEnd(28, '0').take(28).toByteArray(StandardCharsets.UTF_16LE)
        
        val oldTitle = "HTML_GAME_000000000000000000".toByteArray(StandardCharsets.UTF_16LE)
        val newTitle = title.padEnd(28, ' ').take(28).toByteArray(StandardCharsets.UTF_16LE)

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
