package com.gameblabla.chiaki.session

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.gameblabla.chiaki.common.Preferences
import com.gameblabla.chiaki.lib.ControllerState

class StreamInput(val context: Context, val preferences: Preferences)
{
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	val controllerState: ControllerState get()
	{
		val controllerState = sensorControllerState or keyControllerState or motionControllerState

		val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		@Suppress("DEPRECATION")
		when(windowManager.defaultDisplay.rotation)
		{
			Surface.ROTATION_90 -> {
				controllerState.accelX *= -1.0f
				controllerState.accelZ *= -1.0f
				controllerState.gyroX *= -1.0f
				controllerState.gyroZ *= -1.0f
				controllerState.orientX *= -1.0f
				controllerState.orientZ *= -1.0f
			}
			else -> {}
		}

		// prioritize motion controller's l2 and r2 over key
		// (some controllers send only key, others both but key earlier than full press)
		if(motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

		return controllerState or touchControllerState
	}

	private val sensorControllerState = ControllerState() // from Motion Sensors

	private val keyControllerState = ControllerState() // from KeyEvents
	private val motionControllerState = ControllerState() // from MotionEvents
	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			controllerStateUpdated()
		}

	private val swapCrossMoon = preferences.swapCrossMoon

	// Read on every event (not cached) so the switch applies immediately, even
	// mid-stream, and never disagrees with what Settings shows.
	private val psComboEnabled get() = preferences.psComboEnabled
	private val mainHandler = Handler(Looper.getMainLooper())
	private var psReleaseRunnable: Runnable? = null

	// Wired by the activity to the statistics overlay.
	var statsToggleCallback: (() -> Unit)? = null

	// Also used by the hidden hotspot in the stream view, which is why it is not
	// private: the combo and the invisible button produce the same 80 ms tap.
	fun sendPsTap()
	{
		keyControllerState.buttons = keyControllerState.buttons or ControllerState.BUTTON_PS
		controllerStateUpdated()
		psReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
		val release = Runnable {
			keyControllerState.buttons = keyControllerState.buttons and ControllerState.BUTTON_PS.inv()
			controllerStateUpdated()
		}
		psReleaseRunnable = release
		mainHandler.postDelayed(release, PS_TAP_MS)
	}

	// Start + Select → one PS tap. Start and Select are held back for
	// COMBO_WINDOW_MS so a normal single press still reaches the console.
	//
	// L1 + R1 + Start → statistics overlay. Start rides the same hold-back; L1
	// and R1 go out at once, because delaying two buttons used constantly during
	// play would be felt. The price of the shortcut is the brief L1+R1 tap the
	// game sees before the overlay opens.
	private var startDown = false
	private var selectDown = false
	private var l1Down = false
	private var r1Down = false
	private var comboActive = false
	private var startSent = false
	private var selectSent = false
	private var startPendingRunnable: Runnable? = null
	private var selectPendingRunnable: Runnable? = null
	private var startTapRunnable: Runnable? = null
	private var selectTapRunnable: Runnable? = null

	private fun setKeyButton(mask: UInt, pressed: Boolean)
	{
		keyControllerState.buttons = if(pressed)
			keyControllerState.buttons or mask
		else
			keyControllerState.buttons and mask.inv()
		controllerStateUpdated()
	}

	private fun cancelPendingComboEmits()
	{
		startPendingRunnable?.let { mainHandler.removeCallbacks(it) }
		selectPendingRunnable?.let { mainHandler.removeCallbacks(it) }
		startPendingRunnable = null
		selectPendingRunnable = null
	}

	// A Start or Select tap that ends inside the hold-back window was never sent,
	// so it is sent now as a short press: pausing with a quick tap keeps working
	// even though the window has to be wide enough for three buttons.
	private fun sendKeyTap(mask: UInt, isStart: Boolean)
	{
		setKeyButton(mask, true)
		val release = Runnable { setKeyButton(mask, false) }
		if(isStart)
		{
			startTapRunnable?.let { mainHandler.removeCallbacks(it) }
			startTapRunnable = release
		}
		else
		{
			selectTapRunnable?.let { mainHandler.removeCallbacks(it) }
			selectTapRunnable = release
		}
		mainHandler.postDelayed(release, KEY_TAP_MS)
	}

	private fun comboMask(keyCode: Int): UInt = when(keyCode)
	{
		KeyEvent.KEYCODE_BUTTON_START -> ControllerState.BUTTON_OPTIONS
		KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerState.BUTTON_SHARE
		KeyEvent.KEYCODE_BUTTON_L1 -> ControllerState.BUTTON_L1
		else -> ControllerState.BUTTON_R1
	}

	// Once a combo fires nothing that was already sent may stay pressed: Options
	// and Share always, and L1/R1 only for the statistics combo, which is the one
	// that consumes them — holding L1 and pressing Start+Select for PS must not
	// steal the shoulder the player is holding.
	private fun releaseComboEmits(shoulders: Boolean)
	{
		if(startSent) { startSent = false; setKeyButton(ControllerState.BUTTON_OPTIONS, false) }
		if(selectSent) { selectSent = false; setKeyButton(ControllerState.BUTTON_SHARE, false) }
		if(shoulders && l1Down) setKeyButton(ControllerState.BUTTON_L1, false)
		if(shoulders && r1Down) setKeyButton(ControllerState.BUTTON_R1, false)
	}

	private fun setComboDown(keyCode: Int, down: Boolean)
	{
		when(keyCode)
		{
			KeyEvent.KEYCODE_BUTTON_START -> startDown = down
			KeyEvent.KEYCODE_BUTTON_SELECT -> selectDown = down
			KeyEvent.KEYCODE_BUTTON_L1 -> l1Down = down
			else -> r1Down = down
		}
	}

	private fun handleComboKey(event: KeyEvent, keyCode: Int)
	{
		val mask = comboMask(keyCode)
		val isStart = keyCode == KeyEvent.KEYCODE_BUTTON_START
		val isSelect = keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
		when(event.action)
		{
			KeyEvent.ACTION_DOWN -> {
				if(event.repeatCount > 0)
					return
				setComboDown(keyCode, true)
				if(!comboActive && startDown && l1Down && r1Down)
				{
					// Stats overlay. One toggle per press: while the combo stays
					// held nothing else is emitted.
					comboActive = true
					cancelPendingComboEmits()
					releaseComboEmits(true)
					statsToggleCallback?.invoke()
				}
				else if(!comboActive && startDown && selectDown)
				{
					comboActive = true
					cancelPendingComboEmits()
					releaseComboEmits(false)
					sendPsTap()
				}
				else if(isStart || isSelect)
				{
					// Held back so a combo can still form; while a combo is armed
					// they stay silent until everything is released.
					if(!comboActive)
					{
						val pending = Runnable {
							if(comboActive) return@Runnable
							if(isStart) startSent = true else selectSent = true
							setKeyButton(mask, true)
						}
						if(isStart) startPendingRunnable = pending else selectPendingRunnable = pending
						mainHandler.postDelayed(pending, COMBO_WINDOW_MS)
					}
				}
				else
					// L1/R1 go out at once, also right after a combo: the player
					// pressing them again means they want to play.
					setKeyButton(mask, true)
			}
			KeyEvent.ACTION_UP -> {
				setComboDown(keyCode, false)
				if(isStart)
				{
					startPendingRunnable?.let { mainHandler.removeCallbacks(it) }
					startPendingRunnable = null
					if(startSent) { startSent = false; setKeyButton(mask, false) }
					else if(!comboActive) sendKeyTap(mask, true)
				}
				else if(isSelect)
				{
					selectPendingRunnable?.let { mainHandler.removeCallbacks(it) }
					selectPendingRunnable = null
					if(selectSent) { selectSent = false; setKeyButton(mask, false) }
					else if(!comboActive) sendKeyTap(mask, false)
				}
				else
					setKeyButton(mask, false)
				if(comboActive && !startDown && !selectDown && !l1Down && !r1Down)
					comboActive = false
			}
		}
	}

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					sensorControllerState.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
				}
				Sensor.TYPE_GYROSCOPE -> {
					sensorControllerState.gyroX = event.values[1]
					sensorControllerState.gyroY = event.values[2]
					sensorControllerState.gyroZ = event.values[0]
				}
				Sensor.TYPE_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					sensorControllerState.orientX = q[2]
					sensorControllerState.orientY = q[3]
					sensorControllerState.orientZ = q[1]
					sensorControllerState.orientW = q[0]
				}
				else -> return
			}
			controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	private val motionLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume()
		{
			val samplingPeriodUs = 4000
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			listOfNotNull(
				sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
				sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
			).forEach {
				sensorManager.registerListener(sensorEventListener, it, samplingPeriodUs)
			}
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause()
		{
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			sensorManager.unregisterListener(sensorEventListener)
		}
	}

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		if(preferences.motionEnabled)
			lifecycleOwner.lifecycle.addObserver(motionLifecycleObserver)
	}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		//Log.i("StreamSession", "key event $event")
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false

		if(psComboEnabled && (event.keyCode == KeyEvent.KEYCODE_BUTTON_START ||
				event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT ||
				event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 ||
				event.keyCode == KeyEvent.KEYCODE_BUTTON_R1))
		{
			handleComboKey(event, event.keyCode)
			return true
		}

		when(event.keyCode)
		{
			KeyEvent.KEYCODE_BUTTON_L2 -> {
				keyControllerState.l2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				return true
			}
			KeyEvent.KEYCODE_BUTTON_R2 -> {
				keyControllerState.r2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				return true
			}
		}

		val buttonMask: UInt = when(event.keyCode)
		{
			// dpad handled by MotionEvents
			//KeyEvent.KEYCODE_DPAD_LEFT -> ControllerState.BUTTON_DPAD_LEFT
			//KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
			//KeyEvent.KEYCODE_DPAD_UP -> ControllerState.BUTTON_DPAD_UP
			//KeyEvent.KEYCODE_DPAD_DOWN -> ControllerState.BUTTON_DPAD_DOWN
			KeyEvent.KEYCODE_BUTTON_A -> if(swapCrossMoon) ControllerState.BUTTON_MOON else ControllerState.BUTTON_CROSS
			KeyEvent.KEYCODE_BUTTON_B -> if(swapCrossMoon) ControllerState.BUTTON_CROSS else ControllerState.BUTTON_MOON
			KeyEvent.KEYCODE_BUTTON_X -> if(swapCrossMoon) ControllerState.BUTTON_PYRAMID else ControllerState.BUTTON_BOX
			KeyEvent.KEYCODE_BUTTON_Y -> if(swapCrossMoon) ControllerState.BUTTON_BOX else ControllerState.BUTTON_PYRAMID
			KeyEvent.KEYCODE_BUTTON_L1 -> ControllerState.BUTTON_L1
			KeyEvent.KEYCODE_BUTTON_R1 -> ControllerState.BUTTON_R1
			KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerState.BUTTON_L3
			KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerState.BUTTON_R3
			KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerState.BUTTON_SHARE
			KeyEvent.KEYCODE_BUTTON_START -> ControllerState.BUTTON_OPTIONS
			KeyEvent.KEYCODE_BUTTON_C -> ControllerState.BUTTON_PS
			KeyEvent.KEYCODE_BUTTON_MODE -> ControllerState.BUTTON_PS
			else -> return false
		}

		keyControllerState.buttons = keyControllerState.buttons.run {
			when(event.action)
			{
				KeyEvent.ACTION_DOWN -> this or buttonMask
				KeyEvent.ACTION_UP -> this and buttonMask.inv()
				else -> this
			}
		}

		controllerStateUpdated()
		return true
	}

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return false
		fun Float.signedAxis() = (this * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (this * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()
		motionControllerState.leftX = event.getAxisValue(MotionEvent.AXIS_X).signedAxis()
		motionControllerState.leftY = event.getAxisValue(MotionEvent.AXIS_Y).signedAxis()
		motionControllerState.rightX = event.getAxisValue(MotionEvent.AXIS_Z).signedAxis()
		motionControllerState.rightY = event.getAxisValue(MotionEvent.AXIS_RZ).signedAxis()
		motionControllerState.l2State = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).unsignedAxis()
		motionControllerState.r2State = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).unsignedAxis()
		motionControllerState.buttons = motionControllerState.buttons.let {
			val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
			val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
			val dpadButtons =
				(if(dpadX > 0.5f) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
						(if(dpadX < -0.5f) ControllerState.BUTTON_DPAD_LEFT else 0U) or
						(if(dpadY > 0.5f) ControllerState.BUTTON_DPAD_DOWN else 0U) or
						(if(dpadY < -0.5f) ControllerState.BUTTON_DPAD_UP else 0U)
			it and (ControllerState.BUTTON_DPAD_RIGHT or
					ControllerState.BUTTON_DPAD_LEFT or
					ControllerState.BUTTON_DPAD_DOWN or
					ControllerState.BUTTON_DPAD_UP).inv() or
					dpadButtons
		}
		//Log.i("MotionEvent", "motionEvent => $motionControllerState")
		controllerStateUpdated()
		return true
	}

	companion object
	{
		private const val PS_TAP_MS = 80L
		// Long enough for three buttons pressed together; short enough that a
		// lone Options/Share press is not felt as a delay.
		private const val COMBO_WINDOW_MS = 120L
		// Length of the press synthesised for a Start/Select tap that ended
		// inside the hold-back window.
		private const val KEY_TAP_MS = 60L
	}
}
