package com.nexus.cli

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import java.util.Locale

class SensorCommand(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeListener: SensorEventListener? = null
    private var stopRunnable: Runnable? = null

    fun execute(args: List<String>, output: (String) -> Unit) {
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "list" -> listSensors(output)
            "light" -> stream(
                type = Sensor.TYPE_LIGHT,
                label = "LIGHT",
                output = output
            ) { e -> String.format(Locale.US, "lux: %.1f", e.values[0]) }
            "accel" -> stream(
                type = Sensor.TYPE_ACCELEROMETER,
                label = "ACCEL",
                output = output
            ) { e ->
                String.format(Locale.US, "X=%.2f  Y=%.2f  Z=%.2f  (m/s²)", e.values[0], e.values[1], e.values[2])
            }
            "stop" -> {
                stop()
                output("[sensor] listener dihentikan.")
            }
            else -> output("Usage: sensor <list|light|accel|stop>")
        }
    }

    private fun listSensors(output: (String) -> Unit) {
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        if (sensors.isEmpty()) {
            output("[sensor] tidak ada sensor tersedia.")
            return
        }
        val sb = StringBuilder("[sensor] ${sensors.size} sensor ditemukan:")
        sensors.forEachIndexed { i, s ->
            sb.append("\n  ${i + 1}. ${s.name} (${s.vendor})")
        }
        output(sb.toString())
    }

    private fun stream(
        type: Int,
        label: String,
        output: (String) -> Unit,
        format: (SensorEvent) -> String
    ) {
        stop()
        val sensor = sensorManager.getDefaultSensor(type)
        if (sensor == null) {
            output("[sensor] sensor $label tidak tersedia di perangkat ini.")
            return
        }

        var lastPrint = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val now = System.currentTimeMillis()
                if (now - lastPrint < PRINT_INTERVAL_MS) return
                lastPrint = now
                output("[$label] ${format(event)}")
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        activeListener = listener
        val ok = sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        if (!ok) {
            activeListener = null
            output("[sensor] gagal mendaftarkan listener $label.")
            return
        }

        output("[sensor] membaca $label selama ${STREAM_DURATION_MS / 1000} detik (ketik 'sensor stop' untuk berhenti)...")
        val stopper = Runnable {
            stop()
            output("[sensor] $label selesai, listener dihentikan.")
        }
        stopRunnable = stopper
        mainHandler.postDelayed(stopper, STREAM_DURATION_MS)
    }

    fun stop() {
        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        stopRunnable = null
        activeListener?.let { sensorManager.unregisterListener(it) }
        activeListener = null
    }

    companion object {
        private const val STREAM_DURATION_MS = 10_000L
        private const val PRINT_INTERVAL_MS = 500L
    }
}