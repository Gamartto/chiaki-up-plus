// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.gameblabla.chiaki.touchcontrols

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.gameblabla.chiaki.R
import com.gameblabla.chiaki.lib.ControllerState
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import kotlin.math.max
import kotlin.math.min

class TouchpadView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	enum class OverlayMode { ALWAYS, WHILE_TOUCH, NEVER }

	companion object
	{
		private const val BUTTON_PRESS_MAX_MOVE_DIST_DP = 32.0f
		private const val SHORT_BUTTON_PRESS_DURATION_MS = 200L
		private const val BUTTON_HOLD_DELAY_MS = 500L
		// Neon, but quiet: violet-blue on the left, white in the centre, cyan on the
		// right, laid down as a soft glow instead of a flat fill.
		private val ZONE_COLORS = intArrayOf(0x7C5CFF, 0xFFFFFF, 0x22D3EE)
		private const val ZONE_ALPHA_IDLE = 0x26
		private const val ZONE_ALPHA_PRESSED = 0x4D
		private const val GLOW_ALPHA = 0x8C
		private const val GLOW_RADIUS_RATIO = 0.85f
		// The pad vector is a rounded rectangle with a 32/508 corner radius.
		private const val PAD_CORNER_RATIO = 32f / 508f
	}

	// The console only ever receives absolute coordinates, so what is drawn here
	// is purely for the player: when the area is shown and, optionally, in which
	// third of the pad the finger is.
	var overlayMode = OverlayMode.WHILE_TOUCH

	var zoneTint = false

	private var lastTouchX = 0f
	private var lastTouchY = 0f

	private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val padPath = Path()

	private val haptics = ButtonHaptics(context)

	private val drawableIdle: Drawable?
	private val drawablePressed: Drawable?

	private val state: ControllerState = ControllerState()

	inner class Touch(
		val stateId: UByte,
		private val startX: Float,
		private val startY: Float)
	{
		var lifted = false // will be true but touch still in list when only relevant for short touch
		private var maxDist: Float = 0.0f
		val moveInsignificant: Boolean get() = maxDist < BUTTON_PRESS_MAX_MOVE_DIST_DP

		fun onMove(x: Float, y: Float)
		{
			val d = (Vector(x, y) - Vector(startX, startY)).length / resources.displayMetrics.density
			maxDist = max(d, maxDist)
		}

		val startButtonHoldRunnable = Runnable {
			if(!moveInsignificant || buttonHeld)
				return@Runnable
			haptics.trigger(true)
			state.buttons = state.buttons or ControllerState.BUTTON_TOUCHPAD
			buttonHeld = true
			triggerStateChanged()
		}
	}
	private val pointerTouches = mutableMapOf<Int, Touch>()

	private val stateSubject: Subject<ControllerState>
		= BehaviorSubject.create<ControllerState>().also { it.onNext(state) }
	val controllerState: Observable<ControllerState> get() = stateSubject

	private var shortPressingTouches = listOf<Touch>()
	private val shortButtonPressLiftRunnable = Runnable {
		state.buttons = state.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
		shortPressingTouches.forEach {
			state.stopTouch(it.stateId)
		}
		shortPressingTouches = listOf()
		triggerStateChanged()
	}

	private var buttonHeld = false

	init
	{
		context.theme.obtainStyledAttributes(attrs, R.styleable.TouchpadView, 0, 0).apply {
			drawableIdle = getDrawable(R.styleable.TouchpadView_drawableIdle)?.mutate()
			drawablePressed = getDrawable(R.styleable.TouchpadView_drawablePressed)?.mutate()
			recycle()
		}
		isClickable = true
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val touched = pointerTouches.values.any { !it.lifted }

		// Never means nothing at all, tint included: the player asked for a clean
		// screen, not for a different kind of indicator.
		val drawShape = when(overlayMode)
		{
			OverlayMode.ALWAYS -> true
			OverlayMode.WHILE_TOUCH -> touched
			OverlayMode.NEVER -> false
		}
		if(!drawShape)
			return

		val pressed = state.buttons and ControllerState.BUTTON_TOUCHPAD != 0U
		val drawable = (if(pressed) drawablePressed else drawableIdle) ?: return

		val zoneColor = if(zoneTint && touched) ZONE_COLORS[zoneOf(lastTouchX)] else 0
		if(zoneColor != 0)
		{
			// Tinting the vector itself keeps the exact shape and corner radius of
			// the pad; the glow on top is what makes it read as neon rather than as
			// a coloured button.
			val alpha = if(pressed) ZONE_ALPHA_PRESSED else ZONE_ALPHA_IDLE
			drawable.setTint((alpha shl 24) or zoneColor)
		}
		else
			drawable.setTintList(null)

		drawable.setBounds(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
		drawable.draw(canvas)

		if(zoneColor != 0)
			drawGlow(canvas, zoneColor)
	}

	private fun drawGlow(canvas: Canvas, rgb: Int)
	{
		val radius = min(width.toFloat(), height.toFloat()) * GLOW_RADIUS_RATIO
		if(radius <= 0f)
			return
		val cx = lastTouchX.coerceIn(0f, width.toFloat())
		val cy = lastTouchY.coerceIn(0f, height.toFloat())
		glowPaint.shader = RadialGradient(cx, cy, radius,
			(GLOW_ALPHA shl 24) or (rgb and 0xFFFFFF),
			0x00000000,
			Shader.TileMode.CLAMP)

		val corner = width * PAD_CORNER_RATIO
		padPath.reset()
		padPath.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), corner, corner, Path.Direction.CW)
		canvas.save()
		canvas.clipPath(padPath)
		canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
		canvas.restore()
		glowPaint.shader = null
	}

	private fun zoneOf(x: Float) = when
	{
		x < width / 3f -> 0
		x < width * 2f / 3f -> 1
		else -> 2
	}

	private fun touchX(event: MotionEvent, index: Int): UShort =
		maxOf(0U.toUShort(), minOf((ControllerState.TOUCHPAD_WIDTH - 1u).toUShort(),
			(ControllerState.TOUCHPAD_WIDTH.toFloat() * event.getX(index) / width.toFloat()).toUInt().toUShort()))

	private fun touchY(event: MotionEvent, index: Int): UShort =
		maxOf(0U.toUShort(), minOf((ControllerState.TOUCHPAD_HEIGHT - 1u).toUShort(),
			(ControllerState.TOUCHPAD_HEIGHT.toFloat() * event.getY(index) / height.toFloat()).toUInt().toUShort()))

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
				state.startTouch(touchX(event, event.actionIndex), touchY(event, event.actionIndex))?.let {
					haptics.trigger()
					lastTouchX = event.getX(event.actionIndex)
					lastTouchY = event.getY(event.actionIndex)
					val touch = Touch(it, event.getX(event.actionIndex), event.getY(event.actionIndex))
					pointerTouches[event.getPointerId(event.actionIndex)] = touch
					if(!buttonHeld)
						postDelayed(touch.startButtonHoldRunnable, BUTTON_HOLD_DELAY_MS)
					triggerStateChanged()
				}
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
				pointerTouches.remove(event.getPointerId(event.actionIndex))?.let {
					removeCallbacks(it.startButtonHoldRunnable)
					when
					{
						buttonHeld ->
						{
							buttonHeld = false
							state.buttons = state.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
							state.stopTouch(it.stateId)
						}
						it.moveInsignificant -> triggerShortButtonPress(it)
						else -> state.stopTouch(it.stateId)
					}
					triggerStateChanged()
				}
			}
			MotionEvent.ACTION_MOVE -> {
				val changed = pointerTouches.entries.fold(false) { acc, it ->
					val index = event.findPointerIndex(it.key)
					if(index < 0)
						acc
					else
					{
						lastTouchX = event.getX(index)
						lastTouchY = event.getY(index)
						it.value.onMove(event.getX(event.actionIndex), event.getY(event.actionIndex))
						acc || state.setTouchPos(it.value.stateId, touchX(event, index), touchY(event, index))
					}
				}
				if(changed)
					triggerStateChanged()
			}
		}
		return true
	}

	private fun triggerShortButtonPress(touch: Touch)
	{
		shortPressingTouches = shortPressingTouches + listOf(touch)
		removeCallbacks(shortButtonPressLiftRunnable)
		state.buttons = state.buttons or ControllerState.BUTTON_TOUCHPAD
		postDelayed(shortButtonPressLiftRunnable, SHORT_BUTTON_PRESS_DURATION_MS)
	}

	private fun triggerStateChanged()
	{
		invalidate()
		stateSubject.onNext(state)
	}
}
