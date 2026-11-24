// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.gameblabla.chiaki.touchcontrols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The touchpad tint is a ramp, and what matters is that it never steps: a jump of
 * more than a couple of levels between neighbouring samples is exactly the edge
 * that reads as the white starting out of nowhere.
 */
class TouchpadPaletteTest
{
	private fun channels(color: Int) = intArrayOf(
		(color ushr 16) and 0xFF, (color ushr 8) and 0xFF, color and 0xFF
	)

	@Test
	fun theEdgesAndTheCentreKeepTheirColours()
	{
		assertEquals(0xFF7C5CFF.toInt(), TouchpadPalette.colorAt(0f))
		assertEquals(0xFFFFFFFF.toInt(), TouchpadPalette.colorAt(0.5f))
		assertEquals(0xFF22D3EE.toInt(), TouchpadPalette.colorAt(1f))
	}

	@Test
	fun theRampIsContinuous()
	{
		val steps = 2000
		var previous = channels(TouchpadPalette.colorAt(0f))
		for(i in 1..steps)
		{
			val current = channels(TouchpadPalette.colorAt(i.toFloat() / steps))
			for(c in 0..2)
				assertTrue("channel $c jumps at step $i", Math.abs(current[c] - previous[c]) <= 1)
			previous = current
		}
	}

	@Test
	fun redAndGreenClimbToTheCentreAndFallAwayFromIt()
	{
		// The white sits in the middle, so red and green peak there. A zone lookup
		// steps at the thirds instead of moving, which is what looked wrong.
		val left = channels(TouchpadPalette.colorAt(0.25f))
		val middle = channels(TouchpadPalette.colorAt(0.5f))
		val right = channels(TouchpadPalette.colorAt(0.75f))
		assertTrue(left[0] < middle[0] && middle[0] > right[0])
		assertTrue(left[1] < middle[1] && middle[1] > right[1])
	}

	@Test
	fun outsideThePadItClamps()
	{
		assertEquals(TouchpadPalette.colorAt(0f), TouchpadPalette.colorAt(-1f))
		assertEquals(TouchpadPalette.colorAt(1f), TouchpadPalette.colorAt(2f))
	}
}
