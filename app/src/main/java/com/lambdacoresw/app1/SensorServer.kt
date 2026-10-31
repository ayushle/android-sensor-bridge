package com.lambdacoresw.app1

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import java.time.Duration

class SensorServer(private val context: Context) {

    // ---------------------------------------------------------------------------
    // Shared flows — real-time priority: drop oldest when buffer is full
    // ---------------------------------------------------------------------------
    private val imuFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val cameraFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // ---------------------------------------------------------------------------
    // Sensor machinery
    // ---------------------------------------------------------------------------
    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val json = """{"x":$x,"y":$y,"z":$z}"""
            imuFlow.tryEmit(json)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    // ---------------------------------------------------------------------------
    // Ktor server
    // ---------------------------------------------------------------------------
    private var server: ApplicationEngine? = null

    /**
     * Feed a raw JPEG [ByteArray] frame into the camera broadcast flow.
     * Safe to call from any thread.
     */
    fun pushCameraFrame(frame: ByteArray) {
        cameraFlow.tryEmit(frame)
    }

    /**
     * Register sensors and start the embedded Ktor/Netty WebSocket server on port 8080.
     */
    fun start() {
        // Register accelerometer at the fastest delivery rate
        accelerometer?.also { sensor ->
            sensorManager.registerListener(
                sensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }

        server = embeddedServer(Netty, port = 8080) {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(15)
                timeout = Duration.ofSeconds(15)
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }

            routing {
                // IMU endpoint — streams JSON accelerometer readings
                webSocket("/imu") {
                    imuFlow.collect { json ->
                        send(Frame.Text(json))
                    }
                }

                // Camera endpoint — streams raw JPEG frames as binary
                webSocket("/camera") {
                    cameraFlow.collect { bytes ->
                        send(Frame.Binary(true, bytes))
                    }
                }
            }
        }.start(wait = false)
    }

    /**
     * Unregister sensors and gracefully stop the Ktor server.
     */
    fun stop() {
        sensorManager.unregisterListener(sensorListener)
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        server = null
    }
}

