// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.gameblabla.chiaki.settings

import androidx.preference.ListPreference
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import com.gameblabla.chiaki.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings screen is inflated for real here. This exists because a stale
 * findPreference<EditTextPreference> after the bitrate became a slider crashed the
 * screen on open, and nothing caught it until it was installed on the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsScreenTest
{
	private fun openSettings(): androidx.preference.PreferenceFragmentCompat
	{
		val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
		val fragment = activity.supportFragmentManager.findFragmentById(R.id.settingsFragment)
		assertNotNull("SettingsFragment should be attached", fragment)
		return fragment as androidx.preference.PreferenceFragmentCompat
	}

	@Test
	fun settingsScreenInflates()
	{
		val fragment = openSettings()
		assertNotNull("preference screen should be built", fragment.preferenceScreen)
	}

	@Test
	fun bitrateIsASliderNotATextField()
	{
		val screen = openSettings().preferenceScreen
		val preference = screen.findPreference<androidx.preference.Preference>("stream_bitrate")
		assertNotNull("bitrate preference should exist", preference)
		assertTrue("bitrate should be a slider, was ${preference!!::class.simpleName}", preference is SeekBarPreference)
		val slider = preference as SeekBarPreference
		assertEquals(0, slider.min)
		assertEquals(100000, slider.max)
	}

	@Test
	fun everySwitchAndListPreferenceSurvivesInflation()
	{
		val screen = openSettings().preferenceScreen
		for(key in listOf("rumble_enabled", "motion_enabled", "button_haptic_enabled",
				"swap_cross_moon", "log_verbose", "ps_combo_enabled", "stats_at_bottom",
				"touchpad_zone_tint", "auto_connect_enabled"))
		{
			val preference = screen.findPreference<androidx.preference.Preference>(key)
			assertNotNull("$key should exist", preference)
			assertTrue("$key should be a switch", preference is SwitchPreference)
		}
		for(key in listOf("stream_resolution", "stream_fps", "stream_codec", "touchpad_overlay"))
		{
			val preference = screen.findPreference<androidx.preference.Preference>(key)
			assertNotNull("$key should exist", preference)
			assertTrue("$key should be a list", preference is ListPreference)
			assertNotNull("$key needs entries", (preference as ListPreference).entries)
		}
	}
}
