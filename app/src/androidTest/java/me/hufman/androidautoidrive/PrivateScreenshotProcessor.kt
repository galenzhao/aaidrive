package me.hufman.androidautoidrive

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/** Saves instrumentation screenshots under the app's external files dir. */
class PrivateScreenshotProcessor(context: Context) {
	private val imageFolder = File(
		context.getExternalFilesDir(null)!!.absolutePath,
		"screenshots"
	)

	fun save(name: String, bitmap: Bitmap) {
		imageFolder.mkdirs()
		val imageFile = File(imageFolder, "$name.png")
		println("Saving to $imageFile")
		imageFile.outputStream().use { out ->
			bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
		}
	}
}
