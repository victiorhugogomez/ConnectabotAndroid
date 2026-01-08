package com.conectabot.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
//import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
object ConversacionesState {
    val conversaciones = mutableStateOf<Map<String, JSONObject>>(emptyMap())
}
var conversacionAbierta: String? = null

/* =========================================================
   ACTIVITY
========================================================= */

class MainActivity : ComponentActivity() {

    private lateinit var googleClient: GoogleSignInClient
    private val tokenState = mutableStateOf<String?>(null)
    private val emailState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ===== PARTE 3: inicializar canal =====
        crearCanalNotificaciones(this)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

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
   NOTIFICATIONS (PARTE 1 y 2)
========================================================= */

fun crearCanalNotificaciones(context: Context) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
fun EstadoIcono(estado: String?) {
    when (estado) {
        "enviado" -> {
            Text("✓", fontSize = MaterialTheme.typography.labelSmall.fontSize)
        }
        "entregado" -> {
            Text("✓✓", fontSize = MaterialTheme.typography.labelSmall.fontSize)
        }
        "leido" -> {
            Text(
                "✓✓",
                color = Color(0xFF0B6EFD), // azul WhatsApp
                fontSize = MaterialTheme.typography.labelSmall.fontSize
            )
        }
    }
}
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
    val esCliente: Boolean,
    val estado: String? = null
)
data class Marcador(
    val id: String,
    val nombre: String,
    val color: Color
)

data class RespuestaRapida(
    val id: String,
    val titulo: String,
    val texto: String
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
    var mensajeNuevo by remember { mutableStateOf("") }
    var mostrarModalRapida by remember { mutableStateOf(false) }
    var nuevaTitulo by remember { mutableStateOf("") }
    var nuevaTexto by remember { mutableStateOf("") }
    val mostrarRapidas = mensajeNuevo.startsWith("/")
    val filtroRapida = mensajeNuevo.drop(1).lowercase()
    val respuestasRapidas = remember {
        mutableStateListOf(
            RespuestaRapida("1", "Saludo", "Hola 👋 ¿en qué puedo ayudarte?"),
            RespuestaRapida("2", "Horario", "Nuestro horario es de lunes a viernes de 9 a 6."),
            RespuestaRapida("3", "Precio", "Con gusto te comparto nuestros precios.")
        )
    }
    val marcadoresDisponibles = remember {
        mutableStateListOf(
            Marcador("espera", "En espera", Color(0xFFFFC107)),
            Marcador("entregado", "Entregado", Color(0xFF4CAF50)),
            Marcador("cerrado", "Venta cerrada", Color(0xFF2196F3))
        )
    }
    var mostrarModalGestionMarcadores by remember { mutableStateOf(false) }
    var nuevoMarcadorNombre by remember { mutableStateOf("") }
    var nuevoMarcadorColor by remember { mutableStateOf(Color.Gray) }
    val marcadoresPorConversacion = remember {
        mutableStateMapOf<String, Marcador>()
    }
    val rapidasFiltradas = respuestasRapidas.filter {
        it.titulo.lowercase().contains(filtroRapida) ||
                it.texto.lowercase().contains(filtroRapida)
    }

    val listState = rememberLazyListState()

    val bufferUltimosMensajes = remember { mutableStateMapOf<String, String>() }
    val noLeidos = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        cargarConversaciones(token, email) { conversaciones = it }
    }

    // ✅ Actualización automática de conversaciones
    LaunchedEffect(true) {
        while (true) {
            delay(2500)
            cargarConversaciones(token, email) { conversaciones = it }

        }
    }

    LaunchedEffect(seleccionada) {
        if (seleccionada == null) return@LaunchedEffect
        while (true) {
            cargarMensajes(token, email, seleccionada!!) {
                mensajes = it
            }
            delay(2500)
        }
    }

    val chatRows = remember(mensajes) {
        buildChatRows(mensajes)
    }

    LaunchedEffect(chatRows.size) {
        if (chatRows.isNotEmpty()) {
            delay(50)
            listState.scrollToItem(chatRows.size - 1)
        }
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {

        if (seleccionada == null) {

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Conversaciones", style = MaterialTheme.typography.titleLarge)
                Button(onClick = onLogout) { Text("Logout") }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn {
                conversaciones.forEach { (numero, datos) ->
                    val nombre = datos.optString("nombre", numero)
                    val ultimoTexto = datos.optString("ultimoTexto", "-")
                    val status = datos.optString("status", "activo")

//                    item {
//                        Card(
//                            Modifier
//                                .fillMaxWidth()
//                                .padding(4.dp)
//                                .clickable {
//                                    seleccionada = numero
//                                }
//                        ) {
//                            Column(Modifier.padding(16.dp)) {
//                                Text(nombre, fontWeight = FontWeight.Bold)
//                                Text(
//                                    ultimoTexto,
//                                    style = MaterialTheme.typography.bodySmall,
//                                    color = Color.Gray
//                                )
//                            }
//                        }
//                    }
                    item {
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(4.dp)
                                .clickable {
                                    seleccionada = numero
                                    conversacionAbierta = numero
//                                    val updated = ConversacionesState.conversaciones.value.toMutableMap()
//                                    updated[numero]?.put("leido",true)
//                                    ConversacionesState.conversaciones.value = updated
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

//                                Column(Modifier.weight(1f)) {
//                                    Text(nombre, fontWeight = FontWeight.Bold)
//                                    Text(
//                                        ultimoTexto,
//                                        style = MaterialTheme.typography.bodySmall,
//                                        color = Color.Gray
//                                    )
//                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)   // 🔒 altura fija del contenido
                                ) {
                                    Text(
                                        nombre,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Text(
                                        ultimoTexto,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray,
                                        maxLines = 1,                       // 👈 CLAVE
                                        overflow = TextOverflow.Ellipsis    // 👈 CLAVE
                                    )
                                }
                                marcadoresPorConversacion[numero]?.let { marcador ->
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(marcador.color, shape = CircleShape)
                                            .padding(end = 6.dp)
                                    )
                                }

                                if (status == "pausado") {
                                    Text(
                                        "||",
                                        color = Color.Gray,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 6.dp)
                                    )
                                }
                                val leido = datos.optBoolean("leido", true)

                                if (!leido) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .padding(start = 8.dp)
                                            .background(Color(0xFF25D366), shape = CircleShape)
                                    )
                                }
                            }
                        }
                    }

                }
            }

        }else {
            val statusActual =
                conversaciones[seleccionada]?.optString("status", "activo") ?: "activo"
            var mostrarMenuAcciones by remember { mutableStateOf(false) }
            var mostrarModalMarcador by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "←",
                    Modifier
                        .clickable {
                            seleccionada = null
                            conversacionAbierta = null
                            mensajes = emptyList()
                        }
                        .padding(8.dp),
                    style = MaterialTheme.typography.titleLarge
                )

                Text(seleccionada!!, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        togglePausaConversacion(
                            token = token,
                            email = email,
                            numero = seleccionada!!,
                            status = statusActual
                        ) {
                            cargarConversaciones(token, email) { conversaciones = it }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text(if (statusActual == "pausado") "Reanudar" else "Pausar")
                }

                Button(
                    onClick = {
                        eliminarConversacion(
                            token = token,
                            email = email,
                            numero = seleccionada!!
                        ) {
                            seleccionada = null
                            mensajes = emptyList()
                            cargarConversaciones(token, email) { conversaciones = it }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text("Eliminar")
                }

                Box {
                    Button(
                        onClick = { mostrarMenuAcciones = true },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("+", fontWeight = FontWeight.Bold)
                    }

                    DropdownMenu(
                        expanded = mostrarMenuAcciones,
                        onDismissRequest = { mostrarMenuAcciones = false }
                    ) {

                        DropdownMenuItem(
                            text = { Text("Agregar marcador") },
                            onClick = {
                                mostrarMenuAcciones = false
                                mostrarModalMarcador = true
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Gestionar marcadores") },
                            onClick = {
                                mostrarMenuAcciones = false
                                mostrarModalGestionMarcadores = true
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Cerrar venta") },
                            onClick = {
                                mostrarMenuAcciones = false
                                Log.d("CHAT_ACTION", "Cerrar venta de $seleccionada")
                            }
                        )
                    }
                }


            }

            Divider()

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(8.dp)
            ) {
                items(chatRows) { row ->
                    when (row) {
                        is ChatRow.FechaHeader -> {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Divider(Modifier.fillMaxWidth(0.4f))
                                Text(
                                    row.texto,
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                                Divider(Modifier.fillMaxWidth(0.4f))
                            }
                        }

                        is ChatRow.MensajeItem -> {
                            val msg = row.msg
                            val align = if (msg.esCliente) Alignment.Start else Alignment.End
                            val bg = if (msg.esCliente) Color(0xFFE0E0E0) else Color(0xFFDCF8C6)

                            Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = bg),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(msg.texto)
                                        Spacer(Modifier.height(4.dp))
                                        Row(
                                            modifier = Modifier.align(Alignment.End),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                formatearHora(msg.fechaRaw),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.Gray
                                            )

                                            if (!msg.esCliente) {
                                                Spacer(Modifier.width(4.dp))
                                                EstadoIcono(msg.estado)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (mostrarRapidas) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    elevation = CardDefaults.cardElevation(6.dp)
                ) {

                    Column {

                        rapidasFiltradas.forEach { rapida ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        mensajeNuevo = rapida.texto
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rapida.titulo, fontWeight = FontWeight.Bold)
                                    Text(
                                        rapida.texto,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        respuestasRapidas.remove(rapida)
                                    }
                                ) {
                                    Text(
                                        "✕",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Gray
                                    )
                                }
                            }

                            Divider()
                        }





                        Text(
                            "+ Crear respuesta rápida",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { mostrarModalRapida = true }
                                .padding(12.dp),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = mensajeNuevo,
                    onValueChange = { mensajeNuevo = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Escribe un mensaje…") }
                )
                Button(
                    onClick = {
                        if (mensajeNuevo.isNotBlank()) {
                            enviarMensaje(token, email, seleccionada!!, mensajeNuevo) {
                                mensajeNuevo = ""
                            }
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("Enviar")
                }
            }

            // 🔥 AQUÍ VA LA PARTE 2 (MODAL)
            if (mostrarModalRapida) {
                AlertDialog(
                    onDismissRequest = { mostrarModalRapida = false },
                    title = { Text("Nueva respuesta rápida") },
                    text = {
                        Column {
                            TextField(
                                value = nuevaTitulo,
                                onValueChange = { nuevaTitulo = it },
                                placeholder = { Text("Título (ej. Saludo)") }
                            )
                            Spacer(Modifier.height(8.dp))
                            TextField(
                                value = nuevaTexto,
                                onValueChange = { nuevaTexto = it },
                                placeholder = { Text("Texto de la respuesta") }
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (nuevaTitulo.isNotBlank() && nuevaTexto.isNotBlank()) {
                                    respuestasRapidas.add(
                                        RespuestaRapida(
                                            id = UUID.randomUUID().toString(),
                                            titulo = nuevaTitulo,
                                            texto = nuevaTexto
                                        )
                                    )
                                    nuevaTitulo = ""
                                    nuevaTexto = ""
                                    mostrarModalRapida = false
                                }
                            }
                        ) {
                            Text("Guardar")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { mostrarModalRapida = false }) {
                            Text("Cancelar")
                        }
                    }
                )
            }


            if (mostrarModalMarcador) {
                AlertDialog(
                    onDismissRequest = { mostrarModalMarcador = false },
                    title = { Text("Seleccionar marcador") },
                    text = {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp) // 👈 SCROLL SOLO AQUÍ
                        ) {
                            items(marcadoresDisponibles) { marcador ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            marcadoresPorConversacion[seleccionada!!] = marcador
                                            mostrarModalMarcador = false
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .background(marcador.color, shape = CircleShape)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(marcador.nombre)
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { mostrarModalMarcador = false }) {
                            Text("Cancelar")
                        }
                    }
                )
            }




            if (mostrarModalGestionMarcadores) {
                AlertDialog(
                    onDismissRequest = { mostrarModalGestionMarcadores = false },
                    title = { Text("Gestionar marcadores") },
                    text = {
                        Column {

                            // 🔹 LISTA SCROLLABLE DE MARCADORES
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp) // 👈 SOLO ESTA PARTE SCROLLEA
                            ) {
                                items(marcadoresDisponibles) { marcador ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .background(marcador.color, shape = CircleShape)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            marcador.nombre,
                                            modifier = Modifier.weight(1f)
                                        )

                                        IconButton(
                                            onClick = {
                                                marcadoresDisponibles.remove(marcador)

                                                // limpiar conversaciones que lo usaban
                                                marcadoresPorConversacion
                                                    .filterValues { it.id == marcador.id }
                                                    .keys
                                                    .forEach { marcadoresPorConversacion.remove(it) }
                                            }
                                        ) {
                                            Text("✕", color = Color.Red)
                                        }
                                    }
                                }
                            }

                            Divider(Modifier.padding(vertical = 10.dp))

                            // 🔹 ZONA FIJA — NUEVO MARCADOR
                            Text("Nuevo marcador", fontWeight = FontWeight.Bold)

                            Spacer(Modifier.height(6.dp))

                            TextField(
                                value = nuevoMarcadorNombre,
                                onValueChange = { nuevoMarcadorNombre = it },
                                placeholder = { Text("Nombre") }
                            )

                            Spacer(Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Color:")
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(nuevoMarcadorColor, shape = CircleShape)
                                )
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                listOf(
                                    Color(0xFFFFC107),
                                    Color(0xFF4CAF50),
                                    Color(0xFF2196F3),
                                    Color(0xFFF44336),
                                    Color(0xFF9C27B0),
                                    Color(0xFF00BCD4),
                                    Color(0xFF795548),
                                    Color(0xFF607D8B),
                                    Color(0xFFFF5722)
                                ).forEach { color ->
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .background(color, shape = CircleShape)
                                            .border(
                                                width = if (nuevoMarcadorColor == color) 2.dp else 0.dp,
                                                color = Color.Black,
                                                shape = CircleShape
                                            )
                                            .clickable { nuevoMarcadorColor = color }
                                    )
                                }
                            }
                        }
                    }
                    ,
                    confirmButton = {
                        Button(
                            onClick = {
                                if (nuevoMarcadorNombre.isNotBlank()) {
                                    marcadoresDisponibles.add(
                                        Marcador(
                                            id = UUID.randomUUID().toString(),
                                            nombre = nuevoMarcadorNombre,
                                            color = nuevoMarcadorColor
                                        )
                                    )
                                    nuevoMarcadorNombre = ""
                                    nuevoMarcadorColor = Color.Gray
                                }
                            }
                        ) {
                            Text("Agregar")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { mostrarModalGestionMarcadores = false }) {
                            Text("Cerrar")
                        }
                    }
                )
            }


        }


    }
}

/* =========================================================
   BUILD CHAT ROWS
========================================================= */

private fun buildChatRows(mensajes: List<MensajeUI>): List<ChatRow> {
    if (mensajes.isEmpty()) return emptyList()

    val rows = mutableListOf<ChatRow>()
    var lastDay: String? = null

    for (msg in mensajes) {
        val day = formatDayHeaderSafe(msg.fechaRaw)
        if (day != lastDay) {
            rows.add(ChatRow.FechaHeader(day))
            lastDay = day
        }
        rows.add(ChatRow.MensajeItem(msg))
    }

    return rows
}

/* =========================================================
   NETWORK
========================================================= */
fun togglePausaConversacion(
    token: String,
    email: String,
    numero: String,
    status: String,
    onDone: () -> Unit
) {
    val endpoint = if (status == "pausado") "reanudar" else "pausar"

    val body = JSONObject()
        .put("telefono", numero)
        .put("cliente", email)
        .toString()
        .toRequestBody("application/json".toMediaType())

    val request = Request.Builder()
        .url("$API_URL/api/core/$endpoint")
        .addHeader("Authorization", "Bearer $token")
        .post(body)
        .build()

    OkHttpClient().newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {}
        override fun onResponse(call: Call, response: Response) {
            onDone()
        }
    })
}

fun eliminarConversacion(
    token: String,
    email: String,
    numero: String,
    onDone: () -> Unit
) {
    // 1️⃣ DELETE conversación activa
    val deleteRequest = Request.Builder()
        .url("$API_URL/api/whatsapp/conversaciones-activas/$email/$numero")
        .addHeader("Authorization", "Bearer $token")
        .delete()
        .build()

    OkHttpClient().newCall(deleteRequest).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {}

        override fun onResponse(call: Call, response: Response) {

            // 2️⃣ core eliminar-conversacion
            val body = JSONObject()
                .put("telefono", numero)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val coreRequest = Request.Builder()
                .url("$API_URL/api/core/eliminar-conversacion")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()

            OkHttpClient().newCall(coreRequest).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    onDone()
                }
            })
        }
    })
}


fun cargarConversaciones(
    token: String,
    email: String,
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
            val body = response.body?.string() ?: return

            val json = JSONObject(body)

            // Partimos del estado actual para NO perder "leido" cuando abres una conversación
            val updatedState = ConversacionesState.conversaciones.value.toMutableMap()

            val keys = json.keys()
            while (keys.hasNext()) {
                val numero = keys.next()
                val obj = json.getJSONObject(numero)

                val prev = ConversacionesState.conversaciones.value[numero]
                val prevDate = prev?.optString("updated_at", "") ?: ""
                val newDate = obj.optString("updated_at", "")

                if (prev == null) {
                    // Primera vez que vemos este número -> debe quedar NO LEÍDO (punto verde)
                    obj.put("leido", false)

                    // Importante: NO notificar aquí, porque es carga inicial
                } else {
                    // Ya existía: si cambió la fecha, llegó mensaje nuevo
                    val cambioFecha = prevDate.isNotEmpty() && newDate.isNotEmpty() && prevDate != newDate

                    if (cambioFecha) {
                        obj.put("leido", false)
                        Log.d("new json2", obj.toString())
                        // 🔔 Notificar SOLO cuando hay cambio real de fecha
                        if (conversacionAbierta != numero) {
                        mostrarNotificacion(
                            AppContext.app,
                            numero,
                            obj.optString("ultimoTexto", "Nuevo mensaje")
                        )
                    }
                    } else {
                        // No cambió la fecha: conservar el estado de leído anterior
                        obj.put("leido", prev.optBoolean("leido", true))
                    }
                }

                updatedState[numero] = obj
            }
            val backendKeys = json.keys().asSequence().toSet()
            updatedState.keys.retainAll(backendKeys)

            // Guardar el estado para que en el siguiente refresh ya no "re-dispare" la notificación
            val ordered = updatedState.entries
                .sortedByDescending { entry ->
                    entry.value.optString("updated_at", "")
                }
                .associate { it.toPair() }
//            ConversacionesState.conversaciones.value = updatedState
//            onResult(updatedState)
            ConversacionesState.conversaciones.value = ordered
            onResult(ordered)
        }
    })
}




fun cargarMensajes(
    token: String,
    email: String,
    numero: String,
    onResult: (List<MensajeUI>) -> Unit
) {
    val request = Request.Builder()
        .url("$API_URL/api/whatsapp/conversaciones/$email/$numero/mensajes")
        .addHeader("Authorization", "Bearer $token")
        .build()

    OkHttpClient().newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {}
        override fun onResponse(call: Call, response: Response) {
            val raw = response.body?.string() ?: return
            if (!response.isSuccessful) return

            val arr = JSONObject(raw).optJSONArray("mensajes") ?: JSONArray()
            val list = mutableListOf<MensajeUI>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)

                val fecha = obj.optString("creado_en")
                    .ifBlank { obj.optString("created_at") }
                    .ifBlank { obj.optString("timestamp") }
                val estado = obj.optString("estado", "enviado")
                list.add(
                    MensajeUI(
                        texto = obj.optString("mensaje"),
                        fechaRaw = fecha,
                        esCliente = obj.optInt("es_cliente", 1) == 1,
                        estado = estado
                    )
                )
            }
            onResult(list)
        }
    })
}

fun enviarMensaje(
    token: String,
    email: String,
    numero: String,
    mensaje: String,
    onDone: () -> Unit
) {
    val body = JSONObject()
        .put("numero", numero)
        .put("mensaje", mensaje)
        .toString()
        .toRequestBody("application/json".toMediaType())

    val request = Request.Builder()
        .url("$API_URL/api/whatsapp/enviar-mensaje")
        .addHeader("Authorization", "Bearer $token")
        .addHeader("x-usuario", email)
        .post(body)
        .build()

    OkHttpClient().newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {}
        override fun onResponse(call: Call, response: Response) { onDone() }
    })
}

/* =========================================================
   UTILS
========================================================= */

private fun parseFecha(raw: String): Date? {
    return try {
        SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(raw)
    } catch (_: Exception) {
        null
    }
}

private fun formatDayHeaderSafe(raw: String): String {
    val date = parseFecha(raw) ?: return "Sin fecha"
    return SimpleDateFormat(
        "EEEE dd MMM yyyy",
        Locale("es", "MX")
    ).format(date)
}

fun formatearHora(raw: String): String {
    val date = parseFecha(raw) ?: return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
}

/* =========================================================
   STORAGE
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
