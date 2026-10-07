package com.example.parentalagent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.app.usage.UsageStatsManager
import android.os.SystemClock
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64

class MainActivity : ComponentActivity() {

    private lateinit var connectionText: TextView
    private lateinit var stopShareButton: Button
    private var pendingScreenRequest = false

    private val prefs by lazy {
        getSharedPreferences("parental", MODE_PRIVATE)
    }

    private val deviceId: String
        get() {
            val current = prefs.getString("device_id", null)
            if (current != null) return current
            val created = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", created).apply()
            return created
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        connectionText = findViewById(R.id.connectionText)
        stopShareButton = findViewById(R.id.stopShareButton)

        findViewById<Button>(R.id.adultButton).setOnClickListener {
            adultAccess()
        }

        findViewById<Button>(R.id.pairButton).setOnClickListener {
            pairingDialog()
        }

        stopShareButton.setOnClickListener {
            stopSharing()
        }

        SocketManager.onConnectionChanged = { connected ->
            runOnUiThread {
                connectionText.text = if (connected) {
                    "🟢 Servidor conectado"
                } else {
                    "🔴 Servidor desconectado"
                }
            }
        }

        SocketManager.onParentCommand = { type, payload ->
            when (type) {
                "request_status" -> sendStatus()
                "set_limits" -> saveLimits(payload)
                "request_screen_share" -> {
                    pendingScreenRequest = true
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "El adulto solicitó compartir pantalla. Abre esta aplicación para autorizarlo.",
                            Toast.LENGTH_LONG
                        ).show()
                        if (!isFinishing) startScreenConsent()
                    }
                }
                "stop_screen_share" -> stopSharing()
            }
        }

        connectIfEnrolled()

        if (savedInstanceState == null && pendingScreenRequest) {
            startScreenConsent()
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingScreenRequest) {
            startScreenConsent()
        }
    }

    private fun connectIfEnrolled() {
        val token = prefs.getString("device_token", null) ?: return
        val server = prefs.getString("server_url", "") ?: return
        if (server.isBlank()) return
        SocketManager.connect(server.trimEnd('/'), deviceId, token)
    }

    private fun pairingDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }

        val server = EditText(this).apply {
            hint = "URL del servidor"
            setText(prefs.getString("server_url", "http://192.168.1.50:3000"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        val code = EditText(this).apply {
            hint = "Código de vinculación de 6 dígitos"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        layout.addView(server)
        layout.addView(code)

        AlertDialog.Builder(this)
            .setTitle("Vincular dispositivo")
            .setMessage("El adulto debe generar el código desde el panel.")
            .setView(layout)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Vincular") { _, _ ->
                Thread {
                    try {
                        val serverUrl = server.text.toString().trim().trimEnd('/')
                        val pairingCode = code.text.toString().trim()
                        val result = claimPairing(serverUrl, pairingCode)
                        runOnUiThread {
                            prefs.edit()
                                .putString("server_url", serverUrl)
                                .putString("device_token", result.getString("deviceToken"))
                                .apply()
                            Toast.makeText(this, "Dispositivo vinculado.", Toast.LENGTH_SHORT).show()
                            connectIfEnrolled()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "No se pudo vincular: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .show()
    }

    private fun claimPairing(serverUrl: String, code: String): JSONObject {
        val url = URL("$serverUrl/api/pairing/claim")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject()
            .put("code", code)
            .put("deviceId", deviceId)
            .toString()

        connection.outputStream.use { it.write(body.toByteArray()) }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream.bufferedReader().use { it.readText() }
        if (responseCode !in 200..299) {
            throw IllegalStateException(JSONObject(response).optString("error", "Error de servidor"))
        }
        return JSONObject(response)
    }

    private fun adultAccess() {
        val stored = prefs.getString("pin_cipher", null)
        val input = EditText(this).apply {
            hint = if (stored == null) "Crear PIN de 4-8 dígitos" else "PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle(if (stored == null) "Crear PIN del adulto" else "Acceso del adulto")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ ->
                val pin = input.text.toString()
                if (!pin.matches(Regex("\\d{4,8}"))) {
                    Toast.makeText(this, "El PIN debe tener 4 a 8 dígitos.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (stored == null) {
                    savePin(pin)
                    showAdultControls()
                } else if (verifyPin(pin)) {
                    showAdultControls()
                } else {
                    Toast.makeText(this, "PIN incorrecto.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showAdultControls() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }

        val info = TextView(this).apply {
            text = "Servidor: ${prefs.getString("server_url", "No vinculado")}\nDispositivo: $deviceId"
        }

        val limits = EditText(this).apply {
            hint = "Ejemplo: 120 minutos diarios"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("daily_limit", 120).toString())
        }

        val usageButton = Button(this).apply {
            text = "Permitir estadísticas de uso"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        layout.addView(info)
        layout.addView(limits)
        layout.addView(usageButton)

        AlertDialog.Builder(this)
            .setTitle("Controles del adulto")
            .setView(layout)
            .setNegativeButton("Cerrar", null)
            .setPositiveButton("Guardar límite") { _, _ ->
                val minutes = limits.text.toString().toIntOrNull() ?: 120
                prefs.edit().putInt("daily_limit", minutes.coerceIn(1, 1440)).apply()
                val payload = JSONObject()
                    .put("dailyLimitMinutes", minutes.coerceIn(1, 1440))
                    .put("allowedStart", "06:00")
                    .put("allowedEnd", "21:00")
                saveLimits(payload)
                SocketManager.sendLimits(payload)
                Toast.makeText(this, "Límite guardado.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun saveLimits(payload: JSONObject) {
        prefs.edit()
            .putInt("daily_limit", payload.optInt("dailyLimitMinutes", 120))
            .putString("allowed_start", payload.optString("allowedStart", "06:00"))
            .putString("allowed_end", payload.optString("allowedEnd", "21:00"))
            .apply()
        sendStatus()
    }

    private fun sendStatus() {
        val dailyLimit = prefs.getInt("daily_limit", 120)
        val used = getTodayForegroundMinutes()
        val status = JSONObject()
            .put("supervision", true)
            .put("screenSharing", isScreenServiceRunning())
            .put("dailyLimitMinutes", dailyLimit)
            .put("usedTodayMinutes", used)
            .put("remainingMinutes", (dailyLimit - used).coerceAtLeast(0))
            .put("allowedStart", prefs.getString("allowed_start", "06:00"))
            .put("allowedEnd", prefs.getString("allowed_end", "21:00"))
        SocketManager.sendStatus(status)
    }

    private fun startScreenConsent() {
        if (!pendingScreenRequest) return
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Use Activity Result APIs for new apps; retained here for compact MVP.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return

        pendingScreenRequest = false
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Compartir pantalla cancelado.", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, StreamService::class.java).apply {
            putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamService.EXTRA_RESULT_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }

        stopShareButton.visibility = android.view.View.VISIBLE
        Toast.makeText(this, "Compartir pantalla autorizado.", Toast.LENGTH_SHORT).show()
    }

    private fun stopSharing() {
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_STOP
        }
        startService(intent)
        stopShareButton.visibility = android.view.View.GONE
    }

    private fun getTodayForegroundMinutes(): Int {
        val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        return try {
            manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                start,
                end
            ).sumOf { it.totalTimeInForeground }.div(60_000L).toInt()
        } catch (_: Exception) {
            0
        }
    }

    private fun isScreenServiceRunning(): Boolean = false

    private fun savePin(pin: String) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray())
        val encrypted = encrypt(digest)
        prefs.edit().putString("pin_cipher", encrypted).apply()
    }

    private fun verifyPin(pin: String): Boolean {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(pin.toByteArray())
            val expected = prefs.getString("pin_cipher", null) ?: return false
            Base64.encodeToString(decrypt(expected), Base64.NO_WRAP) ==
                Base64.encodeToString(digest, Base64.NO_WRAP)
        } catch (_: Exception) {
            false
        }
    }

    private fun key(): SecretKey {
        val alias = "parental_pin_key"
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(bytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(bytes)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): ByteArray {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                StreamService.CHANNEL_ID,
                "Control parental",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                50
            )
        }
    }

    companion object {
        private const val REQUEST_CAPTURE = 7001
    }
}
