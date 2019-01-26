package com.example.androidthings.weatherstation

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Build
import android.util.Log
import com.amazonaws.mobileconnectors.iot.AWSIotKeystoreHelper
import com.amazonaws.mobileconnectors.iot.AWSIotMqttLastWillAndTestament
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos
import org.json.JSONObject
import java.security.KeyStore
import java.util.*

class PubsubPublisher(keystorePath: String) {
    private val clientId = UUID.randomUUID().toString()

    private val mqttManager = AWSIotMqttManager(clientId, CUSTOMER_SPECIFIC_ENDPOINT)

    private var clientKeyStore: KeyStore? = null

    private var lastTemperature = Float.NaN
    private var lastPressure = Float.NaN

    private val messagePayload: JSONObject
        get() {
            val sensorData = JSONObject()
            sensorData.put("deviceId", Build.DEVICE)
            if (!lastTemperature.isNaN()) {
                sensorData.put("temperature", lastTemperature.toString())
            }
            if (!lastPressure.isNaN()) {
                sensorData.put("pressure", lastPressure.toString())
            }
            return sensorData
        }

    val temperatureListener: SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            lastTemperature = event.values[0]
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    val pressureListener: SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            lastPressure = event.values[0]
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    init {
        // Set keepalive to 10 seconds.  Will recognize disconnects more quickly but will also send
        // MQTT pings every 10 seconds.
        mqttManager.keepAlive = 10

        // Set Last Will and Testament for MQTT.  On an unclean disconnect (loss of connection)
        // AWS IoT will publish this message to alert other clients.
        val lwt = AWSIotMqttLastWillAndTestament("my/lwt/topic", "Android client lost connection", AWSIotMqttQos.QOS0)
        mqttManager.mqttLastWillAndTestament = lwt

        val keystoreName = KEYSTORE_NAME
        val keystorePassword = KEYSTORE_PASSWORD
        val certificateId = CERTIFICATE_ID

        try {
            if (AWSIotKeystoreHelper.isKeystorePresent(keystorePath, keystoreName)) {
                if (AWSIotKeystoreHelper.keystoreContainsAlias(certificateId, keystorePath, keystoreName, keystorePassword)) {
                    Log.i(TAG, "Certificate $certificateId found in keystore - using for MQTT.")
                    // load keystore from file into memory to pass on connection
                    clientKeyStore = AWSIotKeystoreHelper.getIotKeystore(certificateId, keystorePath, keystoreName, keystorePassword)
                } else {
                    Log.i(TAG, "Key/cert $certificateId not found in keystore.")
                }
            } else {
                Log.i(TAG, "Keystore $keystorePath/$keystoreName not found.")
            }
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "An error occurred retrieving cert/key from keystore.", e)
        }
    }

    fun start() {
        try {
            mqttManager.connect(clientKeyStore) { status, throwable ->
                Log.d(TAG, "Status = $status")
                if (throwable != null) {
                    Log.e(TAG, "Connection error.", throwable)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error.", e)
        }
    }

    fun publish() {
        try {
            mqttManager.publishString(messagePayload.toString(), TOPIC, AWSIotMqttQos.QOS0)
        } catch (e: Exception) {
            Log.e(TAG, "Publish error.", e)
        }
    }

    fun close() {
        try {
            mqttManager.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error.", e)
        }
    }

    companion object {
        private val TAG = PubsubPublisher::class.java.simpleName

        // IoT endpoint
        // AWS Iot CLI describe-endpoint call returns: XXXXXXXXXX.iot.<region>.amazonaws.com
        private const val CUSTOMER_SPECIFIC_ENDPOINT = "a2ze7ncyuhnjoe-ats.iot.ap-northeast-1.amazonaws.com"

        // Filename of KeyStore file on the filesystem
        private const val KEYSTORE_NAME = "iot_keystore"
        // Password for the private key in the KeyStore
        private const val KEYSTORE_PASSWORD = "password"
        // Certificate and key aliases in the KeyStore
        private const val CERTIFICATE_ID = "default"

        private const val TOPIC = "status/weather_station"
    }
}