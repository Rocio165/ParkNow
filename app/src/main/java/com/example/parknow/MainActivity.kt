package com.example.parknow

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

    private val esp32BaseUrl = "http://192.168.4.1"
    private val ssidActual = "Red_Robotica"
    private val passActual = "password123"

    private lateinit var auth: FirebaseAuth
    private lateinit var dbHelper: ParkNowDBHelper

    private lateinit var cardEstadoConexion: MaterialCardView
    private lateinit var tvEstadoConexion: TextView
    private lateinit var tvColaOffline: TextView
    private lateinit var tvEstadoNodos: TextView
    private lateinit var tvEstadoUltrasonico: TextView
    private lateinit var tvEstadoBarrera: TextView
    private lateinit var tvHeartbeatMonitor: TextView

    private lateinit var tvPuestoAsignado: TextView
    private lateinit var tvSubtituloPuesto: TextView
    private lateinit var spinnerPuestos: Spinner
    private lateinit var btnLiberarEspacio: MaterialButton

    private lateinit var btnReservarA12: MaterialButton
    private lateinit var btnReservarB04: MaterialButton
    private lateinit var btnReservarC08: MaterialButton

    private val httpLock = Any()
    private var ultimoEnvioMs = 0L

    @Volatile
    private var estaEnLinea = false

    @Volatile
    private var monitoreando = true

    private var pollingThread: Thread? = null
    private var paquetesTelemetria = 0
    private var actualizandoSpinner = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        conectarAlEsp32()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true

        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        dbHelper = ParkNowDBHelper(this)

        val currentUser = auth.currentUser
        if (currentUser == null) {
            irAlLogin()
            return
        }

        val headerIoT = findViewById<LinearLayout>(R.id.headerIoT)
        ViewCompat.setOnApplyWindowInsetsListener(headerIoT) { view, insets ->
            val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(
                view.paddingLeft,
                statusBarInset + 12,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        cardEstadoConexion = findViewById(R.id.cardEstadoConexion)
        tvEstadoConexion = findViewById(R.id.tvEstadoConexion)
        tvColaOffline = findViewById(R.id.tvColaOffline)
        tvEstadoNodos = findViewById(R.id.tvEstadoNodos)
        tvEstadoUltrasonico = findViewById(R.id.tvEstadoUltrasonico)
        tvEstadoBarrera = findViewById(R.id.tvEstadoBarrera)
        tvHeartbeatMonitor = findViewById(R.id.tvHeartbeatMonitor)

        val tvRedInfo = findViewById<TextView>(R.id.tvRedInfo)
        val btnConectarEsp32 = findViewById<MaterialButton>(R.id.btnConectarEsp32)

        val layoutMiEspacio = findViewById<LinearLayout>(R.id.layoutMiEspacio)
        val layoutDisponibles = findViewById<LinearLayout>(R.id.layoutDisponibles)
        val layoutPerfil = findViewById<LinearLayout>(R.id.layoutPerfil)

        tvPuestoAsignado = findViewById(R.id.tvPuestoAsignado)
        tvSubtituloPuesto = findViewById(R.id.tvSubtituloPuesto)
        spinnerPuestos = findViewById(R.id.spinnerPuestos)
        btnLiberarEspacio = findViewById(R.id.btnLiberarEspacio)

        val btnAbrirBarrera = findViewById<MaterialButton>(R.id.btnAbrirBarrera)
        val btnCerrarBarrera = findViewById<MaterialButton>(R.id.btnCerrarBarrera)

        btnReservarA12 = findViewById(R.id.btnReservarA12)
        btnReservarB04 = findViewById(R.id.btnReservarB04)
        btnReservarC08 = findViewById(R.id.btnReservarC08)

        val btnHelpEnlace = findViewById<ImageButton>(R.id.btnHelpEnlace)
        val btnHelpTelemetria = findViewById<ImageButton>(R.id.btnHelpTelemetria)
        val btnHelpEstacionamiento = findViewById<ImageButton>(R.id.btnHelpEstacionamiento)
        val btnHelpDisponibles = findViewById<ImageButton>(R.id.btnHelpDisponibles)

        val tvCorreoUsuario = findViewById<TextView>(R.id.tvCorreoUsuario)
        val btnCambiarCorreo = findViewById<MaterialButton>(R.id.btnCambiarCorreo)
        val btnCambiarPassword = findViewById<MaterialButton>(R.id.btnCambiarPassword)
        val btnLogout = findViewById<MaterialButton>(R.id.btnLogout)
        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottomNavigation)

        tvCorreoUsuario.text = currentUser.email ?: "Conductor Activo"
        tvRedInfo.text = "$ssidActual • 192.168.4.1 (ESP32 + Arduino UNO R4)"

        val opcionesPuestos = arrayOf(
            "Seleccionar puesto...",
            "Puesto A-12 • Zona Techada (Sensor Activo)",
            "Puesto B-04 • Zona Norte (Salida)",
            "Puesto C-08 • Zona Visitas"
        )
        val adapterPuestos = ArrayAdapter(this, android.R.layout.simple_spinner_item, opcionesPuestos)
        adapterPuestos.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPuestos.adapter = adapterPuestos

        spinnerPuestos.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (actualizandoSpinner) return
                if (position == 0) return

                val codigo = when (position) {
                    1 -> "A-12"
                    2 -> "B-04"
                    3 -> "C-08"
                    else -> "SIN_ASIGNAR"
                }

                if (codigo == dbHelper.obtenerPuestoLocal()) return

                if (codigo != "SIN_ASIGNAR") {
                    dbHelper.guardarPuestoLocal(codigo)
                    sincronizarEstadoPuesto(codigo)
                    enviarComandoIoT("puesto", "/reservar?puesto=$codigo", "Reserva Puesto $codigo", tvEstadoConexion, cardEstadoConexion, tvColaOffline)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val puestoGuardado = dbHelper.obtenerPuestoLocal()
        sincronizarEstadoPuesto(puestoGuardado)
        actualizarBadgeColaOffline(tvColaOffline)

        btnConectarEsp32.setOnClickListener {
            verificarPermisosYConectar()
        }

        cardEstadoConexion.setOnClickListener {
            verificarPermisosYConectar()
        }

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_mi_espacio -> {
                    layoutMiEspacio.visibility = View.VISIBLE
                    layoutDisponibles.visibility = View.GONE
                    layoutPerfil.visibility = View.GONE
                    true
                }
                R.id.nav_disponibles -> {
                    layoutMiEspacio.visibility = View.GONE
                    layoutDisponibles.visibility = View.VISIBLE
                    layoutPerfil.visibility = View.GONE
                    true
                }
                R.id.nav_perfil -> {
                    layoutMiEspacio.visibility = View.GONE
                    layoutDisponibles.visibility = View.GONE
                    layoutPerfil.visibility = View.VISIBLE
                    true
                }
                else -> false
            }
        }

        btnAbrirBarrera.setOnClickListener {
            if (!permitirEnvio()) return@setOnClickListener
            enviarComandoIoT("barrera", "/activar", "Abrir Barrera 90°", tvEstadoConexion, cardEstadoConexion, tvColaOffline)
        }

        btnCerrarBarrera.setOnClickListener {
            if (!permitirEnvio()) return@setOnClickListener
            enviarComandoIoT("barrera", "/reposo", "Cerrar Barrera 0°", tvEstadoConexion, cardEstadoConexion, tvColaOffline)
        }

        fun manejarAccionPuesto(codigo: String) {
            if (!permitirEnvio()) return
            val puestoActual = dbHelper.obtenerPuestoLocal()
            if (puestoActual == codigo) {
                dbHelper.guardarPuestoLocal("SIN_ASIGNAR")
                sincronizarEstadoPuesto("SIN_ASIGNAR")
                enviarComandoIoT("puesto", "/reservar?puesto=LIBRE", "Espacio $codigo Liberado", tvEstadoConexion, cardEstadoConexion, tvColaOffline)
            } else {
                dbHelper.guardarPuestoLocal(codigo)
                sincronizarEstadoPuesto(codigo)
                enviarComandoIoT("puesto", "/reservar?puesto=$codigo", "Reserva Puesto $codigo", tvEstadoConexion, cardEstadoConexion, tvColaOffline)
                bottomNavigation.selectedItemId = R.id.nav_mi_espacio
            }
        }

        btnReservarA12.setOnClickListener { manejarAccionPuesto("A-12") }
        btnReservarB04.setOnClickListener { manejarAccionPuesto("B-04") }
        btnReservarC08.setOnClickListener { manejarAccionPuesto("C-08") }

        btnLiberarEspacio.setOnClickListener {
            if (!permitirEnvio()) return@setOnClickListener
            dbHelper.guardarPuestoLocal("SIN_ASIGNAR")
            sincronizarEstadoPuesto("SIN_ASIGNAR")
            enviarComandoIoT("puesto", "/reservar?puesto=LIBRE", "Espacio Liberado", tvEstadoConexion, cardEstadoConexion, tvColaOffline)
        }

        btnHelpEnlace.setOnClickListener {
            mostrarDialogoAyuda("Enlace ESP32", "Conexión Wi-Fi nativa al Access Point del ESP32 ($ssidActual en 192.168.4.1).\n\nMuestra el estado del enlace en tiempo real (En línea / Offline) y administra la cola de comandos SQLite cuando no hay señal.")
        }
        btnHelpTelemetria.setOnClickListener {
            mostrarDialogoAyuda("Telemetría y Sensores", "Monitoreo en tiempo real de los sensores del ESP32 y Arduino UNO R4:\n\n• Sensor Ultrasónico HC-SR04: Detección y distancia (<20 cm) + LED Indicador.\n• Servo Barrera SG90: Ángulo actual del servomotor (0° cerrada / 90° abierta).\n• Heartbeat: Registro en vivo de lecturas recibidas.")
        }
        btnHelpEstacionamiento.setOnClickListener {
            mostrarDialogoAyuda("Control y Estacionamiento", "Control manual de la barrera de acceso mediante comandos HTTP:\n\n• Abrir (90°): Envía /activar al ESP32.\n• Cerrar (0°): Envía /reposo al ESP32.\n• Selector / Liberar: Reserva o cancela el espacio asignado.")
        }
        btnHelpDisponibles.setOnClickListener {
            mostrarDialogoAyuda("Espacios Disponibles", "Listado de estacionamientos en el recinto:\n\n• Espacio A-12 (Zona Techada • Acceso Principal)\n• Espacio B-04 (Zona Norte • Salida)\n• Espacio C-08 (Zona Visitas • Sensor Activo)\n\nPulsar 'Reservar' guarda tu elección localmente y notifica al ESP32.")
        }

        btnCambiarCorreo.setOnClickListener {
            mostrarDialogoCambiarCorreo(tvCorreoUsuario)
        }
        btnCambiarPassword.setOnClickListener {
            mostrarDialogoCambiarPassword()
        }

        btnLogout.setOnClickListener {
            monitoreando = false
            try {
                if (networkCallback != null) {
                    connectivityManager?.unregisterNetworkCallback(networkCallback!!)
                    networkCallback = null
                }
                connectivityManager?.bindProcessToNetwork(null)
            } catch (_: Exception) {}
            auth.signOut()
            irAlLogin()
        }
    }

    override fun onResume() {
        super.onResume()
        monitoreando = true
        if (auth.currentUser != null) {
            iniciarPollingTelemetria()
        }
    }

    override fun onPause() {
        monitoreando = false
        super.onPause()
    }

    override fun onDestroy() {
        monitoreando = false
        try {
            if (networkCallback != null) {
                connectivityManager?.unregisterNetworkCallback(networkCallback!!)
                networkCallback = null
            }
            connectivityManager?.bindProcessToNetwork(null)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun hayInternetDisponibilidad(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun sincronizarEstadoPuesto(puesto: String) {
        actualizandoSpinner = true

        actualizarVistaPuesto(puesto, tvPuestoAsignado, tvSubtituloPuesto)
        actualizarBotonesDisponibles(puesto)

        if (puesto == "SIN_ASIGNAR" || puesto == "LIBRE") {
            btnLiberarEspacio.visibility = View.GONE
            spinnerPuestos.setSelection(0)
        } else {
            btnLiberarEspacio.visibility = View.VISIBLE
            btnLiberarEspacio.text = "LIBERAR PUESTO $puesto"
            val index = when (puesto) {
                "A-12" -> 1
                "B-04" -> 2
                "C-08" -> 3
                else -> 0
            }
            spinnerPuestos.setSelection(index)
        }

        actualizandoSpinner = false
    }

    private fun actualizarVistaPuesto(puesto: String, tvTitulo: TextView, tvSub: TextView) {
        if (puesto == "SIN_ASIGNAR" || puesto == "LIBRE") {
            tvTitulo.text = "Sin Espacio Asignado"
            tvSub.text = "Reserva tu espacio en la pestaña 'Disponibles'."
        } else {
            val ubicacion = when (puesto) {
                "A-12" -> "Zona Techada • Acceso Principal"
                "B-04" -> "Zona Norte • Salida"
                "C-08" -> "Zona Visitas • Sensor Activo"
                else -> "Zona Estacionamiento"
            }
            tvTitulo.text = "Puesto $puesto"
            tvSub.text = ubicacion
        }
    }

    private fun actualizarBotonesDisponibles(puestoActual: String) {
        val colorReservar = ColorStateList.valueOf(0xFF0F4C81.toInt())
        val colorLiberar = ColorStateList.valueOf(0xFFDC2626.toInt())

        if (puestoActual == "A-12") {
            btnReservarA12.text = "Liberar"
            btnReservarA12.backgroundTintList = colorLiberar
        } else {
            btnReservarA12.text = "Reservar"
            btnReservarA12.backgroundTintList = colorReservar
        }

        if (puestoActual == "B-04") {
            btnReservarB04.text = "Liberar"
            btnReservarB04.backgroundTintList = colorLiberar
        } else {
            btnReservarB04.text = "Reservar"
            btnReservarB04.backgroundTintList = colorReservar
        }

        if (puestoActual == "C-08") {
            btnReservarC08.text = "Liberar"
            btnReservarC08.backgroundTintList = colorLiberar
        } else {
            btnReservarC08.text = "Reservar"
            btnReservarC08.backgroundTintList = colorReservar
        }
    }

    private fun mostrarDialogoAyuda(titulo: String, mensaje: String) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun mostrarDialogoCambiarCorreo(tvCorreo: TextView) {
        val user = auth.currentUser ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_cambiar_correo, null)
        val etClaveActual = dialogView.findViewById<EditText>(R.id.etClaveActual)
        val etNuevoCorreo = dialogView.findViewById<EditText>(R.id.etNuevoCorreo)

        AlertDialog.Builder(this)
            .setTitle("Cambiar Correo Electrónico")
            .setView(dialogView)
            .setPositiveButton("Actualizar") { _, _ ->
                if (!hayInternetDisponibilidad()) {
                    Toast.makeText(this, "Cambiar correo requiere conexión a Internet. Conectado a Wi-Fi local ESP32 (192.168.4.1).", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                val claveActual = etClaveActual.text.toString().trim()
                val nuevoCorreo = etNuevoCorreo.text.toString().trim()

                if (claveActual.isEmpty() || nuevoCorreo.isEmpty()) {
                    Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (!Patterns.EMAIL_ADDRESS.matcher(nuevoCorreo).matches()) {
                    Toast.makeText(this, "Ingresa un correo electrónico válido", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val credential = EmailAuthProvider.getCredential(user.email!!, claveActual)
                user.reauthenticate(credential).addOnCompleteListener { reauthTask ->
                    if (reauthTask.isSuccessful) {
                        user.verifyBeforeUpdateEmail(nuevoCorreo).addOnCompleteListener { updateTask ->
                            if (updateTask.isSuccessful) {
                                tvCorreo.text = nuevoCorreo
                                Toast.makeText(this, "Correo actualizado. Confirma la verificación en $nuevoCorreo", Toast.LENGTH_LONG).show()
                            } else {
                                user.updateEmail(nuevoCorreo).addOnCompleteListener { fallbackTask ->
                                    if (fallbackTask.isSuccessful) {
                                        tvCorreo.text = nuevoCorreo
                                        Toast.makeText(this, "Correo actualizado exitosamente", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this, "Error: ${fallbackTask.exception?.localizedMessage}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    } else {
                        Toast.makeText(this, "Contraseña actual incorrecta", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDialogoCambiarPassword() {
        val user = auth.currentUser ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_cambiar_clave, null)
        val etClaveActual = dialogView.findViewById<EditText>(R.id.etClaveActual)
        val etNuevaClave = dialogView.findViewById<EditText>(R.id.etNuevaClave)

        AlertDialog.Builder(this)
            .setTitle("Cambiar Contraseña")
            .setView(dialogView)
            .setPositiveButton("Actualizar") { _, _ ->
                if (!hayInternetDisponibilidad()) {
                    Toast.makeText(this, "Cambiar contraseña requiere conexión a Internet. Conectado a Wi-Fi local ESP32 (192.168.4.1).", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                val claveActual = etClaveActual.text.toString().trim()
                val nuevaClave = etNuevaClave.text.toString().trim()

                if (claveActual.isEmpty() || nuevaClave.length < 6) {
                    Toast.makeText(this, "La nueva clave debe tener al menos 6 caracteres", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val credential = EmailAuthProvider.getCredential(user.email!!, claveActual)
                user.reauthenticate(credential).addOnCompleteListener { reauthTask ->
                    if (reauthTask.isSuccessful) {
                        user.updatePassword(nuevaClave).addOnCompleteListener { updateTask ->
                            if (updateTask.isSuccessful) {
                                Toast.makeText(this, "Contraseña actualizada exitosamente", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Error al actualizar contraseña: ${updateTask.exception?.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        Toast.makeText(this, "Contraseña actual incorrecta", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun permitirEnvio(): Boolean {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoEnvioMs < 250) return false
        ultimoEnvioMs = ahora
        return true
    }

    private fun verificarPermisosYConectar() {
        val permisos = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permisos.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val faltantes = permisos.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltantes.isNotEmpty()) {
            requestPermissionsLauncher.launch(faltantes.toTypedArray())
        } else {
            conectarAlEsp32()
        }
    }

    private fun conectarAlEsp32() {
        try {
            connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

            if (networkCallback != null) {
                try {
                    connectivityManager?.unregisterNetworkCallback(networkCallback!!)
                } catch (_: Exception) {}
                networkCallback = null
            }

            val builder = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(ssidActual)
                    .setWpa2Passphrase(passActual)
                    .build()
                builder.setNetworkSpecifier(specifier)
            }
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    try {
                        connectivityManager?.bindProcessToNetwork(network)
                    } catch (_: Exception) {}
                }

                override fun onLost(network: Network) {
                    estaEnLinea = false
                    try {
                        connectivityManager?.bindProcessToNetwork(null)
                    } catch (_: Exception) {}
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                connectivityManager?.requestNetwork(builder.build(), networkCallback!!)
            }
        } catch (_: Exception) {}
    }

    private fun enviarComandoIoT(
        categoria: String,
        ruta: String,
        descripcion: String,
        tvEstado: TextView,
        cardEstado: MaterialCardView,
        tvCola: TextView
    ) {
        Thread {
            synchronized(httpLock) {
                var exito = false
                var conexion: HttpURLConnection? = null
                try {
                    val url = URL("$esp32BaseUrl$ruta")
                    conexion = url.openConnection() as HttpURLConnection
                    conexion.requestMethod = "GET"
                    conexion.connectTimeout = 600
                    conexion.readTimeout = 600

                    val responseCode = conexion.responseCode
                    if (responseCode == 200 || (responseCode == 404 && ruta.startsWith("/reservar"))) {
                        exito = true
                        estaEnLinea = true
                        runOnUiThread {
                            actualizarBadgeConexion(true, tvEstado, cardEstado)
                            if (ruta == "/activar") {
                                tvEstadoBarrera.text = "Servomotor SG90 • BARRERA ABIERTA (90°)"
                            } else if (ruta == "/reposo") {
                                tvEstadoBarrera.text = "Servomotor SG90 • BARRERA CERRADA (0°)"
                            }
                            Toast.makeText(this, "ESP32: $descripcion", Toast.LENGTH_SHORT).show()
                        }
                        sincronizarColaOffline(tvCola)
                    }
                } catch (_: Exception) {
                    exito = false
                } finally {
                    conexion?.disconnect()
                }

                if (!exito) {
                    estaEnLinea = false
                    dbHelper.encolarComandoOffline(categoria, ruta, descripcion)
                    runOnUiThread {
                        actualizarBadgeConexion(false, tvEstado, cardEstado)
                        actualizarBadgeColaOffline(tvCola)
                        Toast.makeText(this, "Sin Wi-Fi ESP32: '$descripcion' guardado en cola SQLite", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun sincronizarColaOffline(tvCola: TextView) {
        val pendientes = dbHelper.obtenerComandosPendientes()
        if (pendientes.isEmpty()) return

        for (item in pendientes) {
            var conexion: HttpURLConnection? = null
            try {
                val url = URL("$esp32BaseUrl${item.first}")
                conexion = url.openConnection() as HttpURLConnection
                conexion.requestMethod = "GET"
                conexion.connectTimeout = 600
                conexion.readTimeout = 600

                val code = conexion.responseCode
                if (code != 200 && !(code == 404 && item.first.startsWith("/reservar"))) return
            } catch (_: Exception) {
                return
            } finally {
                conexion?.disconnect()
            }
        }
        dbHelper.limpiarCola()
        runOnUiThread {
            actualizarBadgeColaOffline(tvCola)
            Toast.makeText(this, "Cola offline sincronizada con ESP32", Toast.LENGTH_SHORT).show()
        }
    }

    private fun iniciarPollingTelemetria() {
        if (pollingThread != null && pollingThread!!.isAlive) return
        monitoreando = true
        pollingThread = Thread {
            while (monitoreando && !isFinishing && !isDestroyed) {
                synchronized(httpLock) {
                    var conexion: HttpURLConnection? = null
                    try {
                        val url = URL("$esp32BaseUrl/estado")
                        conexion = url.openConnection() as HttpURLConnection
                        conexion.requestMethod = "GET"
                        conexion.connectTimeout = 600
                        conexion.readTimeout = 600

                        val responseCode = conexion.responseCode
                        if (responseCode == 200) {
                            val respuesta = conexion.inputStream.bufferedReader().use { it.readText() }
                            val json = JSONObject(respuesta)
                            val angulo = json.optInt("angulo", 0)
                            val abierta = json.optBoolean("barrera_abierta", false)
                            val hayVehiculo = json.optBoolean("ultrasonico", false)
                            val distancia = json.optInt("distancia_cm", 999)

                            paquetesTelemetria++
                            val horaActual = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                            estaEnLinea = true
                            runOnUiThread {
                                actualizarBadgeConexion(true, tvEstadoConexion, cardEstadoConexion)
                                tvEstadoNodos.text = "Hardware Activo • ESP32 (Barrera) + Arduino UNO R4 (Sensor)"
                                tvEstadoUltrasonico.text = if (hayVehiculo) {
                                    "Ultrasónico HC-SR04 • VEHÍCULO ($distancia cm) • LED Verde ON"
                                } else {
                                    "Ultrasónico HC-SR04 • Despejado ($distancia cm) • LED Rojo ON"
                                }
                                tvEstadoBarrera.text = if (abierta) {
                                    "Servomotor SG90 • BARRERA ABIERTA ($angulo°)"
                                } else {
                                    "Servomotor SG90 • BARRERA CERRADA ($angulo°)"
                                }
                                tvHeartbeatMonitor.text = "Monitor en vivo • Última señal: $horaActual • Lecturas OK: $paquetesTelemetria"
                            }
                            sincronizarColaOffline(tvColaOffline)
                        } else if (responseCode == 404) {
                            estaEnLinea = true
                            runOnUiThread {
                                actualizarBadgeConexion(true, tvEstadoConexion, cardEstadoConexion)
                                tvEstadoNodos.text = "Hardware Activo • ESP32 conectado (Sin telemetría JSON)"
                                tvEstadoUltrasonico.text = "Sensor Ultrasónico • No configurado en ESP32"
                            }
                        } else {
                            estaEnLinea = false
                            runOnUiThread {
                                actualizarBadgeConexion(false, tvEstadoConexion, cardEstadoConexion)
                                tvEstadoNodos.text = "Hardware • Sin enlace con Red_Robotica"
                                tvHeartbeatMonitor.text = "Monitor en vivo • Esperando reconexión..."
                            }
                        }
                    } catch (_: Exception) {
                        estaEnLinea = false
                        runOnUiThread {
                            actualizarBadgeConexion(false, tvEstadoConexion, cardEstadoConexion)
                            tvEstadoNodos.text = "Hardware • Sin enlace con Red_Robotica"
                            tvHeartbeatMonitor.text = "Monitor en vivo • Esperando reconexión..."
                        }
                    } finally {
                        conexion?.disconnect()
                    }
                }

                try {
                    Thread.sleep(if (estaEnLinea) 500 else 1500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        pollingThread?.start()
    }

    private fun actualizarBadgeConexion(enLinea: Boolean, tv: TextView, card: MaterialCardView) {
        if (enLinea) {
            tv.text = "ESP32 • EN LÍNEA"
            tv.setTextColor(0xFF15803D.toInt())
            card.setCardBackgroundColor(0xFFF0FDF4.toInt())
            card.strokeColor = 0xFF22C55E.toInt()
        } else {
            tv.text = "ESP32 • OFFLINE"
            tv.setTextColor(0xFFDC2626.toInt())
            card.setCardBackgroundColor(0xFFFEF2F2.toInt())
            card.strokeColor = 0xFFEF4444.toInt()
        }
    }

    private fun actualizarBadgeColaOffline(tvCola: TextView) {
        val pendientes = dbHelper.obtenerCantidadPendientes()
        if (pendientes > 0) {
            tvCola.visibility = View.VISIBLE
            tvCola.text = "$pendientes comando(s) pendiente(s) en cola SQLite"
        } else {
            tvCola.visibility = View.GONE
        }
    }

    private fun irAlLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}