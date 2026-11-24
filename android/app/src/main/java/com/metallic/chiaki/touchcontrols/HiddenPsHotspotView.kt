// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.gameblabla.chiaki.touchcontrols

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.pow

/**
 * An invisible PS button for handhelds whose controller has no PS key.
 *
 * Nothing is drawn: the shape exists only to decide whether a touch counts. A
 * quarter circle in the corners and a half circle at the middle of the top and
 * bottom edges, which keeps the hit area out of the touchpad's rectangle and out
 * of the middle of the screen. A hold is required so a stray finger cannot open
 * the console menu mid-game.
 */
class HiddenPsHotspotView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	enum class Position { TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

	/** Mirrors the touchpad indicator: never drawn, always drawn, or only on hold. */
	enum class Visibility { NEVER, ALWAYS, ON_PRESS }

	companion object
	{
		private const val HOLD_MS = 600L

		// Same white the touchpad uses for its centre zone, so this reads as part of
		// the same overlay instead of a separate widget.
		private const val WHITE = 0xFFFFFF
		private const val FILL_ALPHA_IDLE = 0x26
		private const val FILL_ALPHA_HELD = 0x4D
		private const val EDGE_ALPHA = 0x66
	}

	private val haptics = ButtonHaptics(context)

	// Explicit handler instead of View.postDelayed: the latter only runs once the
	// view is attached to a window, which makes the hold depend on layout timing.
	private val handler = Handler(Looper.getMainLooper())

	var position = Position.TOP_CENTER
		set(value)
		{
			field = value
			applySize()
		}

	/** Radius of the shape in pixels; the widget resizes itself around it. */
	var radiusPx = 0f
		set(value)
		{
			field = value
			applySize()
		}

	var onTrigger: (() -> Unit)? = null

	var visibilityMode = Visibility.NEVER
		set(value)
		{
			field = value
			invalidate()
		}

	// Same white the touchpad uses for its centre zone, so the button reads as part
	// of the same overlay instead of a separate widget.
	private val areaFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
	private val areaEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
	private val arcRect = RectF()





	private var holding = false
	private var fired = false

	private val holdRunnable = Runnable {
		if(holding && !fired)
		{
			fired = true
			haptics.trigger(true)
			onTrigger?.invoke()
		}
	}

	private fun applySize()
	{
		val r = radiusPx.toInt()
		if(r <= 0)
			return
		val isEdgeCentre = position == Position.TOP_CENTER || position == Position.BOTTOM_CENTER
		val params = layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(0, 0)
		params.width = if(isEdgeCentre) r * 2 else r
		params.height = r
		params.gravity = gravityFor(position)
		layoutParams = params
		requestLayout()
	}

	private fun gravityFor(position: Position) = when(position)
	{
		Position.TOP_LEFT -> android.view.Gravity.TOP or android.view.Gravity.START
		Position.TOP_CENTER -> android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
		Position.TOP_RIGHT -> android.view.Gravity.TOP or android.view.Gravity.END
		Position.BOTTOM_LEFT -> android.view.Gravity.BOTTOM or android.view.Gravity.START
		Position.BOTTOM_CENTER -> android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
		Position.BOTTOM_RIGHT -> android.view.Gravity.BOTTOM or android.view.Gravity.END
	}

	/**
	 * True when [x], [y] fall inside the shape, not merely inside the rectangle
	 * that bounds it. Keeping this pure makes it testable without a device.
	 */
	fun containsTouch(x: Float, y: Float): Boolean
	{
		val r = radiusPx
		if(r <= 0f)
			return false
		val (cx, cy) = when(position)
		{
			Position.TOP_LEFT -> 0f to 0f
			Position.TOP_CENTER -> r to 0f
			Position.TOP_RIGHT -> r to 0f
			Position.BOTTOM_LEFT -> 0f to r
			Position.BOTTOM_CENTER -> r to r
			Position.BOTTOM_RIGHT -> r to r
		}
		return (x - cx).pow(2) + (y - cy).pow(2) <= r * r
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN ->
			{
				if(!containsTouch(event.x, event.y))
					return false
				fired = false
				setHolding(true)
				handler.postDelayed(holdRunnable, HOLD_MS)
				return true
			}
			MotionEvent.ACTION_MOVE ->
			{
				if(holding && !containsTouch(event.x, event.y))
					cancelHold()
				return true
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
			{
				cancelHold()
				return true
			}
		}
		return false
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		if(radiusPx <= 0f)
			return
		val shouldDraw = when(visibilityMode)
		{
			Visibility.NEVER -> false
			Visibility.ALWAYS -> true
			Visibility.ON_PRESS -> holding
		}
		if(!shouldDraw)
			return

		val r = radiusPx
		val (rect, start, sweep) = when(position)
		{
			Position.TOP_LEFT -> Triple(RectF(-r, -r, r, r), 0f, 90f)
			Position.TOP_CENTER -> Triple(RectF(0f, -r, 2 * r, r), 0f, 180f)
			Position.TOP_RIGHT -> Triple(RectF(0f, -r, 2 * r, r), 90f, 90f)
			Position.BOTTOM_LEFT -> Triple(RectF(-r, 0f, r, 2 * r), 270f, 90f)
			Position.BOTTOM_CENTER -> Triple(RectF(0f, 0f, 2 * r, 2 * r), 180f, 180f)
			Position.BOTTOM_RIGHT -> Triple(RectF(0f, 0f, 2 * r, 2 * r), 180f, 90f)
		}
		val fillAlpha = if(holding) FILL_ALPHA_HELD else FILL_ALPHA_IDLE
		areaFill.color = (fillAlpha shl 24) or WHITE
		areaEdge.color = (EDGE_ALPHA shl 24) or WHITE
		areaEdge.strokeWidth = (r * 0.04f).coerceAtLeast(2f)
		arcRect.set(rect)
		canvas.drawArc(arcRect, start, sweep, true, areaFill)
		canvas.drawArc(arcRect, start, sweep, true, areaEdge)
	}

	private fun setHolding(value: Boolean)
	{
		holding = value
		if(visibilityMode == Visibility.ON_PRESS)
			invalidate()
	}

	private fun cancelHold()
	{
		setHolding(false)
		handler.removeCallbacks(holdRunnable)
	}
}
