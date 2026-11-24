// SPDX-License-Identifier: AGPL-3.0-only-OpenSSL

package com.gameblabla.chiaki.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gameblabla.chiaki.R
import com.gameblabla.chiaki.common.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The settings screen persists through a PreferenceDataStore, which silently drops
 * any key it does not know about. Every preference declared in preferences.xml
 * therefore has to appear in DataStore's tables, and this test keeps the two in
 * step: it reads the XML the app actually ships and asks the store whether each
 * key is bound. Two bugs came from exactly this mismatch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DataStoreBindingsTest
{
	private val context = ApplicationProvider.getApplicationContext<Context>()

	// Preferences that store nothing on purpose: click targets and a summary row.
	private val actionKeys = setOf("export_settings", "import_settings", "registered_hosts", "logs")

	private fun declaredKeys(): Set<String>
	{
		val parser = context.resources.getXml(R.xml.preferences)
		val keys = mutableSetOf<String>()
		var event = parser.eventType
		while(event != XmlPullParser.END_DOCUMENT)
		{
			// Only real preferences: PreferenceCategory and PreferenceScreen also
			// carry keys, but nothing is stored under them.
			if(event == XmlPullParser.START_TAG && parser.name.endsWith("Preference"))
			{
				parser.getAttributeValue("http://schemas.android.com/apk/res-auto", "key")?.let {
					keys.add(resolve(it))
				}
			}
			event = parser.next()
		}
		return keys
	}

	private fun resolve(raw: String): String
	{
		// Compiled XML hands back "@12345" for a resource reference; the source form
		// "@string/name" only appears when the file was not processed.
		if(raw.startsWith("@") && raw.drop(1).toIntOrNull() != null)
			return context.getString(raw.drop(1).toInt())
		if(!raw.startsWith("@string/"))
			return raw
		val name = raw.removePrefix("@string/")
		val id = context.resources.getIdentifier(name, "string", context.packageName)
		return if(id == 0) name else context.getString(id)
	}

	@Test
	fun everyDeclaredPreferenceHasABinding()
	{
		val store = DataStore(Preferences(context))
		val unbound = declaredKeys()
			.filterNot { it in actionKeys }
			.filterNot { store.hasBinding(it) }

		assertTrue("declared in preferences.xml but missing from DataStore: $unbound", unbound.isEmpty())
	}

	@Test
	fun roundTripStoresAndReadsBack()
	{
		val preferences = Preferences(context)
		val store = DataStore(preferences)

		store.putBoolean(preferences.hiddenPsEnabledKey, true)
		assertTrue(store.getBoolean(preferences.hiddenPsEnabledKey, false))

		store.putInt(preferences.bitrateKey, 45000)
		assertEquals(45000, store.getInt(preferences.bitrateKey, 0))

		store.putString(preferences.touchpadOverlayKey, "never")
		assertEquals("never", store.getString(preferences.touchpadOverlayKey, null))
	}
}
