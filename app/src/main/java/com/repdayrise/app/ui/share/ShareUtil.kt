package com.repdayrise.app.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ShareUtil {
    suspend fun shareBitmap(context: Context, bitmap: Bitmap, title: String) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, "dayrise-${System.currentTimeMillis()}.png")
            FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            f
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
