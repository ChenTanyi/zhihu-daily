package com.tangenty.daily

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Exception
import java.lang.ref.WeakReference
import java.net.URL

class DisplayStoryActivity : AppCompatActivity() {
    private val handler = UIHandler(this)
    private val webViewClient = WebViewClient()
    private var storyUri: String? = null
    private var storyBody: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display_story)

        storyUri = intent.getStringExtra(Constants.EXTRA_STORY_URI)
        storyBody = intent.getStringExtra(Constants.EXTRA_STORY_BOYD)

        reload(null)
    }

    fun reload(view: View?) {
        val webView = findViewById<WebView>(R.id.displayStory)
        webView.webViewClient = webViewClient
        if (!storyBody.isNullOrBlank()) {
            webView.loadData(storyBody, "text/html; charset=utf-8", "utf-8")
        } else if (!storyUri.isNullOrBlank()) {
            Thread {
                try {
                    val conn = URL(storyUri).openConnection()
                    conn.setRequestProperty("User-Agent", Constants.USER_AGENT)
                    conn.getInputStream().use {
                        val content = it.readBytes().toString(Charsets.UTF_8)
                        Log.d(RELOADT, "Receive story: $content")
                        val msg = handler.obtainMessage(MESSAGE_RELOAD, content)
                        handler.sendMessage(msg)
                    }
                } catch (e: Exception) {
                    val msg = handler.obtainMessage(UI_SHOW_ALERT_DIALOG, e)
                    handler.sendMessage(msg)
                }
            }.start()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage("Can't reload with empty story")
                .show()
        }
    }

    companion object {
        private const val RELOADT = "DisplayStory.reload"

        private const val MESSAGE_RELOAD = 1
        private const val UI_SHOW_ALERT_DIALOG = 1000
    }

    private class UIHandler(displayStoryActivity: DisplayStoryActivity) : Handler() {
        var activity: WeakReference<DisplayStoryActivity> = WeakReference(displayStoryActivity);

        override fun handleMessage(msg: Message) {
            try {
                super.handleMessage(msg)
                when (msg.what) {
                    MESSAGE_RELOAD -> {
                        val story = msg.obj
                        if (story is String) {
                            val displayStoryActivity = activity.get()!!
                            displayStoryActivity.storyBody = story
                            displayStoryActivity.reload(null)
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
