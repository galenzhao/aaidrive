package me.hufman.androidautoidrive.phoneui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.databinding.MusicPlayerBinding
import me.hufman.androidautoidrive.music.MusicAppDiscovery
import me.hufman.androidautoidrive.music.MusicAppInfo
import me.hufman.androidautoidrive.phoneui.controllers.MusicPlayerController
import me.hufman.androidautoidrive.phoneui.fragments.*
import me.hufman.androidautoidrive.phoneui.viewmodels.MusicActivityIconsModel
import me.hufman.androidautoidrive.phoneui.viewmodels.MusicActivityModel
import me.hufman.androidautoidrive.phoneui.viewmodels.viewModels

class MusicPlayerActivity: AppCompatActivity() {

	companion object {
		const val TAG = "MusicPlayerActivity"
	}

	// the viewmodels used by the fragments
	val musicActivityModel by viewModels<MusicActivityModel> { MusicActivityModel.Factory(applicationContext, UIState.selectedMusicApp) }
	val musicActivityIconsModel by viewModels<MusicActivityIconsModel> { MusicActivityIconsModel.Factory(this) }
	val musicPlayerController by lazy { MusicPlayerController(null, musicActivityModel.musicController) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val musicApp = UIState.selectedMusicApp ?: return

		discoverApp(musicApp)

		val binding = MusicPlayerBinding.inflate(layoutInflater)
		binding.lifecycleOwner = this
		binding.viewModel = musicActivityModel
		binding.iconsModel = musicActivityIconsModel    // need to touch this so that it's ready for the fragments, even though we don't use it here
		setContentView(binding.root)

		val navToolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.nav_toolbar)
		setSupportActionBar(navToolbar)
		val origPaddingTop = navToolbar.paddingTop
		navToolbar.setOnApplyWindowInsetsListener { v, insets ->
			val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, v)
			val top = compat.getInsets(WindowInsetsCompat.Type.systemBars()).top
			v.updatePadding(top = origPaddingTop + top)
			insets
		}

		val pgrMusicPlayer = findViewById<ViewPager2>(R.id.pgrMusicPlayer)
		musicPlayerController.viewPager = pgrMusicPlayer

		// set up the paging
		val adapter = MusicPlayerPagerAdapter(this)
		pgrMusicPlayer.adapter = adapter
		pgrMusicPlayer.offscreenPageLimit = 2
		TabLayoutMediator(findViewById(R.id.tabMusicPlayer), pgrMusicPlayer) { tab, position ->
			tab.text = adapter.getPageTitle(position)
		}.attach()

		onBackPressedDispatcher.addCallback {
			val currentItem = pgrMusicPlayer.currentItem
			if (currentItem == 0) {
				this.isEnabled = false
				onBackPressedDispatcher.onBackPressed()
			} else if (currentItem == 1) {
				val container = adapter.getFragment(1) as? MusicBrowseFragment
				val popped = container?.onBackPressed() == true
				if (!popped) {
					musicPlayerController.showNowPlaying()
				}
			} else if (currentItem == 2) {
				// go back to the main playback page
				musicPlayerController.showNowPlaying()
			} else if (currentItem == 3) {
				// go back to the main playback page
				musicPlayerController.showNowPlaying()
			}
		}
	}

	fun discoverApp(musicAppInfo: MusicAppInfo) {
		val musicAppDiscovery = MusicAppDiscovery(this, Handler(Looper.getMainLooper()))
		musicAppDiscovery.loadInstalledMusicApps()
		musicAppDiscovery.probeApp(musicAppInfo)
	}

	override fun onDestroy() {
		super.onDestroy()
		musicPlayerController.viewPager = null
		UIState.selectedMusicApp = null
	}
}

class MusicPlayerPagerAdapter(private val fragmentActivity: FragmentActivity): FragmentStateAdapter(fragmentActivity) {
	private val titles = listOf("Now Playing", "Browse", "Queue", "Search")

	override fun getItemCount(): Int = titles.size

	fun getPageTitle(position: Int): CharSequence = titles[position]

	override fun createFragment(position: Int): Fragment {
		return when (position) {
			0 -> MusicNowPlayingFragment()
			1 -> MusicBrowseFragment.newInstance(MusicBrowsePageFragment.newInstance(null))
			2 -> MusicQueueFragment()
			3 -> MusicSearchFragment()
			else -> throw IllegalArgumentException("Unknown music player page $position")
		}
	}

	fun getFragment(position: Int): Fragment? {
		return fragmentActivity.supportFragmentManager.findFragmentByTag("f$position")
	}
}
