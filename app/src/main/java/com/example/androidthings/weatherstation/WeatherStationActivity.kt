/*
 * Copyright 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.weatherstation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.KeyEvent
import android.view.animation.LinearInterpolator

import com.google.android.things.contrib.driver.apa102.Apa102
import com.google.android.things.contrib.driver.bmx280.Bmx280SensorDriver
import com.google.android.things.contrib.driver.button.Button
import com.google.android.things.contrib.driver.button.ButtonInputDriver
import com.google.android.things.contrib.driver.ht16k33.AlphanumericDisplay
import com.google.android.things.contrib.driver.pwmspeaker.Speaker
import com.google.android.things.pio.Gpio
import com.google.android.things.pio.PeripheralManager
import kotlinx.android.synthetic.main.activity_main.*

import java.io.IOException

class WeatherStationActivity : Activity() {

    private lateinit var mSensorManager: SensorManager

    private lateinit var mButtonInputDriver: ButtonInputDriver

    private lateinit var mEnvironmentalSensorDriver: Bmx280SensorDriver

    private lateinit var mLed: Gpio

    private lateinit var mSpeaker: Speaker

    private var mDisplay: AlphanumericDisplay? = null
    private var mDisplayMode = DisplayMode.PRESSURE

    private var mLedstrip: Apa102? = null
    private val mRainbow = IntArray(7)

    private var mLastTemperature = 0f
    private var mLastPressure = 0f

    private var mPubsubPublisher: PubsubPublisher? = null

    private lateinit var mHandler: Handler

    // Callback used when we register the BMP280 sensor driver with the system's SensorManager.
    private val mDynamicSensorCallback = object : SensorManager.DynamicSensorCallback() {
        override fun onDynamicSensorConnected(sensor: Sensor) {
            if (sensor.type == Sensor.TYPE_AMBIENT_TEMPERATURE) {
                // Our sensor is connected. Start receiving temperature data.
                mSensorManager.registerListener(mTemperatureListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                if (mPubsubPublisher != null) {
                    mSensorManager.registerListener(mPubsubPublisher?.temperatureListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                }
            } else if (sensor.type == Sensor.TYPE_PRESSURE) {
                // Our sensor is connected. Start receiving pressure data.
                mSensorManager.registerListener(mPressureListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                if (mPubsubPublisher != null) {
                    mSensorManager.registerListener(mPubsubPublisher?.pressureListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
        }

        override fun onDynamicSensorDisconnected(sensor: Sensor) {
            super.onDynamicSensorDisconnected(sensor)
        }
    }

    // Callback when SensorManager delivers temperature data.
    private val mTemperatureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            mLastTemperature = event.values[0]
            Log.d(TAG, "sensor changed: $mLastTemperature")
            if (mDisplayMode == DisplayMode.TEMPERATURE) {
                updateDisplay(mLastTemperature)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            Log.d(TAG, "accuracy changed: $accuracy")
        }
    }

    // Callback when SensorManager delivers pressure data.
    private val mPressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            mLastPressure = event.values[0]
            Log.d(TAG, "sensor changed: $mLastPressure")
            if (mDisplayMode == DisplayMode.PRESSURE) {
                updateDisplay(mLastPressure)
            }
            updateBarometer(mLastPressure)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            Log.d(TAG, "accuracy changed: $accuracy")
        }
    }

    private enum class DisplayMode {
        TEMPERATURE,
        PRESSURE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Started Weather Station")

        setContentView(R.layout.activity_main)

        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        mHandler = object : Handler() {
            private var mBarometerImage = -1

            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_UPDATE_BAROMETER_UI -> {
                        val img: Int = when {
                            mLastPressure > BAROMETER_RANGE_SUNNY -> R.drawable.ic_sunny
                            mLastPressure < BAROMETER_RANGE_RAINY -> R.drawable.ic_rainy
                            else -> R.drawable.ic_cloudy
                        }
                        if (img != mBarometerImage) {
                            imageView.setImageResource(img)
                            mBarometerImage = img
                        }
                    }
                }
            }
        }

        // GPIO button that generates 'A' keypresses (handled by onKeyUp method)
        try {
            mButtonInputDriver = ButtonInputDriver(BoardDefaults.buttonGpioPin, Button.LogicState.PRESSED_WHEN_LOW, KeyEvent.KEYCODE_A)
            mButtonInputDriver.register()
            Log.d(TAG, "Initialized GPIO Button that generates a keypress with KEYCODE_A")
        } catch (e: IOException) {
            throw RuntimeException("Error initializing GPIO button", e)
        }

        // I2C
        // Note: In this sample we only use one I2C bus, but multiple peripherals can be connected
        // to it and we can access them all, as long as they each have a different address on the
        // bus. Many peripherals can be configured to use a different address, often by connecting
        // the pins a certain way; this may be necessary if the default address conflicts with
        // another peripheral's. In our case, the temperature sensor and the display have
        // different default addresses, so everything just works.
        try {
            mEnvironmentalSensorDriver = Bmx280SensorDriver(BoardDefaults.i2cBus)
            mSensorManager.registerDynamicSensorCallback(mDynamicSensorCallback)
            mEnvironmentalSensorDriver.registerTemperatureSensor()
            mEnvironmentalSensorDriver.registerPressureSensor()
            Log.d(TAG, "Initialized I2C BMP280")
        } catch (e: IOException) {
            throw RuntimeException("Error initializing BMP280", e)
        }

        try {
            mDisplay = AlphanumericDisplay(BoardDefaults.i2cBus)
            mDisplay?.setEnabled(true)
            mDisplay?.clear()
            Log.d(TAG, "Initialized I2C Display")
        } catch (e: IOException) {
            Log.e(TAG, "Error initializing display", e)
            Log.d(TAG, "Display disabled")
            mDisplay = null
        }

        // SPI ledstrip
        try {
            mLedstrip = Apa102(BoardDefaults.spiBus, Apa102.Mode.BGR)
            mLedstrip?.brightness = LEDSTRIP_BRIGHTNESS
            for (i in mRainbow.indices) {
                val hsv = floatArrayOf(i * 360f / mRainbow.size, 1.0f, 1.0f)
                mRainbow[i] = Color.HSVToColor(255, hsv)
            }
        } catch (e: IOException) {
            mLedstrip = null // Led strip is optional.
        }

        // GPIO led
        try {
            val pioManager = PeripheralManager.getInstance()
            mLed = pioManager.openGpio(BoardDefaults.ledGpioPin)
            mLed.setEdgeTriggerType(Gpio.EDGE_NONE)
            mLed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW)
            mLed.setActiveType(Gpio.ACTIVE_HIGH)
        } catch (e: IOException) {
            throw RuntimeException("Error initializing led", e)
        }

        // PWM speaker
        try {
            mSpeaker = Speaker(BoardDefaults.speakerPwmPin)
            val slide = ValueAnimator.ofFloat(440f, 440 * 4f)
            slide.duration = 50
            slide.repeatCount = 5
            slide.interpolator = LinearInterpolator()
            slide.addUpdateListener { animation ->
                try {
                    val v = animation.animatedValue as Float
                    mSpeaker.play(v.toDouble())
                } catch (e: IOException) {
                    throw RuntimeException("Error sliding speaker", e)
                }
            }
            slide.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    try {
                        mSpeaker.stop()
                    } catch (e: IOException) {
                        throw RuntimeException("Error sliding speaker", e)
                    }

                }
            })
            val handler = Handler(mainLooper)
            handler.postDelayed({ slide.start() }, SPEAKER_READY_DELAY_MS.toLong())
        } catch (e: IOException) {
            throw RuntimeException("Error initializing speaker", e)
        }

        // start Cloud PubSub Publisher if cloud credentials are present.
        val credentialId = resources.getIdentifier("credentials", "raw", packageName)
        if (credentialId != 0) {
            try {
                mPubsubPublisher = PubsubPublisher(this, "weatherstation", BuildConfig.PROJECT_ID, BuildConfig.PUBSUB_TOPIC, credentialId)
                mPubsubPublisher?.start()
            } catch (e: IOException) {
                Log.e(TAG, "error creating pubsub publisher", e)
            }

        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_A) {
            mDisplayMode = DisplayMode.TEMPERATURE
            updateDisplay(mLastTemperature)
            try {
                mLed.value = true
            } catch (e: IOException) {
                Log.e(TAG, "error updating LED", e)
            }

            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_A) {
            mDisplayMode = DisplayMode.PRESSURE
            updateDisplay(mLastPressure)
            try {
                mLed.value = false
            } catch (e: IOException) {
                Log.e(TAG, "error updating LED", e)
            }

            return true
        }
        return super.onKeyUp(keyCode, event)
    }


    override fun onDestroy() {
        super.onDestroy()

        // Clean up sensor registrations
        mSensorManager.unregisterListener(mTemperatureListener)
        mSensorManager.unregisterListener(mPressureListener)
        mSensorManager.unregisterDynamicSensorCallback(mDynamicSensorCallback)

        // Clean up peripheral.
        try {
            mEnvironmentalSensorDriver.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        try {
            mButtonInputDriver.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        try {
            mDisplay?.clear()
            mDisplay?.setEnabled(false)
            mDisplay?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error disabling display", e)
        } finally {
            mDisplay = null
        }

        try {
            mLedstrip?.brightness = 0
            mLedstrip?.write(IntArray(7))
            mLedstrip?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error disabling ledstrip", e)
        } finally {
            mLedstrip = null
        }

        try {
            mLed.value = false
            mLed.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error disabling led", e)
        }

        // clean up Cloud PubSub publisher.
        if (mPubsubPublisher != null) {
            mSensorManager.unregisterListener(mPubsubPublisher?.temperatureListener)
            mSensorManager.unregisterListener(mPubsubPublisher?.pressureListener)
            mPubsubPublisher?.close()
            mPubsubPublisher = null
        }
    }

    private fun updateDisplay(value: Float) {
        try {
            mDisplay?.display(value.toDouble())
        } catch (e: IOException) {
            Log.e(TAG, "Error setting display", e)
        }
    }

    private fun updateBarometer(pressure: Float) {
        // Update UI.
        if (!mHandler.hasMessages(MSG_UPDATE_BAROMETER_UI)) {
            mHandler.sendEmptyMessageDelayed(MSG_UPDATE_BAROMETER_UI, 100)
        }
        // Update led strip.
        if (mLedstrip == null) {
            return
        }
        val t = (pressure - BAROMETER_RANGE_LOW) / (BAROMETER_RANGE_HIGH - BAROMETER_RANGE_LOW)
        val n = Math.max(0, Math.min(Math.ceil((mRainbow.size * t).toDouble()).toInt(), mRainbow.size))
        val colors = IntArray(mRainbow.size)
        for (i in 0 until n) {
            val ri = mRainbow.size - 1 - i
            colors[ri] = mRainbow[ri]
        }
        try {
            mLedstrip?.write(colors)
        } catch (e: IOException) {
            Log.e(TAG, "Error setting ledstrip", e)
        }

    }

    companion object {

        private val TAG = WeatherStationActivity::class.java.simpleName
        private const val LEDSTRIP_BRIGHTNESS = 1
        private const val BAROMETER_RANGE_LOW = 965f
        private const val BAROMETER_RANGE_HIGH = 1035f
        private const val BAROMETER_RANGE_SUNNY = 1010f
        private const val BAROMETER_RANGE_RAINY = 990f

        private const val MSG_UPDATE_BAROMETER_UI = 1

        private const val SPEAKER_READY_DELAY_MS = 300
    }
}
