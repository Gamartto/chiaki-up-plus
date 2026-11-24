// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.gameblabla.chiaki.touchcontrols

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The invisible PS button is nothing but a shape and a timer, which is exactly the
 * part that has to be right: a shape that steals touches from the touchpad, or a
 * tap that fires when it should not, both ruin a session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HiddenPsHotspotViewTest
{
	private val radius = 100f

	private fun viewAt(position: HiddenPsHotspotView.Position): HiddenPsHotspotView
	{
		val context = ApplicationProvider.getApplicationContext<Context>()
		return HiddenPsHotspotView(context).apply {
			this.position = position
			this.radiusPx = radius
		}
	}

	@Test
	fun quarterCircleHitsOnlyItsQuadrant()
	{
		val view = viewAt(HiddenPsHotspotView.Position.TOP_LEFT)
		// Inside the quarter circle centred on the top-left corner.
		assertTrue(view.containsTouch(10f, 10f))
		assertTrue(view.containsTouch(70f, 70f))
		// Outside the circle but inside the bounding square.
		assertFalse(view.containsTouch(95f, 95f))
		// Outside the widget altogether.
		assertFalse(view.containsTouch(150f, 10f))
	}

	@Test
	fun halfCircleAtTopCentreSpansBothSides()
	{
		val view = viewAt(HiddenPsHotspotView.Position.TOP_CENTER)
		assertTrue(view.containsTouch(100f, 10f))
		assertTrue(view.containsTouch(40f, 80f))
		assertTrue(view.containsTouch(160f, 80f))
		assertTrue(view.containsTouch(100f, 99f))
		assertFalse(view.containsTouch(5f, 99f))
	}

	@Test
	fun bottomRightQuarterCircleIsAnchoredToThatCorner()
	{
		val view = viewAt(HiddenPsHotspotView.Position.BOTTOM_RIGHT)
		assertTrue(view.containsTouch(90f, 90f))
		assertFalse(view.containsTouch(5f, 5f))
	}

	@Test
	fun holdTriggersOnce()
	{
		val view = viewAt(HiddenPsHotspotView.Position.TOP_CENTER)
		val fired = AtomicInteger(0)
		view.onTrigger = { fired.incrementAndGet() }

		val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 10f, 0)
		assertTrue(view.onTouchEvent(down))

		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
		assertEquals("nothing before the hold elapses", 0, fired.get())

		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
		assertEquals("one PS tap after 0.6 s", 1, fired.get())

		// Staying pressed must not keep firing.
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
		assertEquals(1, fired.get())
	}

	@Test
	fun quickTapDoesNotFire()
	{
		val view = viewAt(HiddenPsHotspotView.Position.TOP_CENTER)
		val fired = AtomicInteger(0)
		view.onTrigger = { fired.incrementAndGet() }

		view.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 10f, 0))
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
		view.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 100f, 10f, 0))
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

		assertEquals("a tap must not open the console menu", 0, fired.get())
	}

	@Test
	fun touchOutsideTheShapeIsNotConsumed()
	{
		val view = viewAt(HiddenPsHotspotView.Position.TOP_LEFT)
		val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 95f, 95f, 0)
		assertFalse("outside the circle the event must fall through", view.onTouchEvent(down))
	}
}
