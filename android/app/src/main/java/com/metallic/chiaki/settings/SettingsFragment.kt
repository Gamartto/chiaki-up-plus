// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.gameblabla.chiaki.settings

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.text.InputType
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.preference.*
import com.gameblabla.chiaki.R
import com.gameblabla.chiaki.common.Preferences
import com.gameblabla.chiaki.common.exportAndShareAllSettings
import com.gameblabla.chiaki.common.ext.viewModelFactory
import com.gameblabla.chiaki.common.getDatabase
import com.gameblabla.chiaki.common.importSettingsFromUri
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat


class DataStore(val preferences: Preferences): PreferenceDataStore()
{
	private class Binding<T>(val read: () -> T, val write: (T) -> Unit)

	// One table per stored type. A preference that is declared in preferences.xml
	// but missing here is silently dropped by the framework, which has cost two
	// bugs already; DataStoreBindingsTest fails the build if they disagree.
	private val booleans = mapOf(
		preferences.logVerboseKey to Binding({ preferences.logVerbose }, { v: Boolean -> preferences.logVerbose = v }),
		preferences.swapCrossMoonKey to Binding({ preferences.swapCrossMoon }, { v: Boolean -> preferences.swapCrossMoon = v }),
		preferences.rumbleEnabledKey to Binding({ preferences.rumbleEnabled }, { v: Boolean -> preferences.rumbleEnabled = v }),
		preferences.motionEnabledKey to Binding({ preferences.motionEnabled }, { v: Boolean -> preferences.motionEnabled = v }),
		preferences.buttonHapticEnabledKey to Binding({ preferences.buttonHapticEnabled }, { v: Boolean -> preferences.buttonHapticEnabled = v }),
		preferences.psComboEnabledKey to Binding({ preferences.psComboEnabled }, { v: Boolean -> preferences.psComboEnabled = v }),
		preferences.statsAtBottomKey to Binding({ preferences.statsAtBottom }, { v: Boolean -> preferences.statsAtBottom = v }),
		preferences.touchpadZoneTintKey to Binding({ preferences.touchpadZoneTint }, { v: Boolean -> preferences.touchpadZoneTint = v }),
		preferences.autoConnectEnabledKey to Binding({ preferences.autoConnectEnabled }, { v: Boolean -> preferences.autoConnectEnabled = v }),
		preferences.hiddenPsEnabledKey to Binding({ preferences.hiddenPsEnabled }, { v: Boolean -> preferences.hiddenPsEnabled = v })
	)

	private val strings = mapOf(
		preferences.resolutionKey to Binding({ preferences.resolution.value }, { v: String ->
			Preferences.Resolution.values().firstOrNull { it.value == v }?.let { preferences.resolution = it }
		}),
		preferences.fpsKey to Binding({ preferences.fps.value }, { v: String ->
			Preferences.FPS.values().firstOrNull { it.value == v }?.let { preferences.fps = it }
		}),
		preferences.codecKey to Binding({ preferences.codec.value }, { v: String ->
			Preferences.Codec.values().firstOrNull { it.value == v }?.let { preferences.codec = it }
		}),
		preferences.touchpadOverlayKey to Binding({ preferences.touchpadOverlay }, { v: String -> preferences.touchpadOverlay = v }),
		preferences.hiddenPsPositionKey to Binding({ preferences.hiddenPsPosition }, { v: String -> preferences.hiddenPsPosition = v })
	)

	private val ints = mapOf(
		preferences.rumbleStrengthKey to Binding({ preferences.rumbleStrength }, { v: Int -> preferences.rumbleStrength = v }),
		preferences.bitrateKey to Binding({ preferences.bitrate ?: 0 }, { v: Int -> preferences.bitrate = if(v <= 0) null else v }),
		preferences.hiddenPsSizeKey to Binding({ preferences.hiddenPsSize }, { v: Int -> preferences.hiddenPsSize = v })
	)

	override fun getBoolean(key: String?, defValue: Boolean) = key?.let { booleans[it]?.read?.invoke() } ?: defValue
	override fun putBoolean(key: String?, value: Boolean) { key?.let { booleans[it]?.write?.invoke(value) } }

	override fun getString(key: String?, defValue: String?): String? = key?.let { strings[it]?.read?.invoke() } ?: defValue
	override fun putString(key: String?, value: String?) { if(value != null) key?.let { strings[it]?.write?.invoke(value) } }

	override fun getInt(key: String?, defValue: Int) = key?.let { ints[it]?.read?.invoke() } ?: defValue
	override fun putInt(key: String?, value: Int) { key?.let { ints[it]?.write?.invoke(value) } }

	/** Used by DataStoreBindingsTest to check the XML and these tables agree. */
	fun hasBinding(key: String) = booleans.containsKey(key) || strings.containsKey(key) || ints.containsKey(key)
}

class SettingsFragment: PreferenceFragmentCompat(), TitleFragment
{
	companion object
	{
		private const val PICK_SETTINGS_JSON_REQUEST = 1
	}

	private var disposable = CompositeDisposable()
	private var exportDisposable = CompositeDisposable().also { it.addTo(disposable) }

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		val context = context ?: return

		val viewModel = ViewModelProvider(this, viewModelFactory { SettingsViewModel(getDatabase(context), Preferences(context)) })
			.get(SettingsViewModel::class.java)

		val preferences = viewModel.preferences
		preferenceManager.preferenceDataStore = DataStore(preferences)
		setPreferencesFromResource(R.xml.preferences, rootKey)

		preferenceScreen.findPreference<androidx.preference.SeekBarPreference>(preferences.bitrateKey)?.let {
			it.summaryProvider = androidx.preference.Preference.SummaryProvider<androidx.preference.SeekBarPreference> { pref ->
				val kbps = pref.value
				if(kbps <= 0)
					getString(R.string.preferences_bitrate_auto_summary, preferences.bitrateAuto / 1000)
				else
					getString(R.string.preferences_bitrate_value_summary, kbps / 1000)
			}
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_resolution_key))?.let {
			it.entryValues = Preferences.resolutionAll.map { res -> res.value }.toTypedArray()
			it.entries = Preferences.resolutionAll.map { res -> getString(res.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_fps_key))?.let {
			it.entryValues = Preferences.fpsAll.map { fps -> fps.value }.toTypedArray()
			it.entries = Preferences.fpsAll.map { fps -> getString(fps.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_codec_key))?.let {
			it.entryValues = Preferences.codecAll.map { codec -> codec.value }.toTypedArray()
			it.entries = Preferences.codecAll.map { codec -> getString(codec.title) }.toTypedArray()
		}

		val registeredHostsPreference = preferenceScreen.findPreference<Preference>("registered_hosts")
		viewModel.registeredHostsCount.observe(this, Observer {
			registeredHostsPreference?.summary = getString(R.string.preferences_registered_hosts_summary, it)
		})

		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_export_settings_key))?.setOnPreferenceClickListener { exportSettings(); true }
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_import_settings_key))?.setOnPreferenceClickListener { importSettings(); true }
	}
		
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		// EDGE-TO-EDGE: make bottom preferences reachable
		ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
			val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			v.setPadding(
				v.paddingLeft,
				v.paddingTop,
				v.paddingRight,
				systemBars.bottom // pushes content above nav bar
			)
			insets
		}
	}

	override fun onDestroy()
	{
		super.onDestroy()
		disposable.dispose()
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.title_settings)

	private fun exportSettings()
	{
		val activity = activity ?: return
		exportDisposable.clear()
		exportAndShareAllSettings(activity).addTo(exportDisposable)
	}

	private fun importSettings()
	{
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = "application/json"
		}
		startActivityForResult(intent, PICK_SETTINGS_JSON_REQUEST)
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		if(requestCode == PICK_SETTINGS_JSON_REQUEST && resultCode == Activity.RESULT_OK)
		{
			val activity = activity ?: return
			data?.data?.also {
				importSettingsFromUri(activity, it, disposable)
			}
		}
	}
}
