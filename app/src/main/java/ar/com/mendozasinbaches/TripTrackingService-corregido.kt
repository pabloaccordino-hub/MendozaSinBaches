package ar.com.mendozasinbaches

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Servicio de monitoreo de viaje.
 * - Mantiene ubicación activa mediante un servicio en primer plano.
 * - Consulta reportes publicados en Supabase.
 * - Advierte por proximidad radial (no calcula si el bache está sobre la ruta).
 * - Guarda detecciones del acelerómetro localmente para revisión manual.
 */
class TripTrackingService : Service(), LocationListener, SensorEventListener {

    companion object {
        const val ACTION_START = "ar.com.mendozasinbaches.START_TRIP"
        const val ACTION_STOP = "ar.com.mendozasinbaches.STOP_TRIP"

        private const val SERVICE_CHANNEL_ID = "msb_trip_service"
        private const val ALERT_CHANNEL_ID = "msb_trip_alerts"
        private const val NOTIFICATION_ID = 7301

        private const val PREFS = "msb_trip_data"
        private const val KEY_PENDING = "pending_detections"

        private const val ALERT_RADIUS_METERS = 150f
        private const val ALERT_REPEAT_MS = 180_000L
        private const val HAZARD_REFRESH_MS = 60_000L
        private const val SENSOR_THRESHOLD = 2.7f
        private const val SENSOR_COOLDOWN_MS = 1_800L

        private const val SUPABASE_URL = "https://uqluaeyncxyuawsvosnn.supabase.co"
        // Clave publicable del cliente. Nunca colocar aquí una service_role key.
        private const val SUPABASE_KEY = "sb_publishable__gBqxSO2cHoPHmsBU8Qtow_iXaMpgau"

        fun getPendingDetections(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PENDING, "[]") ?: "[]"

        fun clearPendingDetections(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PENDING, "[]")
                .apply()
        }
    }

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    @Volatile
    private var lastLocation: Location? = null

    @Volatile
    private var hazards: List<JSONObject> = emptyList()

    private val lastAlertAt = mutableMapOf<String, Long>()
    private val lastCloudFetch = AtomicLong(0L)

    @Volatile
    private var lastCandidateAt = 0L

    @Volatile
    private var tracking = false

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startTracking()
            else -> {
                // No se reactiva automáticamente tras una terminación del proceso
                // si Android vuelve a crear el servicio sin una acción explícita.
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startTracking() {
        if (tracking) return

        try {
            startForeground(NOTIFICATION_ID, buildServiceNotification("Preparando GPS…"))
        } catch (_: Exception) {
            stopSelf()
            return
        }

        if (!hasLocationPermission()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        tracking = true

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 3_000L, 5f, this
                )
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 5_000L, 10f, this
                )
            }

            // Recupera una última posición conocida para no esperar el primer fix.
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestKnown = listOfNotNull(lastGps, lastNetwork)
                .minByOrNull { it.accuracy }

            if (bestKnown != null) {
                lastLocation = bestKnown
                checkNearbyHazards(bestKnown)
            }
        } catch (_: SecurityException) {
            tracking = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        } catch (_: Exception) {
            // Si un proveedor no está disponible, el otro todavía puede funcionar.
        }

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        fetchHazardsIfNeeded(force = true)
        updateServiceNotification("Monitoreo de viaje activo")
    }

    private fun stopTracking() {
        tracking = false
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        sensorManager.unregisterListener(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    override fun onLocationChanged(location: Location) {
        if (!tracking) return
        if (location.accuracy > 70f) return

        lastLocation = location
        fetchHazardsIfNeeded()
        checkNearbyHazards(location)

        updateServiceNotification(
            "Viaje activo · precisión GPS ±${location.accuracy.toInt()} m"
        )
    }

    private fun fetchHazardsIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val previous = lastCloudFetch.get()

        if (!force && now - previous < HAZARD_REFRESH_MS) return
        if (!lastCloudFetch.compareAndSet(previous, now) && !force) return

        thread(name = "MSB-Supabase-Reportes") {
            var connection: HttpURLConnection? = null
            try {
                val endpoint =
                    "$SUPABASE_URL/rest/v1/reportes" +
                    "?select=id,tipo,calle,departamento,gravedad,lat,lng,estado" +
                    "&estado=neq.Solucionado"

                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12_000
                    readTimeout = 12_000
                    setRequestProperty("apikey", SUPABASE_KEY)
                    setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                    setRequestProperty("Accept", "application/json")
                }

                val status = connection.responseCode
                if (status !in 200..299) return@thread

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONArray(body)
                val fresh = ArrayList<JSONObject>(json.length())

                for (i in 0 until json.length()) {
                    val item = json.optJSONObject(i) ?: continue
                    val lat = item.optDouble("lat", Double.NaN)
                    val lng = item.optDouble("lng", Double.NaN)
                    if (!lat.isNaN() && !lng.isNaN() &&
                        lat in -90.0..90.0 && lng in -180.0..180.0
                    ) {
                        fresh.add(item)
                    }
                }

                // Se reemplaza la lista completa de una sola vez; no se modifica
                // mientras el hilo principal está recorriéndola.
                hazards = fresh
            } catch (_: Exception) {
                // Se conservan los últimos reportes válidos si falla la conexión.
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun checkNearbyHazards(current: Location) {
        if (!tracking) return

        val now = System.currentTimeMillis()
        val snapshot = hazards

        for (item in snapshot) {
            val lat = item.optDouble("lat", Double.NaN)
            val lng = item.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) continue

            val target = Location("report").apply {
                latitude = lat
                longitude = lng
            }

            val distance = current.distanceTo(target)
            val id = item.optString("id").ifBlank { "${lat}_${lng}" }
            val previousAlert = lastAlertAt[id] ?: 0L

            if (distance <= ALERT_RADIUS_METERS && now - previousAlert >= ALERT_REPEAT_MS) {
                lastAlertAt[id] = now
                warnDriver(item, distance)
            }
        }
    }

    private fun warnDriver(hazard: JSONObject, distance: Float) {
        val title = "Atención: peligro vial cercano"
        val type = hazard.optString("tipo", "Bache")
        val street = hazard.optString("calle", "ubicación registrada")
        val detail = "$type a ${distance.toInt()} metros · $street"

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = (hazard.optString("id").hashCode() and 0x7fffffff)

        manager.notify(
            notificationId,
            NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        )

        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        450L, VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(450L)
            }
        } catch (_: Exception) {
        }

        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 350)
            // Liberar el recurso luego de emitir el tono.
            thread {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                }
                tone.release()
            }
        } catch (_: Exception) {
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!tracking || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        val deviation = abs(magnitude - SensorManager.GRAVITY_EARTH)
        val now = System.currentTimeMillis()

        if (deviation >= SENSOR_THRESHOLD &&
            now - lastCandidateAt > SENSOR_COOLDOWN_MS
        ) {
            lastCandidateAt = now
            val location = lastLocation ?: return
            if (location.accuracy > 50f) return
            saveCandidate(location, deviation)
        }
    }

    private fun saveCandidate(location: Location, intensity: Float) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val array = try {
                JSONArray(prefs.getString(KEY_PENDING, "[]"))
            } catch (_: Exception) {
                JSONArray()
            }

            array.put(
                JSONObject().apply {
                    put("id", "sensor-${System.currentTimeMillis()}")
                    put("tipo", "Posible bache detectado por sensor")
                    put("lat", location.latitude)
                    put("lng", location.longitude)
                    put("accuracy_m", location.accuracy)
                    put("intensidad", intensity)
                    put("velocidad_kmh", location.speed * 3.6f)
                    put("fecha", System.currentTimeMillis())
                    put("estado", "Pendiente de revisión")
                }
            )

            prefs.edit().putString(KEY_PENDING, array.toString()).apply()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Servicio de viaje",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Indica que el monitoreo del viaje está activo"
            }

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Alertas de peligros viales",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos de baches y peligros viales próximos"
                enableVibration(true)
            }

            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun buildServiceNotification(text: String): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = openAppIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
        }

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Mendoza Sin Baches")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateServiceNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildServiceNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }
}
