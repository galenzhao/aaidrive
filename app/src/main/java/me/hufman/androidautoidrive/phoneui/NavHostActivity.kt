package me.hufman.androidautoidrive.phoneui

import android.content.Intent
import android.os.Bundle
import android.view.MenuInflater
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Observer
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationView
import me.hufman.androidautoidrive.*
import me.hufman.androidautoidrive.databinding.NavHeaderBinding
import me.hufman.androidautoidrive.phoneui.controllers.PermissionsController
import me.hufman.androidautoidrive.phoneui.viewmodels.ConnectionStatusModel
import me.hufman.androidautoidrive.phoneui.viewmodels.viewModels

class NavHostActivity: AppCompatActivity() {

	private val connectionViewModel by viewModels<ConnectionStatusModel> { ConnectionStatusModel.Factory(this.applicationContext) }
	private var locationPrompted = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		AppSettings.loadSettings(this)
		if (AppSettings[AppSettings.KEYS.ENABLED_ANALYTICS].toBoolean()) {
			Analytics.init(this)
		}
		CarInformation.loadCache(MutableAppSettingsReceiver(applicationContext))

		if (!AppSettings[AppSettings.KEYS.FIRST_START_DONE].toBoolean()) {
			val intent = Intent(this, WelcomeActivity::class.java)
			startActivity(intent)
			finish()
			return
		}

		setContentView(R.layout.activity_navhost)
		val navToolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.nav_toolbar)
		val navView = findViewById<NavigationView>(R.id.nav_view)
		setSupportActionBar(navToolbar)

		// Set each of the menu entries as a top level destination
		val navMenu = PopupMenu(this, null).menu.also {
			MenuInflater(this).inflate(R.menu.menu_main, it)
		}
		val drawerLayout = findViewById<View>(R.id.drawer_layout) as? DrawerLayout
		val appBarConfig = AppBarConfiguration(navMenu, drawerLayout)
		val navController = findNavController(R.id.nav_host_fragment)
		setupActionBarWithNavController(navController, appBarConfig)        // title updater
		navToolbar.setupWithNavController(navController, appBarConfig)     // hamburger click handler
		navView.setupWithNavController(navController)                      // nav menu click handler

		setupNavHeader()

		setupNavMenu()

		drawerLayout?.post { drawerLayout.openDrawer(GravityCompat.START) }
	}

	override fun onResume() {
		super.onResume()

		startService()
		promptLocationIfNeeded()
	}

	private fun promptLocationIfNeeded() {
		if (locationPrompted) {
			return
		}
		locationPrompted = true
		window.decorView.post {
			if (!isFinishing) {
				val permissions = PermissionsController(this)
				permissions.promptLocationIfNeeded()
				permissions.promptBackgroundLocationIfNeeded()
			}
		}
	}

	fun setupNavHeader() {
		val navView = findViewById<NavigationView>(R.id.nav_view)
		val binding = NavHeaderBinding.inflate(layoutInflater, navView, false)
		binding.lifecycleOwner = this
		binding.viewModel = connectionViewModel
		navView.removeHeaderView(navView.getHeaderView(0))
		navView.addHeaderView(binding.root)

		val paneConnectionStatus = navView.getHeaderView(0).findViewById<View>(R.id.paneConnectionStatus)
		paneConnectionStatus.setOnClickListener {
			findNavController(R.id.nav_host_fragment).navigate(R.id.nav_connection)
			val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
			drawerLayout?.closeDrawer(GravityCompat.START)
		}
	}

	fun setupNavMenu() {
		val themeAttrs = obtainStyledAttributes(R.style.optionMapVisible, arrayOf(android.R.attr.visibility).toIntArray())
		val mapVisibility = themeAttrs.getInt(0, 0)
		themeAttrs.recycle()
		val navView = findViewById<NavigationView>(R.id.nav_view)
		val mapsVisible = mapVisibility == View.VISIBLE
		navView.menu.findItem(R.id.nav_maps).isVisible = mapsVisible
		navView.menu.findItem(R.id.nav_map_quick_destinations).isVisible = mapsVisible

		val advancedSetting = BooleanLiveSetting(this, AppSettings.KEYS.SHOW_ADVANCED_SETTINGS)
		advancedSetting.observe(this, Observer {
			navView.menu.findItem(R.id.nav_connection).isVisible = it
		})
	}

	fun startService() {
		try {
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
				// this is a clear signal of car connection, we can confidently startForeground
				this.startForegroundService(Intent(this, MainService::class.java).setAction(MainService.ACTION_START))
			} else {
				this.startService(Intent(this, MainService::class.java).setAction(MainService.ACTION_START))
			}
		} catch (e: IllegalStateException) {
			// Android Oreo strenuously objects to starting the service if the activity isn't visible
			// for example, when Android Studio tries to start the Activity with the screen off
		}
	}

	override fun onPause() {
		super.onPause()
		// stop animations in the nav header
		connectionViewModel.onPause()
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		if (requestCode == PermissionsController.REQUEST_LOCATION) {
			PermissionsController(this).promptBackgroundLocationIfNeeded()
		}
	}
}