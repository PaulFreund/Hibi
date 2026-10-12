package org.lvl3.hibi.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.webkit.*
import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
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
    fun read(callback: (String) -> Unit) {
        val view = webView
        if (view == null) callback(latest)
        else view.evaluateJavascript("window.getMarkdown()") { encoded ->
            callback((runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull() ?: latest).also { latest = it })
        }
    }
}

class EditorBridge(private val changed: (String) -> Unit) {
    @JavascriptInterface fun onChange(markdown: String) { Handler(Looper.getMainLooper()).post { changed(markdown) } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownEditor(id: String, initial: String, controller: EditorController, modifier: Modifier, changed: (String) -> Unit) {
    val context = LocalContext.current
    val fontScale = LocalConfiguration.current.fontScale
    val callback by rememberUpdatedState(changed)
    key(id) {
        AndroidView(modifier = modifier, factory = {
            WebView(it).apply {
                controller.latest = initial; controller.webView = this
                setBackgroundColor(ContextCompat.getColor(context, R.color.hibi_surface))
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.textZoom = (fontScale * 100).toInt()
                settings.blockNetworkLoads = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                addJavascriptInterface(EditorBridge { value -> controller.latest = value; callback(value) }, "Hibi")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                        val uri = request.url
                        val prefix = "/assets/editor/"
                        val file = uri.path?.removePrefix(prefix).orEmpty()
                        if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.path?.startsWith(prefix) == true && file in setOf("editor.html", "hibi-editor.js", "toastui-editor-all.min.js", "toastui-editor.min.css", "de-de.js")) {
                            val mime = when { file.endsWith(".css") -> "text/css"; file.endsWith(".js") -> "application/javascript"; else -> "text/html" }
                            return WebResourceResponse(mime, "UTF-8", context.assets.open("editor/$file"))
                        }
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                    }
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest) = true
                    override fun onPageFinished(view: WebView, url: String) {
                        fun color(resource: Int) = String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(context, resource))
                        val colors = JSONObject().put("paper", color(R.color.hibi_surface)).put("ink", color(R.color.hibi_ink))
                            .put("accent", color(R.color.hibi_accent)).put("line", color(R.color.hibi_outline)).put("muted", color(R.color.hibi_muted))
                            .put("body-font", HibiDesign.bodyFamily).put("heading-font", HibiDesign.headingFamily).put("body-size", "${HibiDesign.bodySize}px")
                        view.evaluateJavascript("window.initialize(${JSONObject.quote(initial)},$colors)", null)
                    }
                }
                loadUrl("https://appassets.androidplatform.net/assets/editor/editor.html")
            }
        }, onReset = null, onRelease = { view -> controller.webView = null; view.removeJavascriptInterface("Hibi"); view.destroy() },
            update = { view -> view.settings.textZoom = (fontScale * 100).toInt() })
    }
}

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, preview: Boolean = false) {
    val context = LocalContext.current
    val markwon = remember { Markwon.builder(context).usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context)).usePlugin(TaskListPlugin.create(context)).build() }
    AndroidView(modifier = modifier, factory = {
        TextView(it).apply {
            textSize = HibiDesign.bodySize.toFloat()
            typeface = android.graphics.Typeface.create(HibiDesign.bodyFamily, android.graphics.Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.hibi_ink))
            setLineSpacing(3f, 1.15f)
            setTextIsSelectable(!preview)
        }
    }, update = { view ->
        markwon.setMarkdown(view, markdown)
        view.maxLines = if (preview) 3 else Int.MAX_VALUE
        view.ellipsize = if (preview) TextUtils.TruncateAt.END else null
        // Diary links do not fetch anything in the preview.
        view.movementMethod = null
    })
}
