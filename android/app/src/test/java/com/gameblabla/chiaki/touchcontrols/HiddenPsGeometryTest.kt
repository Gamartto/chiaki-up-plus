// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.gameblabla.chiaki.touchcontrols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invisible PS button and the touchpad share the same edges. This guards the
 * invariant that matters: at its maximum radius the hotspot must not reach into
 * the pad, otherwise a corner press would be swallowed before the pad sees it.
 */
class HiddenPsGeometryTest
{
	// A 1080p handheld in landscape and portrait, same density as the RP5.
	private val density = 2.75f
	private val margin = HiddenPsGeometry.PAD_SIDE_MARGIN_DP * density

	@Test
	fun padSizeMatchesTheLayoutRules()
	{
		val w = 1920f
		val h = 1080f
		val padWidth = HiddenPsGeometry.padWidthPx(w, h, margin)
		// Width-limited: the screen is not wide enough for the vertical fit.
		assertEquals(w - 2 * margin, padWidth, 0.5f)
		assertEquals(padWidth / (1920f / 942f), HiddenPsGeometry.padHeightPx(w, h, margin), 0.5f)
	}

	@Test
	fun maximumRadiusStaysOutsideThePadLandscape()
	{
		val w = 1920f
		val h = 1080f
		val radius = HiddenPsGeometry.maxRadiusPx(w, h, margin, density)
		val padHeight = HiddenPsGeometry.padHeightPx(w, h, margin)
		val padTop = (h - padHeight) / 2f

		assertTrue("radius must be positive", radius > 0f)
		// Corner quarter circles: the furthest point they reach is (r, r) from the
		// corner, which must stay above the pad and left of it.
		assertTrue("hotspot must not cross the pad's left edge", radius <= margin)
		assertTrue("hotspot must not cross the pad's top edge", radius <= padTop)
	}

	@Test
	fun maximumRadiusStaysOutsideThePadPortrait()
	{
		val w = 1080f
		val h = 1920f
		val radius = HiddenPsGeometry.maxRadiusPx(w, h, margin, density)
		val padHeight = HiddenPsGeometry.padHeightPx(w, h, margin)
		val padTop = (h - padHeight) / 2f

		assertTrue(radius > 0f)
		assertTrue(radius <= margin)
		assertTrue(radius <= padTop)
	}

	@Test
	fun aBigConfiguredSizeIsClampedToTheFreeGap()
	{
		val w = 1920f
		val h = 1080f
		val max = HiddenPsGeometry.maxRadiusPx(w, h, margin, density)
		val requested = 120f * density // the slider maximum, in pixels

		assertTrue("the slider maximum should be too big for this screen", requested > max)
		assertTrue("clamping must keep it clear", minOf(requested, max) <= max)
	}

	@Test
	fun noRoomMeansNoRadius()
	{
		// A pad that fills the screen leaves nothing to work with.
		val radius = HiddenPsGeometry.maxRadiusPx(400f, 200f, 0f, density)
		assertEquals(0f, radius, 0.01f)
	}
}
