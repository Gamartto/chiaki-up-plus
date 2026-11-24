// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.gameblabla.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.graphics.Matrix
import android.os.*
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.gameblabla.chiaki.R
import com.gameblabla.chiaki.common.Preferences
import com.gameblabla.chiaki.common.ext.viewModelFactory
import com.gameblabla.chiaki.databinding.ActivityStreamBinding
import com.gameblabla.chiaki.lib.ConnectInfo
import com.gameblabla.chiaki.lib.ConnectVideoProfile
import com.gameblabla.chiaki.session.*
import com.gameblabla.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.gameblabla.chiaki.touchcontrols.HiddenPsGeometry
import com.gameblabla.chiaki.touchcontrols.HiddenPsHotspotView
import com.gameblabla.chiaki.touchcontrols.TouchControlsFragment
import com.gameblabla.chiaki.touchcontrols.TouchpadOnlyFragment
import io.reactivex.disposables.CompositeDisposable
import java.util.Locale
import io.reactivex.rxkotlin.addTo
import kotlin.math.min

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

class StreamActivity : AppCompatActivity(), View.OnSystemUiVisibilityChangeListener
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val HIDE_UI_TIMEOUT_MS = 2000L
		private const val RP_IN_USE_MAX_RETRIES = 4
		private const val RP_IN_USE_RETRY_MS = 2500L
		private const val SESSION_RELEASE_GRACE_MS = 2500L
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding

	private val uiVisibilityHandler = Handler()

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		// The video profile is read here rather than trusted from the intent: a
		// mid-session bitrate change saves the preference and recreates the
		// activity, and this is what makes the new ceiling take effect.
		val connectInfo = intent.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)?.copy(
			videoProfile = Preferences(this).videoProfile
		)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)
		viewModel.input.statsToggleCallback = {
			viewModel.setStatsEnabled(!(viewModel.statsEnabled.value ?: false))
		}

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
		window.decorView.setOnSystemUiVisibilityChangeListener(this)

		viewModel.onScreenControlsEnabled.observe(this, Observer {
			if(binding.onScreenControlsSwitch.isChecked != it)
				binding.onScreenControlsSwitch.isChecked = it
			if(binding.onScreenControlsSwitch.isChecked)
				binding.touchpadOnlySwitch.isChecked = false
		})
		binding.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}

		viewModel.touchpadOnlyEnabled.observe(this, Observer {
			if(binding.touchpadOnlySwitch.isChecked != it)
				binding.touchpadOnlySwitch.isChecked = it
			if(binding.touchpadOnlySwitch.isChecked)
				binding.onScreenControlsSwitch.isChecked = false
		})
		binding.touchpadOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setTouchpadOnlyEnabled(isChecked)
			showOverlay()
		}

		viewModel.statsEnabled.observe(this, Observer {
			// Syncing the switch must not look like the user flipping it: the
			// listener below shows the overlay, which then stayed on screen.
			suppressSwitchCallback = true
			if(binding.statsSwitch.isChecked != it)
				binding.statsSwitch.isChecked = it
			suppressSwitchCallback = false
			binding.statsText.visibility = if(it) View.VISIBLE else View.GONE
			if(it)
			{
				applyStatsPosition()
				startStatsPolling()
			}
			else
				stopStatsPolling()
		})
		binding.statsSwitch.setOnCheckedChangeListener { _, isChecked ->
			if(suppressSwitchCallback)
				return@setOnCheckedChangeListener
			viewModel.setStatsEnabled(isChecked)
			showOverlay()
		}

		binding.displayModeToggle.addOnButtonCheckedListener { _, _, _ ->
			adjustStreamViewAspect()
			showOverlay()
		}

		//viewModel.session.attachToTextureView(textureView)
		viewModel.session.attachToSurfaceView(binding.surfaceView)
		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()

		setupHiddenPsHotspot()
		binding.psButtonButton.setOnClickListener { showHiddenPsDialog() }
		updateBitrateButton()
		binding.bitrateButton.setOnClickListener { showBitrateDialog() }

		viewModel.session.keyboardState.observe(this, Observer { text ->
			if(text == null)
			{
				keyboardDialog?.dismiss()
				keyboardDialog = null
			}
			else
				showKeyboardDialog(text)
		})

		val rumblePreferences = Preferences(this)
		if(rumblePreferences.rumbleEnabled)
		{
			val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
			// Strength is a percentage so 0 equals off and 150 exaggerates the
			// motor, clamped to what the API accepts.
			val strength = rumblePreferences.rumbleStrength
			viewModel.session.rumbleState.observe(this, Observer {
				val base = (it.left.toInt() + it.right.toInt()) / 2
				val amplitude = min(255, base * strength / 100)
				vibrator.cancel()
				if(amplitude == 0)
					return@Observer
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
					vibrator.vibrate(VibrationEffect.createOneShot(1000, amplitude))
				else
					vibrator.vibrate(1000)
			})
		}
	}

	// In-stream statistics: video bitrate and frame rate come from the decoder
	// counters, packet loss from the lib. All four values are cumulative, so the
	// overlay shows the delta against the previous sample.
	private var keyboardDialog: androidx.appcompat.app.AlertDialog? = null

	// A reconnect right after dropping a session is refused with RP_IN_USE while
	// the console finishes releasing it, so retry quietly before showing the dialog.
	private var rpInUseRetries = 0
	private val reconnectHandler = Handler(Looper.getMainLooper())
	private var suppressSwitchCallback = false
	private val statsHandler = Handler(Looper.getMainLooper())
	private var statsLastSample = 0L
	private var statsLastBytes = 0L
	private var statsLastReceived = 0L
	private var statsLastLost = 0L

	private val statsRunnable = object : Runnable
	{
		override fun run()
		{
			updateStats()
			statsHandler.postDelayed(this, 1000)
		}
	}

	// The console asks for text and we forward whatever is typed, live. Accepting
	// closes the console-side field; cancelling rejects it there too.
	private fun showKeyboardDialog(initial: String)
	{
		if(keyboardDialog != null)
			return

		val editText = EditText(this).apply {
			setText(initial)
			setSelection(initial.length)
			hint = getString(R.string.stream_keyboard_hint)
		}

		val dialog = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.stream_keyboard_title)
			.setView(editText)
			.setPositiveButton(android.R.string.ok) { _, _ -> viewModel.session.keyboardAccept() }
			.setNegativeButton(android.R.string.cancel) { _, _ -> viewModel.session.keyboardReject() }
			.setOnDismissListener { keyboardDialog = null }
			.create()

		editText.addTextChangedListener(object : TextWatcher
		{
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }
			override fun afterTextChanged(s: Editable?)
			{
				viewModel.session.keyboardText(s?.toString() ?: "")
			}
		})

		keyboardDialog = dialog
		dialog.show()
	}

	// The hotspot is laid out by the view itself from the radius and position, so
	// here it is only wired to the same tap the Start + Select combo produces.
	private fun setupHiddenPsHotspot()
	{
		val preferences = Preferences(this)
		val hotspot = binding.hiddenPsHotspot
		if(!preferences.hiddenPsEnabled)
		{
			hotspot.visibility = View.GONE
			return
		}
		val density = resources.displayMetrics.density
		// The view's own size is the truth when it is already laid out; the display
		// metrics are the fallback for the first pass. They can differ in split
		// screen or with insets.
		val widthPx = if(binding.mainStreamLayout.width > 0) binding.mainStreamLayout.width.toFloat()
			else resources.displayMetrics.widthPixels.toFloat()
		val heightPx = if(binding.mainStreamLayout.height > 0) binding.mainStreamLayout.height.toFloat()
			else resources.displayMetrics.heightPixels.toFloat()
		// The pad is inset from the edges, so a big hotspot could grow into it and
		// steal its touches. The radius is capped by that free gap.
		val freeGap = HiddenPsGeometry.maxRadiusPx(
			widthPx, heightPx, HiddenPsGeometry.PAD_SIDE_MARGIN_DP * density, density
		)
		val requested = preferences.hiddenPsSize * density
		if(freeGap <= 0f)
		{
			// No room at all (the pad fills the screen, e.g. ultrawide). A button
			// that had to overlap the pad would steal its touches, so the feature
			// stays off rather than half-working.
			hotspot.visibility = View.GONE
			return
		}
		hotspot.radiusPx = minOf(requested, freeGap)
		hotspot.position = when(preferences.hiddenPsPosition)
		{
			"top_left" -> HiddenPsHotspotView.Position.TOP_LEFT
			"top_right" -> HiddenPsHotspotView.Position.TOP_RIGHT
			"bottom_left" -> HiddenPsHotspotView.Position.BOTTOM_LEFT
			"bottom_center" -> HiddenPsHotspotView.Position.BOTTOM_CENTER
			"bottom_right" -> HiddenPsHotspotView.Position.BOTTOM_RIGHT
			else -> HiddenPsHotspotView.Position.TOP_CENTER
		}
		hotspot.visibilityMode = when(preferences.hiddenPsVisibility)
		{
			"always" -> HiddenPsHotspotView.Visibility.ALWAYS
			"while_press" -> HiddenPsHotspotView.Visibility.ON_PRESS
			else -> HiddenPsHotspotView.Visibility.NEVER
		}
		hotspot.onTrigger = { viewModel.session.input.sendPsTap() }
		hotspot.visibility = View.VISIBLE
	}

	private val hiddenPsPositions = arrayOf(
		"top_left", "top_center", "top_right",
		"bottom_left", "bottom_center", "bottom_right"
	)

	private val hiddenPsVisibilities = arrayOf("never", "always", "while_press")

	private val hiddenPsVisibilityLabels = arrayOf("Never", "Always", "While held")

	private val hiddenPsPositionLabels = arrayOf(
		"Top left", "Top centre", "Top right",
		"Bottom left", "Bottom centre", "Bottom right"
	)

	// Reachable from the overlay so the button can be moved or shown without
	// leaving the session.
	private fun showHiddenPsDialog()
	{
		val preferences = Preferences(this)
		val density = resources.displayMetrics.density
		val padding = (20 * density).toInt()

		val enabledSwitch = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
			text = getString(R.string.stream_ps_enabled)
			isChecked = preferences.hiddenPsEnabled
		}
		val positionLabel = TextView(this).apply {
			text = getString(R.string.stream_ps_position)
			setPadding(0, padding / 2, 0, 0)
		}
		val spinner = android.widget.Spinner(this).apply {
			adapter = android.widget.ArrayAdapter(this@StreamActivity,
				android.R.layout.simple_spinner_dropdown_item, hiddenPsPositionLabels)
			setSelection(hiddenPsPositions.indexOf(preferences.hiddenPsPosition).coerceAtLeast(0))
			isEnabled = enabledSwitch.isChecked
			// Applies as soon as it is picked: moving the button is a live tweak,
			// not a form to submit.
			onItemSelectedListener = object: android.widget.AdapterView.OnItemSelectedListener
			{
				override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long)
				{
					preferences.hiddenPsPosition = hiddenPsPositions.getOrElse(position) { "top_center" }
					setupHiddenPsHotspot()
				}
				override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
			}
		}

		val visibilityLabel = TextView(this).apply {
			text = getString(R.string.stream_ps_visibility)
			setPadding(0, padding / 2, 0, 0)
		}
		val visibilitySpinner = android.widget.Spinner(this).apply {
			adapter = android.widget.ArrayAdapter(this@StreamActivity,
				android.R.layout.simple_spinner_dropdown_item, hiddenPsVisibilityLabels)
			setSelection(hiddenPsVisibilities.indexOf(preferences.hiddenPsVisibility).coerceAtLeast(0))
			isEnabled = enabledSwitch.isChecked
		}
		enabledSwitch.setOnCheckedChangeListener { _, checked ->
			preferences.hiddenPsEnabled = checked
			spinner.isEnabled = checked
			visibilitySpinner.isEnabled = checked
			setupHiddenPsHotspot()
		}

		visibilitySpinner.onItemSelectedListener = object: android.widget.AdapterView.OnItemSelectedListener
		{
			override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long)
			{
				preferences.hiddenPsVisibility = hiddenPsVisibilities.getOrElse(position) { "never" }
				setupHiddenPsHotspot()
			}
			override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
		}

		val container = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(padding, 0, padding, 0)
			addView(enabledSwitch)
			addView(visibilityLabel)
			addView(visibilitySpinner)
			addView(positionLabel)
			addView(spinner)
		}

		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.stream_ps_dialog_title)
			.setView(container)
			.setPositiveButton(android.R.string.ok) { _, _ -> }
			.show()
	}

	private fun currentBitrateMbps() = (Preferences(this).bitrate ?: 0) / 1000

	private fun updateBitrateButton()
	{
		val mbps = currentBitrateMbps()
		binding.bitrateButton.text = if(mbps <= 0)
			getString(R.string.preferences_bitrate_title) + " · Auto"
		else
			getString(R.string.preferences_bitrate_value_summary, mbps)
	}

	// The bitrate is part of the launch specification, so the only way to change it
	// is to open a new session: save the value and let the activity rebuild, which
	// shuts the current session down and connects again with the new ceiling.
	private fun showBitrateDialog()
	{
		val preferences = Preferences(this)
		val slider = Slider(this).apply {
			valueFrom = 0f
			valueTo = 100f
			stepSize = 5f
			val current = currentBitrateMbps()
			value = if(current <= 0) 0f else current.toFloat().coerceIn(5f, 100f)
		}

		val valueLabel = TextView(this).apply {
			textSize = 18f
			gravity = Gravity.CENTER_HORIZONTAL
			val density = resources.displayMetrics.density
			setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
		}
		fun describe(mbps: Float) =
			if(mbps <= 0f) getString(R.string.preferences_bitrate_title) + " · Auto"
			else getString(R.string.preferences_bitrate_value_summary, mbps.toInt())
		valueLabel.text = describe(slider.value)
		slider.addOnChangeListener { _, value, _ -> valueLabel.text = describe(value) }

		val container = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			val density = resources.displayMetrics.density
			setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
			addView(valueLabel)
			addView(slider)
		}

		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.stream_bitrate_dialog_title)
			.setMessage(R.string.stream_bitrate_dialog_hint)
			.setView(container)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				val mbps = slider.value.toInt()
				val newValue = if(mbps <= 0) null else mbps * 1000
				if(newValue != preferences.bitrate)
				{
					preferences.bitrate = newValue
					// Rebuilding the session in place instead of recreating the
					// activity: the ViewModel (and its ConnectInfo) survive a
					// recreate, so that path reopened with the old bitrate and left
					// the screen dead. The console still needs a moment to release
					// the previous session, hence the grace delay.
					viewModel.session.shutdown()
					reconnectHandler.postDelayed({
						viewModel.session.restartWithProfile(Preferences(this).videoProfile)
					}, SESSION_RELEASE_GRACE_MS)
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun startStatsPolling()
	{
		statsHandler.removeCallbacks(statsRunnable)
		statsHandler.post(statsRunnable)
	}

	private fun stopStatsPolling()
	{
		statsHandler.removeCallbacks(statsRunnable)
	}

	// Top by default, bottom when the user asks for it. The bottom variant keeps
	// clear of the overlay bar.
	private fun applyStatsPosition()
	{
		val lp = binding.statsText.layoutParams as? FrameLayout.LayoutParams ?: return
		val margin = (6 * resources.displayMetrics.density).toInt()
		val bottomClearance = (56 * resources.displayMetrics.density).toInt()
		if(viewModel.preferences.statsAtBottom)
		{
			lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
			lp.bottomMargin = bottomClearance
			lp.topMargin = 0
		}
		else
		{
			lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
			lp.topMargin = margin
			lp.bottomMargin = 0
		}
		binding.statsText.layoutParams = lp
	}

	private fun updateStats()
	{
		val stats = viewModel.session.stats()
		if(stats == null || stats.size < 4)
		{
			binding.statsText.text = getString(R.string.stream_stats_label)
			return
		}

		val now = SystemClock.uptimeMillis()
		val bytes = stats[0]
		val received = stats[1]
		val lost = stats[2]
		val rttMs = if(stats.size > 3) stats[3] / 1000.0 else 0.0

		if(statsLastSample == 0L)
		{
			statsLastSample = now
			statsLastBytes = bytes
			statsLastReceived = received
			statsLastLost = lost
			return
		}

		val dt = (now - statsLastSample) / 1000.0
		if(dt <= 0.0)
			return

		val mbps = (bytes - statsLastBytes) * 8.0 / dt / 1000000.0
		val dReceived = received - statsLastReceived
		val dLost = lost - statsLastLost
		val total = dReceived + dLost
		val loss = if(total > 0) dLost * 100.0 / total else 0.0

		binding.statsText.text = String.format(Locale.US, "%.1f Mbps · %.1f%% lost · %.0f ms", mbps, loss, rttMs)

		statsLastSample = now
		statsLastBytes = bytes
		statsLastReceived = received
		statsLastLost = lost
	}

	private val controlsDisposable = CompositeDisposable()

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			fragment.controllerState
				.subscribe { viewModel.input.touchControllerState = it }
				.addTo(controlsDisposable)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
			if(fragment is TouchpadOnlyFragment)
				fragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
		}
	}

	override fun onResume()
	{
		super.onResume()
		// The LiveData observer owns the polling loop: starting a second chain
		// here made the sample windows uneven and the fps readout jump.
		if(viewModel.statsEnabled.value == true)
		{
			applyStatsPosition()
			startStatsPolling()
		}
		hideSystemUI()
		viewModel.session.resume()
	}

	override fun onPause()
	{
		statsHandler.removeCallbacks(statsRunnable)
		super.onPause()
		viewModel.session.pause()
	}

	override fun onDestroy()
	{
		reconnectHandler.removeCallbacksAndMessages(null)
		super.onDestroy()
		controlsDisposable.dispose()
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	private val hideSystemUIRunnable = Runnable { hideSystemUI() }
	private val hideOverlayRunnable = Runnable { hideOverlay() }

	override fun onSystemUiVisibilityChange(visibility: Int)
	{
		if(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0)
			showOverlay()
		else
			hideOverlay()
	}

	private fun showOverlay()
	{
		// While the UI is up the hotspot steps aside: at the bottom it would sit on
		// top of the overlay bar and steal touches from its switches.
		if(Preferences(this).hiddenPsEnabled)
			binding.hiddenPsHotspot.visibility = View.GONE
		binding.overlay.isVisible = true
		binding.overlay.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.alpha = 1.0f
				}
			})
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)

		// Hiding the system UI does not always deliver the visibility callback
		// that used to be the only thing closing this bar, so it could stay on
		// screen over the game. Close it on our own clock as well.
		uiVisibilityHandler.removeCallbacks(hideOverlayRunnable)
		uiVisibilityHandler.postDelayed(hideOverlayRunnable, HIDE_UI_TIMEOUT_MS + 1200L)
	}

	private fun hideOverlay()
	{
		if(Preferences(this).hiddenPsEnabled)
			binding.hiddenPsHotspot.visibility = View.VISIBLE
		binding.overlay.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.isGone = true
				}
			})
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
		window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
				or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_FULLSCREEN)
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	private fun stateChanged(state: StreamState)
	{
		binding.progressBar.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE

		when(state)
		{
			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isRemotePlayInUse && rpInUseRetries < RP_IN_USE_MAX_RETRIES)
					{
						rpInUseRetries++
						reconnectHandler.postDelayed({ reconnect() }, RP_IN_USE_RETRY_MS)
						return
					}

					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(getString(R.string.alert_message_session_quit, state.reason.toString())
									+ (if(reasonStr != null) "\n$reasonStr" else ""))
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}

			StreamStateConnected -> rpInUseRetries = 0

			else -> {}
		}
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent) = viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	override fun onGenericMotionEvent(event: MotionEvent) = viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.display_mode_stretch_button -> STRETCH
				R.id.display_mode_zoom_button -> ZOOM
				else -> FIT
			}
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
