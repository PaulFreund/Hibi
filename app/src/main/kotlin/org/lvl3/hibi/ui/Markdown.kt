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
import org.lvl3.hibi.BuildConfig
import org.lvl3.hibi.ui.theme.HibiDesign
import org.lvl3.hibi.model.RendererHandoff
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentLinkedQueue

data class EditorRead(val markdown: String, val confirmed: Boolean)

class EditorController(private val evaluator: ((String, (String) -> Unit) -> Unit)? = null) {
    private class Renderer(val checkpoint: (String, String) -> Unit) {
        var retired = false
        var released = false
    }
    private val renderers = mutableMapOf<WebView, Renderer>()
    private val handoff: RendererHandoff<WebView> = RendererHandoff { outgoing, done ->
        val base = latest
        outgoing.clearFocus()
        readFrom(outgoing, allowSwitch = true) { result ->
            renderers[outgoing]?.let { record ->
                record.checkpoint(base, result.markdown)
                record.retired = true
            }
            ready = false
            cancelStartup()
            outgoing.removeJavascriptInterface("Hibi")
            done() // Only now may the next renderer load and initialize with latest.
            disposeReleased(outgoing)
            if (handoffViewAbsent()) switching = false
        }
    }
    val webView: WebView? get() = handoff.current
    var latest = ""
    var ready by mutableStateOf(false)
    var switching by mutableStateOf(false)
        private set
    val usable get() = ready && !switching && failure == null
    var failure by mutableStateOf<String?>(null)
    var readIssue by mutableStateOf<String?>(null)
    var consoleError: String? = null
    var loadPhase = "not-created"
    var pageUrl: String? = null
    var initializeAnswer: String? = null
    val resources = ConcurrentLinkedQueue<String>()
    var generation by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())
    private var startup: Runnable? = null
    private fun handoffViewAbsent(): Boolean = webView == null && !handoff.waiting
    fun attach(view: WebView, checkpoint: (String, String) -> Unit, load: () -> Unit) {
        renderers[view] = Renderer(checkpoint)
        switching = true
        handoff.attach(view) {
            switching = false; ready = false; failure = null; consoleError = null
            loadPhase = "loading-assets"; initializeAnswer = null; resources.clear()
            startup = Runnable { if (webView === view && !ready) fail(view, "Der Schreibbereich antwortet nicht. Bitte lade ihn erneut.") }
            handler.postDelayed(startup!!, 30000)
            load()
        }
    }
    fun release(view: WebView) {
        val record = renderers[view] ?: return
        record.released = true
        switching = true
        handoff.release(view)
        if (webView !== view && !record.retired) record.retired = true
        disposeReleased(view)
        switching = handoff.waiting
    }
    private fun disposeReleased(view: WebView) {
        val record = renderers[view] ?: return
        if (record.released && record.retired) {
            renderers.remove(view); view.removeJavascriptInterface("Hibi"); view.destroy()
        }
    }
    fun rendererReady(view: WebView) {
        if (webView === view && failure == null) { ready = true; loadPhase = "ready"; cancelStartup() }
    }
    fun fail(view: WebView, message: String) {
        if (webView === view) { ready = false; failure = message; loadPhase = "failed"; cancelStartup() }
    }
    private fun cancelStartup() { startup?.let(handler::removeCallbacks); startup = null }
    fun diagnostics() = "ready=$ready, switching=$switching, phase=$loadPhase, url=$pageUrl, initialize=$initializeAnswer, failure=$failure, console=$consoleError, resources=${resources.toList()}, renderer=${webView?.let(System::identityHashCode)}, latestLength=${latest.length}"
    fun read(callback: (EditorRead) -> Unit) {
        if (!usable) { callback(EditorRead(latest, false)); return }
        readFrom(webView, allowSwitch = false, callback)
    }
    private fun readFrom(view: WebView?, allowSwitch: Boolean, callback: (EditorRead) -> Unit) {
        if (!ready || (view == null && evaluator == null)) { callback(EditorRead(latest, false)); return }
        var completed = false
        fun finish(value: String?) {
            if (completed) return
            completed = true
            val confirmed = value != null && webView === view && ready && (allowSwitch || !switching)
            if (confirmed) { latest = value!!; readIssue = null }
            else if (webView === view && !allowSwitch) readIssue = "Der aktuelle Text konnte nicht bestätigt werden. Bitte versuche es erneut."
            callback(EditorRead(latest, confirmed))
        }
        val timeout = Runnable { finish(null) }
        handler.postDelayed(timeout, 1500)
        try {
            val answer: (String) -> Unit = { encoded ->
                handler.removeCallbacks(timeout)
                finish(runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull())
            }
            if (evaluator != null) evaluator.invoke("window.getMarkdown()", answer)
            else view!!.evaluateJavascript("window.getMarkdown()") { answer(it) }
        } catch (_: Exception) {
            handler.removeCallbacks(timeout); finish(null)
        }
    }
    fun retry() { cancelStartup(); ready = false; failure = null; readIssue = null; generation++ }
}

class EditorBridge(private val changed: (String) -> Unit, private val ready: () -> Unit, private val failed: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun onChange(markdown: String) { main.post { changed(markdown) } }
    @JavascriptInterface fun onReady() { main.post { ready() } }
    @JavascriptInterface fun onError(message: String) { main.post { failed(message) } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownEditor(id: String, initial: String, controller: EditorController, modifier: Modifier, checkpoint: (String, String) -> Unit = { _, _ -> }, changed: (String) -> Unit) {
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
                tag = controller
                if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                setBackgroundColor(ContextCompat.getColor(context, R.color.hibi_surface))
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.textZoom = (fontScale * 100).toInt()
                settings.blockNetworkLoads = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) controller.consoleError = message.message()
                        return true
                    }
                }
                fun fail(message: String) = controller.fail(owner, message)
                addJavascriptInterface(EditorBridge(
                    changed = { value -> if (controller.webView === owner && controller.ready) { controller.latest = value; callback(value) } },
                    ready = { controller.rendererReady(owner) }, failed = ::fail), "Hibi")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                        val uri = request.url; val prefix = "/assets/editor/"
                        val file = uri.path?.removePrefix(prefix).orEmpty()
                        if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.path?.startsWith(prefix) == true && file in setOf("editor.html", "hibi-editor.js", "toastui-editor-all.min.js", "toastui-editor.min.css", "de-de.js")) {
                            controller.resources.add(file)
                            val mime = when { file.endsWith(".css") -> "text/css"; file.endsWith(".js") -> "application/javascript"; else -> "text/html" }
                            return WebResourceResponse(mime, "UTF-8", context.assets.open("editor/$file"))
                        }
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                    }
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest) = true
                    override fun onPageFinished(view: WebView, url: String) {
                        if (controller.webView !== view || controller.failure != null) return
                        controller.pageUrl = url; controller.loadPhase = "initialize-called"
                        fun color(resource: Int) = String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(context, resource))
                        val colors = JSONObject().put("paper", color(R.color.hibi_surface)).put("ink", color(R.color.hibi_ink))
                            .put("accent", color(R.color.hibi_accent)).put("line", color(R.color.hibi_outline)).put("muted", color(R.color.hibi_muted))
                            .put("body-font", HibiDesign.bodyFamily).put("heading-font", HibiDesign.headingFamily).put("body-size", "${HibiDesign.bodySize}px")
                        view.evaluateJavascript("window.initialize(${JSONObject.quote(controller.latest)},$colors)") { answer ->
                            if (controller.webView === view) {
                                controller.initializeAnswer = answer
                                if (answer != "true") fail("Der Schreibbereich konnte nicht gestartet werden. Bitte lade ihn erneut.")
                            }
                        }
                    }
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        controller.resources.add("error:${request?.url?.lastPathSegment}:${error?.errorCode}")
                        if (request?.isForMainFrame == true) fail("Der Schreibbereich konnte nicht geladen werden.")
                    }
                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                        controller.resources.add("http:${request?.url?.lastPathSegment}:${response?.statusCode}")
                    }
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        fail("Der Schreibbereich wurde beendet. Bitte lade ihn erneut.")
                        return true // Compose removes the failed AndroidView and destroys it below.
                    }
                }
                controller.attach(this, checkpoint) { loadUrl("https://appassets.androidplatform.net/assets/editor/editor.html") }
            }
        }, onReset = null, onRelease = { view ->
            controller.release(view)
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
