package me.hufman.androidautoidrive.phoneui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.phoneui.fragments.welcome.*

class WelcomeActivity: AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		setContentView(R.layout.activity_welcome)

		val navToolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.nav_toolbar)
		setSupportActionBar(navToolbar)
		val origPaddingTop = navToolbar.paddingTop
		navToolbar.setOnApplyWindowInsetsListener { v, insets ->
			val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, v)
			val top = compat.getInsets(WindowInsetsCompat.Type.systemBars()).top
			v.updatePadding(top = origPaddingTop + top)
			insets
		}

		val pgrWelcomeTabs = findViewById<ViewPager2>(R.id.pgrWelcomeTabs)
		val adapter = FirstStartPagerAdapter(this)
		pgrWelcomeTabs.adapter = adapter

		TabLayoutMediator(findViewById(R.id.tabWelcomeTabs), pgrWelcomeTabs) { _, _ -> }.attach()

		onBackPressedDispatcher.addCallback {
			if (pgrWelcomeTabs.currentItem == 0) {
				// pass through default behavior, to close the Activity
				this.isEnabled = false
				onBackPressedDispatcher.onBackPressed()
			} else {
				pgrWelcomeTabs.currentItem = pgrWelcomeTabs.currentItem - 1
			}
		}
	}

	override fun onResume() {
		super.onResume()

		findViewById<Button>(R.id.btnNext).setOnClickListener {
			val pgrWelcomeTabs = findViewById<ViewPager2>(R.id.pgrWelcomeTabs)
			if (pgrWelcomeTabs.currentItem != (pgrWelcomeTabs.adapter?.itemCount ?: 0) - 1) {
				pgrWelcomeTabs.currentItem = pgrWelcomeTabs.currentItem + 1
			} else {
				AppSettings.saveSetting(this, AppSettings.KEYS.FIRST_START_DONE, "true")
				val intent = Intent(this, NavHostActivity::class.java)
				intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
				startActivity(intent)
				finish()
			}
		}
	}
}

class FirstStartPagerAdapter(fragmentActivity: FragmentActivity): FragmentStateAdapter(fragmentActivity) {
	private val includeAnalytics = WelcomeAnalyticsFragment.isSupported()

	override fun getItemCount(): Int = if (includeAnalytics) 6 else 5

	override fun createFragment(position: Int): Fragment {
		var index = position
		if (index == 0) return WelcomeFragment()
		index--
		if (index == 0) return WelcomeDependenciesFragment()
		index--
		if (index == 0) return WelcomeNotificationFragment()
		index--
		if (index == 0) return WelcomeMusicFragment()
		index--
		if (includeAnalytics) {
			if (index == 0) return WelcomeAnalyticsFragment()
			index--
		}
		if (index == 0) return WelcomeCompleteFragment()
		throw IllegalArgumentException("Unknown welcome page $position")
	}
}
