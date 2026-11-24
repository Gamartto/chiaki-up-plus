// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.gameblabla.chiaki.common

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.gameblabla.chiaki.R
import com.gameblabla.chiaki.lib.Codec
import com.gameblabla.chiaki.lib.ConnectVideoProfile
import com.gameblabla.chiaki.lib.VideoFPSPreset
import com.gameblabla.chiaki.lib.VideoResolutionPreset
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import kotlin.math.max
import kotlin.math.min

class Preferences(context: Context)
{
	enum class Resolution(val value: String, @StringRes val title: Int, val preset: VideoResolutionPreset)
	{
		RES_360P("360p", R.string.preferences_resolution_title_360p, VideoResolutionPreset.RES_360P),
		RES_540P("540p", R.string.preferences_resolution_title_540p, VideoResolutionPreset.RES_540P),
		RES_720P("720p", R.string.preferences_resolution_title_720p, VideoResolutionPreset.RES_720P),
		RES_1080P("1080p", R.string.preferences_resolution_title_1080p, VideoResolutionPreset.RES_1080P),
	}

	enum class FPS(val value: String, @StringRes val title: Int, val preset: VideoFPSPreset)
	{
		FPS_30("30", R.string.preferences_fps_title_30, VideoFPSPreset.FPS_30),
		FPS_60("60", R.string.preferences_fps_title_60, VideoFPSPreset.FPS_60)
	}

	enum class Codec(val value: String, @StringRes val title: Int, val codec: com.gameblabla.chiaki.lib.Codec)
	{
		CODEC_H264("h264", R.string.preferences_codec_title_h264, com.gameblabla.chiaki.lib.Codec.CODEC_H264),
		CODEC_H265("h265", R.string.preferences_codec_title_h265, com.gameblabla.chiaki.lib.Codec.CODEC_H265)
	}

	companion object
	{
		val resolutionDefault = Resolution.RES_720P
		val resolutionAll = Resolution.values()
		val fpsDefault = FPS.FPS_60
		val fpsAll = FPS.values()
		val codecDefault = Codec.CODEC_H265
		val codecAll = Codec.values()
	}

	private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
	private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		when(key)
		{
			resolutionKey -> bitrateAutoSubject.onNext(bitrateAuto)
		}
	}.also { sharedPreferences.registerOnSharedPreferenceChangeListener(it) }

	private val resources = context.resources

	// The bitrate used to be a text field and therefore a String; a slider stores
	// an Int under the same key and reading the wrong type throws. Convert before
	// anything else touches it.
	init
	{
		val raw = sharedPreferences.all[bitrateKey]
		if(raw is String)
			sharedPreferences.edit().putInt(bitrateKey, raw.toIntOrNull()?.let { validateBitrate(it) } ?: 0).apply()
	}

	val discoveryEnabledKey get() = resources.getString(R.string.preferences_discovery_enabled_key)
	var discoveryEnabled
		get() = sharedPreferences.getBoolean(discoveryEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(discoveryEnabledKey, value).apply() }

	val onScreenControlsEnabledKey get() = resources.getString(R.string.preferences_on_screen_controls_enabled_key)
	var onScreenControlsEnabled
		get() = sharedPreferences.getBoolean(onScreenControlsEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(onScreenControlsEnabledKey, value).apply() }

	val touchpadOnlyEnabledKey get() = resources.getString(R.string.preferences_touchpad_only_enabled_key)
	var touchpadOnlyEnabled
		get() = sharedPreferences.getBoolean(touchpadOnlyEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(touchpadOnlyEnabledKey, value).apply() }

	val rumbleEnabledKey get() = resources.getString(R.string.preferences_rumble_enabled_key)
	var rumbleEnabled
		get() = sharedPreferences.getBoolean(rumbleEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(rumbleEnabledKey, value).apply() }

	val motionEnabledKey get() = resources.getString(R.string.preferences_motion_enabled_key)
	var motionEnabled
		get() = sharedPreferences.getBoolean(motionEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(motionEnabledKey, value).apply() }

	val buttonHapticEnabledKey get() = resources.getString(R.string.preferences_button_haptic_enabled_key)
	var buttonHapticEnabled
		get() = sharedPreferences.getBoolean(buttonHapticEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(buttonHapticEnabledKey, value).apply() }

	val logVerboseKey get() = resources.getString(R.string.preferences_log_verbose_key)
	var logVerbose
		get() = sharedPreferences.getBoolean(logVerboseKey, false)
		set(value) { sharedPreferences.edit().putBoolean(logVerboseKey, value).apply() }

	val swapCrossMoonKey get() = resources.getString(R.string.preferences_swap_cross_moon_key)
	var swapCrossMoon
		get() = sharedPreferences.getBoolean(swapCrossMoonKey, false)
		set(value) { sharedPreferences.edit().putBoolean(swapCrossMoonKey, value).apply() }

	val rumbleStrengthKey get() = resources.getString(R.string.preferences_rumble_strength_key)
	var rumbleStrength
		get() = sharedPreferences.getInt(rumbleStrengthKey, 100).coerceIn(0, 150)
		set(value) { sharedPreferences.edit().putInt(rumbleStrengthKey, value.coerceIn(0, 150)).apply() }

	val hiddenPsEnabledKey get() = resources.getString(R.string.preferences_hidden_ps_enabled_key)
	var hiddenPsEnabled
		get() = sharedPreferences.getBoolean(hiddenPsEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(hiddenPsEnabledKey, value).apply() }

	val hiddenPsPositionKey get() = resources.getString(R.string.preferences_hidden_ps_position_key)
	var hiddenPsPosition
		get() = sharedPreferences.getString(hiddenPsPositionKey, "top_center") ?: "top_center"
		set(value) { sharedPreferences.edit().putString(hiddenPsPositionKey, value).apply() }

	val hiddenPsVisibilityKey get() = resources.getString(R.string.preferences_hidden_ps_visibility_key)
	var hiddenPsVisibility
		get() = sharedPreferences.getString(hiddenPsVisibilityKey, "never") ?: "never"
		set(value) { sharedPreferences.edit().putString(hiddenPsVisibilityKey, value).apply() }

	val hiddenPsSizeKey get() = resources.getString(R.string.preferences_hidden_ps_size_key)
	var hiddenPsSize
		get() = sharedPreferences.getInt(hiddenPsSizeKey, 72).coerceIn(48, 120)
		set(value) { sharedPreferences.edit().putInt(hiddenPsSizeKey, value.coerceIn(48, 120)).apply() }

	val autoConnectEnabledKey get() = resources.getString(R.string.preferences_auto_connect_enabled_key)
	var autoConnectEnabled
		get() = sharedPreferences.getBoolean(autoConnectEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(autoConnectEnabledKey, value).apply() }

	// Which console to open when more than one is registered; -1 means none chosen.
	val autoConnectHostIdKey get() = resources.getString(R.string.preferences_auto_connect_host_id_key)
	var autoConnectHostId
		get() = sharedPreferences.getLong(autoConnectHostIdKey, -1L)
		set(value) { sharedPreferences.edit().putLong(autoConnectHostIdKey, value).apply() }

	val touchpadOverlayKey get() = resources.getString(R.string.preferences_touchpad_overlay_key)
	var touchpadOverlay
		get() = sharedPreferences.getString(touchpadOverlayKey, "while_touch") ?: "while_touch"
		set(value) { sharedPreferences.edit().putString(touchpadOverlayKey, value).apply() }

	val touchpadZoneTintKey get() = resources.getString(R.string.preferences_touchpad_zone_tint_key)
	var touchpadZoneTint
		get() = sharedPreferences.getBoolean(touchpadZoneTintKey, false)
		set(value) { sharedPreferences.edit().putBoolean(touchpadZoneTintKey, value).apply() }

	val statsAtBottomKey get() = resources.getString(R.string.preferences_stats_at_bottom_key)
	var statsAtBottom
		get() = sharedPreferences.getBoolean(statsAtBottomKey, false)
		set(value) { sharedPreferences.edit().putBoolean(statsAtBottomKey, value).apply() }

	val statsEnabledKey get() = resources.getString(R.string.preferences_stats_enabled_key)
	var statsEnabled
		get() = sharedPreferences.getBoolean(statsEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(statsEnabledKey, value).apply() }

	val psComboEnabledKey get() = resources.getString(R.string.preferences_ps_combo_enabled_key)
	var psComboEnabled
		get() = sharedPreferences.getBoolean(psComboEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(psComboEnabledKey, value).apply() }

	val resolutionKey get() = resources.getString(R.string.preferences_resolution_key)
	var resolution
		get() = sharedPreferences.getString(resolutionKey, resolutionDefault.value)?.let { value ->
			Resolution.values().firstOrNull { it.value == value }
		} ?: resolutionDefault
		set(value) { sharedPreferences.edit().putString(resolutionKey, value.value).apply() }

	val fpsKey get() = resources.getString(R.string.preferences_fps_key)
	var fps
		get() = sharedPreferences.getString(fpsKey, fpsDefault.value)?.let { value ->
			FPS.values().firstOrNull { it.value == value }
		}  ?: fpsDefault
		set(value) { sharedPreferences.edit().putString(fpsKey, value.value).apply() }

	// The console caps this itself; 100 Mbit is only an upper bound for the UI.
	fun validateBitrate(bitrate: Int) = max(2000, min(100000, bitrate))
	val bitrateKey get() = resources.getString(R.string.preferences_bitrate_key)
	var bitrate
		get() = sharedPreferences.getInt(bitrateKey, 0).let { if(it == 0) null else validateBitrate(it) }
		set(value) { sharedPreferences.edit().putInt(bitrateKey, if(value != null) validateBitrate(value) else 0).apply() }
	val bitrateAuto get() = videoProfileDefaultBitrate.bitrate
	private val bitrateAutoSubject by lazy { BehaviorSubject.createDefault(bitrateAuto) }
	val bitrateAutoObservable: Observable<Int> get() = bitrateAutoSubject

	val codecKey get() = resources.getString(R.string.preferences_codec_key)
	var codec
		get() = sharedPreferences.getString(codecKey, codecDefault.value)?.let { value ->
			Codec.values().firstOrNull { it.value == value }
		}  ?: codecDefault
		set(value) { sharedPreferences.edit().putString(codecKey, value.value).apply() }

	private val videoProfileDefaultBitrate get() = ConnectVideoProfile.preset(resolution.preset, fps.preset, codec.codec)
	val videoProfile get() = videoProfileDefaultBitrate.let {
		val bitrate = bitrate
		if(bitrate == null)
			it
		else
			it.copy(bitrate = bitrate)
	}
}
