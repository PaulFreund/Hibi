package org.lvl3.hibi.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.method.ArrowKeyMovementMethod
import android.webkit.*
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import org.json.JSONObject
import org.json.JSONTokener
import org.lvl3.hibi.R
import org.lvl3.hibi.ui.theme.HibiDesign
import java.io.ByteArrayInputStream

class EditorController {
    var webView: WebView? = null
    var latest = ""
    var ready by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    var generation by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())
    fun read(callback: (String) -> Unit) {
        val view = webView
        if (view == null || !ready) { callback(latest); return }
        var completed = false
        val timeout = Runnable { if (!completed) { completed = true; callback(latest) } }
        handler.postDelayed(timeout, 1500)
        try {
            view.evaluateJavascript("window.getMarkdown()") { encoded ->
                if (!completed) {
                    completed = true; handler.removeCallbacks(timeout)
                    val value = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
                    if (webView === view && value != null) latest = value
                    callback(latest)
                }
            }
        } catch (_: Exception) {
            if (!completed) { completed = true; handler.removeCallbacks(timeout); callback(latest) }
        }
    }
    fun retry() { ready = false; failure = null; generation++ }
}

class EditorBridge(private val changed: (String) -> Unit, private val ready: () -> Unit, private val failed: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun onChange(markdown: String) { main.post { changed(markdown) } }
    @JavascriptInterface fun onReady() { main.post { ready() } }
    @JavascriptInterface fun onError(message: String) { main.post { failed(message) } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownEditor(id: String, initial: String, controller: EditorController, modifier: Modifier, changed: (String) -> Unit) {
    val context = LocalContext.current
    val fontScale = LocalConfiguration.current.fontScale
    val callback by rememberUpdatedState(changed)
    val failure = controller.failure
    if (failure != null) {
        Column(modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(failure); Text("Deine bisherigen Worte bleiben im Entwurf erhalten.")
            OutlinedButton(onClick = controller::retry) { Text("Schreibbereich erneut laden") }
        }
        return
    }
    key(id, controller.generation) {
        AndroidView(modifier = modifier, factory = {
            WebView(it).apply {
                val owner = this
                // On a renderer retry use the last bridge value, not a stale constructor value.
                if (controller.generation == 0) controller.latest = initial
                controller.webView = this; controller.ready = false
                setBackgroundColor(ContextCompat.getColor(context, R.color.hibi_surface))
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.textZoom = (fontScale * 100).toInt()
                settings.blockNetworkLoads = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                fun fail(message: String) { if (controller.webView === owner) { controller.ready = false; controller.failure = message } }
                addJavascriptInterface(EditorBridge(
                    changed = { value -> if (controller.webView === owner && controller.ready) { controller.latest = value; callback(value) } },
                    ready = { if (controller.webView === owner) controller.ready = true }, failed = ::fail), "Hibi")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                        val uri = request.url; val prefix = "/assets/editor/"
                        val file = uri.path?.removePrefix(prefix).orEmpty()
                        if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.path?.startsWith(prefix) == true && file in setOf("editor.html", "hibi-editor.js", "toastui-editor-all.min.js", "toastui-editor.min.css", "de-de.js")) {
                            val mime = when { file.endsWith(".css") -> "text/css"; file.endsWith(".js") -> "application/javascript"; else -> "text/html" }
                            return WebResourceResponse(mime, "UTF-8", context.assets.open("editor/$file"))
                        }
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                    }
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest) = true
                    override fun onPageFinished(view: WebView, url: String) {
                        if (controller.webView !== view) return
                        fun color(resource: Int) = String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(context, resource))
                        val colors = JSONObject().put("paper", color(R.color.hibi_surface)).put("ink", color(R.color.hibi_ink))
                            .put("accent", color(R.color.hibi_accent)).put("line", color(R.color.hibi_outline)).put("muted", color(R.color.hibi_muted))
                            .put("body-font", HibiDesign.bodyFamily).put("heading-font", HibiDesign.headingFamily).put("body-size", "${HibiDesign.bodySize}px")
                        view.evaluateJavascript("window.initialize(${JSONObject.quote(controller.latest)},$colors)", null)
                    }
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) fail("Der Schreibbereich konnte nicht geladen werden.")
                    }
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        fail("Der Schreibbereich wurde beendet. Bitte lade ihn erneut.")
                        return true // Compose removes the failed AndroidView and destroys it below.
                    }
                }
                loadUrl("https://appassets.androidplatform.net/assets/editor/editor.html")
            }
        }, onReset = null, onRelease = { view ->
            if (controller.webView === view) { controller.webView = null; controller.ready = false }
            view.removeJavascriptInterface("Hibi"); view.destroy()
        }, update = { view -> view.settings.textZoom = (fontScale * 100).toInt() })
    }
}

private object MarkdownRenderer {
    private var instance: Markwon? = null
    @Synchronized fun get(context: android.content.Context): Markwon = instance ?: Markwon.builder(context.applicationContext)
        .usePlugin(StrikethroughPlugin.create()).usePlugin(TablePlugin.create(context.applicationContext))
        .usePlugin(TaskListPlugin.create(context.applicationContext)).build().also { instance = it }
}

private val blockPrefix = Regex("(?m)^\\s*(?:#{1,6}\\s+|>\\s*|[-*+]\\s+|[0-9]+[.)]\\s+)")
private val whitespace = Regex("\\s+")
fun previewMarkdown(markdown: String): String = markdown.take(600).replace(blockPrefix, "").replace(whitespace, " ").trim() + if (markdown.length > 600) "…" else ""

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, preview: Boolean = false) {
    val context = LocalContext.current
    val markwon = remember { MarkdownRenderer.get(context) }
    val source = remember(markdown, preview) { if (preview) previewMarkdown(markdown) else markdown }
    AndroidView(modifier = modifier, factory = {
        TextView(it).apply {
            textSize = HibiDesign.bodySize.toFloat()
            typeface = android.graphics.Typeface.create(HibiDesign.bodyFamily, android.graphics.Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.hibi_ink)); setLineSpacing(3f, 1.15f)
        }
    }, update = { view ->
        if (view.tag != source) { markwon.setMarkdown(view, source); view.tag = source }
        view.maxLines = if (preview) 3 else Int.MAX_VALUE
        view.ellipsize = if (preview) TextUtils.TruncateAt.END else null
        view.setTextIsSelectable(!preview)
        view.movementMethod = if (preview) null else ArrowKeyMovementMethod.getInstance()
    })
}
