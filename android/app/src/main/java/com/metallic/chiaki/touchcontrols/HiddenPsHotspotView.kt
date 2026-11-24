// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.gameblabla.chiaki.touchcontrols

import android.content.Context
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

	companion object
	{
		private const val HOLD_MS = 600L
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
				holding = true
				fired = false
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

	private fun cancelHold()
	{
		holding = false
		handler.removeCallbacks(holdRunnable)
	}
}
