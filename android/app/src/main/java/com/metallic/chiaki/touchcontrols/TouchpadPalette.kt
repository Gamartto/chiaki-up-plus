// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.gameblabla.chiaki.touchcontrols

/**
 * The touchpad tint across its width: violet-blue at the left edge, white in the
 * centre, cyan at the right.
 *
 * It is a continuous ramp rather than three zones with a lookup per third. A
 * per-zone colour steps at the boundaries, and the eye reads that step as the next
 * colour starting out of nowhere; interpolating keeps the hue moving with the
 * finger, so there is no edge to notice.
 */
object TouchpadPalette
{
	// Violet-blue, white, cyan. Kept pure: what changes is the shape of the ramp,
	// not the colours themselves.
	private const val LEFT = 0x7C5CFF
	private const val CENTRE = 0xFFFFFF
	private const val RIGHT = 0x22D3EE

	/** Colour at [fraction] of the pad's width: 0 is the left edge, 1 the right. */
	fun colorAt(fraction: Float): Int
	{
		val t = fraction.coerceIn(0f, 1f) * 2f
		return if(t <= 1f) lerp(LEFT, CENTRE, t) else lerp(CENTRE, RIGHT, t - 1f)
	}

	private fun lerp(from: Int, to: Int, t: Float): Int
	{
		val f = t.coerceIn(0f, 1f)
		fun channel(shift: Int): Int
		{
			val a = (from ushr shift) and 0xFF
			val b = (to ushr shift) and 0xFF
			return Math.round(a + (b - a) * f)
		}
		return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
	}
}
