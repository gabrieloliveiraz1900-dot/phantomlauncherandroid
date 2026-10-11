package com.phantom.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var web: WebView
    private lateinit var tts: TextToSpeech
    private var sr: SpeechRecognizer? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        tts = TextToSpeech(this, this)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onInit(status: Int) {
        tts.language = Locale("pt", "PT")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { js("onSpeak(true)") }
            override fun onDone(id: String?) { js("onSpeak(false)") }
            @Deprecated("x") override fun onError(id: String?) { js("onSpeak(false)") }
        })
    }

    @Deprecated("launcher não fecha")
    override fun onBackPressed() {}

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    inner class Bridge {
        @JavascriptInterface
        fun getApps(): String {
            val pm = packageManager
            val arr = JSONArray()
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .forEach { arr.put(JSONObject().put("n", it.loadLabel(pm).toString()).put("p", it.activityInfo.packageName)) }
            return arr.toString()
        }

        @JavascriptInterface
        fun getIcon(pkg: String): String = try {
            val d = packageManager.getApplicationIcon(pkg)
            val bm = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 120, 120); d.draw(Canvas(bm))
            val o = ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.PNG, 100, o)
            "data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) { "" }

        @JavascriptInterface
        fun openPkg(pkg: String): Boolean {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return true
        }

        @JavascriptInterface
        fun openUrl(url: String, pkg: String): Boolean = try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg.isNotEmpty()) i.setPackage(pkg)
            startActivity(i); true
        } catch (e: Exception) { if (pkg.isNotEmpty()) openUrl(url, "") else false }

        @JavascriptInterface
        fun torch(on: Boolean) {
            try { val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager; cm.setTorchMode(cm.cameraIdList[0], on) } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun battery(): Int = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        @JavascriptInterface
        fun voices(): String {
            val arr = JSONArray()
            try { tts.voices.filter { it.locale.language == "pt" }.sortedBy { it.name }.forEach { arr.put(it.name) } } catch (e: Exception) {}
            return arr.toString()
        }

        @JavascriptInterface
        fun speak(t: String, voice: String, rate: Float, pitch: Float) {
            try { tts.voices.firstOrNull { it.name == voice }?.let { tts.voice = it } } catch (e: Exception) {}
            tts.setSpeechRate(rate); tts.setPitch(pitch)
            tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "j")
        }

        @JavascriptInterface
        fun stopSpeak() { tts.stop(); js("onSpeak(false)") }

        @JavascriptInterface
        fun stopListen() { runOnUiThread { sr?.cancel() } }

        @JavascriptInterface
        fun listen() = runOnUiThread {
            sr?.destroy()
            sr = SpeechRecognizer.createSpeechRecognizer(this@MainActivity)
            sr?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) {
                    val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    js("onVoice(${JSONObject.quote(t)})")
                }
                override fun onError(e: Int) { js("onVoice('')") }
                override fun onRmsChanged(v: Float) { js("onRms($v)") }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            sr?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-PT")
            })
        }
    }
}
