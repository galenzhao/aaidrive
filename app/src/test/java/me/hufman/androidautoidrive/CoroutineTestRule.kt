package me.hufman.androidautoidrive

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineTestRule(val testDispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher()  {
	val testDispatcherProvider = object : DispatcherProvider {
		override val Default: CoroutineDispatcher = testDispatcher
		override val Main: CoroutineDispatcher = testDispatcher
		override val IO: CoroutineDispatcher = testDispatcher
		override val Unconfined: CoroutineDispatcher = testDispatcher
	}

	override fun starting(description: Description?) {
		super.starting(description)
		Dispatchers.setMain(testDispatcher)
	}

	override fun finished(description: Description?) {
		super.finished(description)
		Dispatchers.resetMain()
	}

	fun runTest(block: suspend TestScope.() -> Unit) =
		runTest(context = testDispatcher, testBody = block)
}
