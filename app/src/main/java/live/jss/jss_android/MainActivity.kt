package live.jss.jss_android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splash: TextView

    private val readyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == JssService.ACTION_READY) {
                showWebView()
            }
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — service still runs without notification permission, just less visibly */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        startService(Intent(this, JssService::class.java))
        ContextCompat.registerReceiver(
            this,
            readyReceiver,
            IntentFilter(JssService.ACTION_READY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (this@MainActivity::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(readyReceiver) } catch (_: Exception) {}
    }

    @Suppress("SetJavaScriptEnabled")
    private fun buildLayout(): View {
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        webView = WebView(this).apply {
            visibility = View.GONE
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                allowFileAccess = false
                allowContentAccess = false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val host = request.url.host
                    // Keep loopback navigation in-app; everything else goes
                    // to the system browser (avoids OAuth flow capture).
                    if (host == "127.0.0.1" || host == "localhost") return false
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    return true
                }
            }
        }
        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        splash = TextView(this).apply {
            text = getString(R.string.splash_text)
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }
        root.addView(splash, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        return root
    }

    private fun showWebView() {
        webView.loadUrl("http://127.0.0.1:${JssService.PORT}/")
        webView.visibility = View.VISIBLE
        splash.visibility = View.GONE
    }
}
