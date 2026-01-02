package com.conectabot.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

private const val API_URL = "https://api.conectabot.org"

/* =========================================================
   GLOBAL STATE
========================================================= */

object ConversacionesState {
    val conversaciones = mutableStateOf<Map<String, JSONObject>>(emptyMap())
}

/* =========================================================
   ACTIVITY
========================================================= */

class MainActivity : ComponentActivity() {

    private lateinit var googleClient: GoogleSignInClient
    private val tokenState = mutableStateOf<String?>(null)
    private val emailState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔔 Permiso de notificaciones (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        // 🔔 Canal con sonido (forzado)
        crearCanalNotificaciones(this)

        tokenState.value = getToken(this)
        emailState.value = getEmailFromToken(tokenState.value)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken("472951762551-0e4elcms7ctfpova6utovkvepj6lktu1.apps.googleusercontent.com")
            .build()

        googleClient = GoogleSignIn.getClient(this, gso)

        setContent {
            MaterialTheme {
                if (tokenState.value == null) {
                    LoginScreen {
                        startActivityForResult(googleClient.signInIntent, 100)
                    }
                } else {
                    ConversacionesScreen(
                        token = tokenState.value!!,
                        email = emailState.value!!,
                        onLogout = {
                            googleClient.signOut()
                            clearToken(this)
                            tokenState.value = null
                            emailState.value = null
                        }
                    )
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100) {
            try {
                val account = GoogleSignIn
                    .getSignedInAccountFromIntent(data)
                    .getResult(ApiException::class.java)

                sendTokenToBackend(account.idToken!!)
            } catch (e: ApiException) {
                Log.e("GOOGLE_LOGIN", "Error", e)
            }
        }
    }

    private fun sendTokenToBackend(idToken: String) {
        val body = """{ "idToken": "$idToken" }"""
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$API_URL/api/google-login")
            .post(body)
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) return

                val json = JSONObject(response.body!!.string())
                val token = json.getString("token")

                saveToken(this@MainActivity, token)

                runOnUiThread {
                    tokenState.value = token
                    emailState.value = getEmailFromToken(token)
                }
            }
        })
    }
}

/* =========================================================
   NOTIFICATIONS
========================================================= */

fun crearCanalNotificaciones(context: Context) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // ⚠️ IMPORTANTE: borrar canal previo (solo pruebas)
        manager.deleteNotificationChannel("mensajes")

        val soundUri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI

        val channel = NotificationChannel(
            "mensajes",
            "Mensajes nuevos",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notificaciones de mensajes no leídos"
            enableVibration(true)
            setSound(
                soundUri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
        }

        manager.createNotificationChannel(channel)
    }
}

fun mostrarNotificacion(context: Context, numero: String, texto: String) {
    val notification = NotificationCompat.Builder(context, "mensajes")
        .setSmallIcon(android.R.drawable.sym_action_chat)
        .setContentTitle("Nuevo mensaje")
        .setContentText("$numero: $texto")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()

    NotificationManagerCompat.from(context)
        .notify(numero.hashCode(), notification)
}

/* =========================================================
   UI
========================================================= */

@Composable
fun LoginScreen(onGoogleClick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = onGoogleClick) {
            Text("Iniciar con Google")
        }
    }
}

/* =========================================================
   MODELO
========================================================= */

data class MensajeUI(
    val texto: String,
    val fechaRaw: String,
    val esCliente: Boolean
)

sealed class ChatRow {
    data class FechaHeader(val texto: String) : ChatRow()
    data class MensajeItem(val msg: MensajeUI) : ChatRow()
}

/* =========================================================
   CONVERSACIONES
========================================================= */

@Composable
fun ConversacionesScreen(
    token: String,
    email: String,
    onLogout: () -> Unit
) {
    var conversaciones by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var seleccionada by remember { mutableStateOf<String?>(null) }
    var mensajes by remember { mutableStateOf(listOf<MensajeUI>()) }

    LaunchedEffect(Unit) {
        cargarConversaciones(token, email, seleccionada) {
            conversaciones = it
        }
    }

    LaunchedEffect(true) {
        while (true) {
            delay(2500)
            cargarConversaciones(token, email, seleccionada) {
                conversaciones = it
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        LazyColumn {
            conversaciones.forEach { (numero, datos) ->
                val nombre = datos.optString("nombre", numero)
                val ultimoTexto = datos.optString("ultimoTexto", "-")
                val leido = datos.optBoolean("leido", true)

                item {
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(4.dp)
                            .clickable {
                                seleccionada = numero
                                val updated = conversaciones.toMutableMap()
                                updated[numero]?.put("leido", true)
                                conversaciones = updated
                                ConversacionesState.conversaciones.value = updated
                            }
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(nombre, fontWeight = FontWeight.Bold)
                                Text(
                                    ultimoTexto,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                            if (!leido) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(0xFF25D366), CircleShape)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* =========================================================
   NETWORK
========================================================= */

fun cargarConversaciones(
    token: String,
    email: String,
    seleccionada: String?,
    onResult: (Map<String, JSONObject>) -> Unit
) {
    val request = Request.Builder()
        .url("$API_URL/api/whatsapp/conversaciones-activas?cliente=$email")
        .addHeader("Authorization", "Bearer $token")
        .build()

    OkHttpClient().newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {}

        override fun onResponse(call: Call, response: Response) {
            if (!response.isSuccessful) return
            val json = JSONObject(response.body!!.string())
            val map = mutableMapOf<String, JSONObject>()

            json.keys().forEach { numero ->
                val obj = json.getJSONObject(numero)
                val prev = ConversacionesState.conversaciones.value[numero]

                val prevDate = prev?.optString("updated_at", "")
                val newDate = obj.optString("updated_at", "")
                val esNuevo = prev != null && prevDate != newDate
                val leido = prev != null && prevDate == newDate && prev.optBoolean("leido", true)

                obj.put("leido", leido)

                if (esNuevo && !leido && seleccionada != numero) {
                    mostrarNotificacion(
                        AppContext.app,
                        numero,
                        obj.optString("ultimoTexto", "Nuevo mensaje")
                    )
                }

                map[numero] = obj
            }

            ConversacionesState.conversaciones.value = map
            onResult(map)
        }
    })
}

/* =========================================================
   STORAGE + UTILS
========================================================= */

fun saveToken(context: Context, token: String) {
    context.getSharedPreferences("auth", Context.MODE_PRIVATE)
        .edit().putString("jwt", token).apply()
}

fun clearToken(context: Context) {
    context.getSharedPreferences("auth", Context.MODE_PRIVATE)
        .edit().clear().apply()
}

fun getToken(context: Context): String? =
    context.getSharedPreferences("auth", Context.MODE_PRIVATE)
        .getString("jwt", null)

fun getEmailFromToken(token: String?): String? =
    try {
        val payload = String(android.util.Base64.decode(token!!.split(".")[1], 0))
        JSONObject(payload).getString("email")
    } catch (_: Exception) {
        null
    }
