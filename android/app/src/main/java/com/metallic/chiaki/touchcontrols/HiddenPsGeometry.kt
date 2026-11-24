// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.gameblabla.chiaki.touchcontrols

/**
 * Where the invisible PS button may live without touching the touchpad.
 *
 * The touchpad is laid out with the same rules as fragment_touchpad_only.xml: a
 * margin on each side and the DualSense aspect ratio, centred vertically. Its
 * free gap to the screen edge is what limits the hotspot radius, because a circle
 * that grows past that gap starts swallowing touchpad touches in the corners.
 */
object HiddenPsGeometry
{
	/** 1920 / 942, the DualSense touchpad aspect. */
	private const val PAD_ASPECT = 1920f / 942f

	/** Matches the 80dp side margins in fragment_touchpad_only.xml. */
	const val PAD_SIDE_MARGIN_DP = 80f

	/** Keeps a couple of dp of air between the hotspot and the pad. */
	private const val SAFETY_DP = 2f

	fun padWidthPx(viewWidth: Float, viewHeight: Float, marginPx: Float): Float =
		minOf(viewWidth - 2 * marginPx, viewHeight * PAD_ASPECT)

	fun padHeightPx(viewWidth: Float, viewHeight: Float, marginPx: Float): Float =
		padWidthPx(viewWidth, viewHeight, marginPx) / PAD_ASPECT

	/**
	 * Largest radius, in pixels, that stays clear of the touchpad. Returns 0 when
	 * the pad fills the screen and there is no room at all.
	 */
	fun maxRadiusPx(viewWidth: Float, viewHeight: Float, marginPx: Float, density: Float): Float
	{
		if(viewWidth <= 0f || viewHeight <= 0f)
			return 0f
		val padHeight = padHeightPx(viewWidth, viewHeight, marginPx)
		val verticalGap = (viewHeight - padHeight) / 2f
		val horizontalGap = marginPx
		val gap = minOf(horizontalGap, verticalGap) - SAFETY_DP * density
		return if(gap > 0f) gap else 0f
	}
}
