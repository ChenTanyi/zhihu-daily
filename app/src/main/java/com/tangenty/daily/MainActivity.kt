package com.tangenty.daily

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.text.isDigitsOnly
import androidx.core.view.children
import androidx.core.view.get
import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Exception
import java.lang.ref.WeakReference
import java.net.URL
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentSkipListMap

class MainActivity : AppCompatActivity() {
    private val gson = Gson()
    private val handler = UIHandler(this)
    private val timeout = 10 * 1000
    private lateinit var cache : Cache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cache = loadCache()

        if (cache.latestDate > 0) {
            findViewById<EditText>(R.id.dateLatest).apply {
                setText(cache.latestDate.toString())
            }
        }
        if (cache.currentDate > 0) {
            findViewById<EditText>(R.id.dateCurrent).apply {
                setText(cache.currentDate.toString())
            }
        }
    }

    fun load(view: View) {
        val dateItem = findViewById<EditText>(R.id.dateCurrent)
        val date = dateItem.text.toString()
        if (date.isDigitsOnly()) {
            cache.currentDate = date.toInt()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Wrong Date")
                .setMessage("Date should be \"YYYYMMDD\" but get $date")
                .show()
            return
        }

        Thread {
            try {
                var stories = cache.storiesCache[date]
                if (stories == null) {
                    val url = getString(R.string.daily_api, date)
                    Log.d(LOADT, "Load url: $url")
                    requestGet(url).use {
                        val content = it.readBytes().toString(Charsets.UTF_8)
                        Log.d(LOADT, "Receive stories: $content")
                        val result = gson.fromJson(content, Stories::class.java)
                        val preResult = cache.storiesCache.putIfAbsent(date, result)
                        stories = if (preResult == null) {
                            cache.lru.addLast(date)
                            if (cache.lru.size > MAX_CACHE_SIZE) {
                                val oldDate = cache.lru.removeFirst()
                                cache.storiesCache.remove(oldDate)
                            }
                            result
                        } else {
                            preResult
                        }
                    }
                }
                handler.sendMessage(handler.obtainMessage(UI_INIT, stories))

                for (story in stories!!.stories) {
                    if (story.imageBlob.isNullOrBlank() && !story.images.isNullOrEmpty() && !story.images[0].isNullOrBlank()) {
                        requestGet(story.images[0]).use { imageStream ->
                            val blob = imageStream.readBytes()
                            story.imageBlob =
                                Base64.encodeToString(blob, Base64.DEFAULT)
                            val imageMsg = handler.obtainMessage(UI_UPDATE_IMAGE, story.id, 0, BitmapFactory.decodeByteArray(blob, 0, blob.size))
                            handler.sendMessage(imageMsg)
                        }
                    }
                }
                for (story in stories!!.stories) {
                    if (story.body.isNullOrBlank()) {
                        requestGet(story.url).use { storyStream ->
                            story.body = storyStream.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                }

                handler.sendMessage(handler.obtainMessage(UI_INIT, stories))
            } catch (e: Exception) {
                val msg = handler.obtainMessage(UI_SHOW_ALERT_DIALOG, e)
                handler.sendMessage(msg)
            }
        }.start()
        Log.d(LOADT, "Start Loading")
    }

    fun sync(view: View) {
        val latestDate = findViewById<EditText>(R.id.dateLatest)
        var latest = latestDate.text.toString()
        if (!(latest.isNotBlank() && latest.isDigitsOnly() && latest.toInt() > cache.currentDate)) {
            latestDate.setText(cache.currentDate.toString())
        }
        latest = latestDate.text.toString()

        cache.latestDate = latest.toInt()
        saveCache(cache)
    }

    private fun requestGet(url: String) : InputStream {
        return requestGet(URL(url))
    }

    private fun requestGet(url: URL) : InputStream {
        val conn = url.openConnection()
        conn.connectTimeout = timeout
        conn.readTimeout = timeout
        conn.setRequestProperty("User-Agent", Constants.USER_AGENT)
        return conn.inputStream
    }

    private fun loadCache() : Cache {
        val cacheFile = File(filesDir, CACHE_FILENAME)
        if (cacheFile.isFile) {
            try {
                return gson.fromJson(cacheFile.readText(), Cache::class.java)
            } catch (e: Exception) {
                Log.e(LOAD_CACHET, "Unable to load cache: $e")
            }
        } else {
            cacheFile.delete()
        }
        return Cache(0, 0, ConcurrentLinkedDeque(), ConcurrentSkipListMap())
    }

    private fun saveCache(cache: Cache) {
        val cacheFile = File(filesDir, CACHE_FILENAME)
        if (!cacheFile.isFile) {
            cacheFile.delete()
        }

        try {
            val jsonOutput = gson.toJson(cache)
            cacheFile.writeText(jsonOutput)
        } catch (e: Exception) {
            Log.e(SAVE_CACHET, "Unable to save cache: $e")
        }
    }

    fun createStoryLayout(context: Context, story: Story, height: Float) : View {
        val row = LinearLayout(context)
        val rowParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, height)
        rowParams.topMargin = 10
        rowParams.bottomMargin = 10

        row.layoutParams = rowParams
        row.weightSum = 30F
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = LinearLayout.TEXT_ALIGNMENT_CENTER
        row.tag = story.id
        row.setOnClickListener {
            val intent = Intent(context, DisplayStoryActivity::class.java).apply {
                putExtra(Constants.EXTRA_STORY_URI, story.url)
                putExtra(Constants.EXTRA_STORY_BOYD, story.body)
            }
            startActivity(intent)
        }

        val image = ImageView(context)
        val imageParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 5F)
        image.layoutParams = imageParams
        image.tag = story.id
        if (!story.imageBlob.isNullOrBlank()) {
            val blob = Base64.decode(story.imageBlob!!, Base64.DEFAULT)
            image.setImageBitmap(BitmapFactory.decodeByteArray(blob, 0, blob.size))
        }

        val text = TextView(context)
        val textParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 25F)
        text.layoutParams = textParams
        text.text = story.title

        row.addView(image)
        row.addView(text)
        return row
    }

    companion object {
        private const val LOADT = "Main.load"
        private const val LOAD_CACHET = "Main.loadCache"
        private const val SAVE_CACHET = "Main.saveCache"
        private const val HANDLE_MESSAGET = "Main.handleMessage"
        private const val CACHE_FILENAME = "cache.json"

        private const val MAX_CACHE_SIZE = 5
        private const val UI_INIT = 1
        private const val UI_UPDATE_IMAGE = 2
        private const val UI_SHOW_ALERT_DIALOG = 1000
    }

    private class UIHandler(mainActivity: MainActivity) : Handler() {
        var activity: WeakReference<MainActivity> = WeakReference(mainActivity);

        override fun handleMessage(msg: Message) {
            try {
                super.handleMessage(msg)
                when (msg.what) {
                    UI_INIT -> {
                        Log.d(HANDLE_MESSAGET, "Remove Main Layout")
                        val mainActivity = activity.get()!!
                        val layout = mainActivity.findViewById<LinearLayout>(R.id.layoutMain)
                        layout.removeAllViewsInLayout()

                        val stories = msg.obj
                        if (stories is Stories) {
                            val height = layout.weightSum.div(stories.stories.size)
                            for (story in stories.stories) {
                                Log.d(HANDLE_MESSAGET, "Add Layout for ${story.id}")
                                layout.addView(mainActivity.createStoryLayout(mainActivity, story, height))
                            }
                        }
                    }
                    UI_UPDATE_IMAGE -> {
                        val layout = activity.get()?.findViewById<LinearLayout>(R.id.layoutMain)!!
                        val image = msg.obj
                        if (image is Bitmap) {
                            for (row in layout.children) {
                                if (row is LinearLayout) {
                                    val imageView = row[0]
                                    if (imageView is ImageView && imageView.tag == msg.arg1) {
                                        Log.d(HANDLE_MESSAGET, "Update image for ${msg.arg1}")
                                        imageView.setImageBitmap(image)
                                    }
                                }
                            }
                        }
                    }
                    UI_SHOW_ALERT_DIALOG -> {
                        val exception = msg.obj
                        if (exception is Throwable) {
                            throw exception
                        }
                    }
                }
            } catch (e: Exception) {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                e.printStackTrace(pw)
                AlertDialog.Builder(activity.get()!!)
                    .setTitle("Exception !!")
                    .setMessage(sw.toString())
                    .show()
            }
        }
    }
}
