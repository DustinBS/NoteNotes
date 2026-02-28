package com.notenotes.ui.components

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Jetpack Compose wrapper for a WebView that renders sheet music
 * using OpenSheetMusicDisplay (OSMD).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SheetMusicWebView(
    musicXml: String?,
    modifier: Modifier = Modifier,
    playbackProgress: Float = 0f,
    currentNoteIndex: Int = -1,
    isPlaying: Boolean = false,
    barsPerRow: Int = -1,
    scale: Float = 0.9f,
    onWebViewReady: ((WebView) -> Unit)? = null,
    onReady: () -> Unit = {},
    onRenderComplete: () -> Unit = {},
    onError: (String) -> Unit = {},
    onPrintReady: (() -> Unit)? = null
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isOsmdReady by remember { mutableStateOf(false) }
    var pendingXml by remember { mutableStateOf<String?>(null) }
    var cursorShown by remember { mutableStateOf(false) }

    // Clean up WebView when leaving composition (M7)
    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                wv.stopLoading()
                wv.removeJavascriptInterface("Android")
                wv.destroy()
            }
            webView = null
        }
    }

    // When musicXml changes, try to render
    LaunchedEffect(musicXml, isOsmdReady) {
        if (musicXml != null && isOsmdReady && webView != null) {
            loadMusicXml(webView!!, musicXml)
        } else if (musicXml != null && !isOsmdReady) {
            pendingXml = musicXml
        }
    }

    // Show/hide cursor and update position during playback
    LaunchedEffect(isPlaying) {
        val wv = webView ?: return@LaunchedEffect
        if (isPlaying) {
            wv.evaluateJavascript("showCursor()", null)
            cursorShown = true
        } else if (cursorShown && !isPlaying) {
            wv.evaluateJavascript("hideCursor()", null)
            cursorShown = false
        }
    }

    // Update bars per row when setting changes
    LaunchedEffect(barsPerRow, isOsmdReady) {
        if (isOsmdReady) {
            webView?.evaluateJavascript("setBarsPerRow($barsPerRow)", null)
        }
    }

    // Update scale when setting changes
    LaunchedEffect(scale, isOsmdReady) {
        if (isOsmdReady) {
            webView?.evaluateJavascript("setScale($scale)", null)
        }
    }

    // Update cursor position based on note index — throttled to ~10 Hz
    var lastCursorUpdate by remember { mutableStateOf(0L) }
    LaunchedEffect(currentNoteIndex, isPlaying) {
        val wv = webView ?: return@LaunchedEffect
        if ((isPlaying || cursorShown) && currentNoteIndex >= 0) {
            val now = System.currentTimeMillis()
            if (now - lastCursorUpdate >= 100) {  // max 10 updates per second
                lastCursorUpdate = now
                wv.evaluateJavascript("setCursorToNoteIndex($currentNoteIndex)", null)
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onOsmdReady() {
                        // @JavascriptInterface runs on WebView thread; post to main thread
                        post {
                            isOsmdReady = true
                            onReady()
                            // Render pending XML if any
                            pendingXml?.let { xml ->
                                loadMusicXml(this@apply, xml)
                                pendingXml = null
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onRenderComplete() {
                        post { onRenderComplete() }
                    }

                    @JavascriptInterface
                    fun onScoreLoaded(trackCount: Int) {
                        Log.d("SheetMusic", "Score loaded with $trackCount track(s)")
                    }

                    @JavascriptInterface
                    fun onError(message: String) {
                        post { onError(message) }
                    }

                    @JavascriptInterface
                    fun onPrintReady() {
                        post { onPrintReady?.invoke() }
                    }
                }, "Android")

                webViewClient = WebViewClient()
                loadUrl("file:///android_asset/alphatab/preview.html")
                webView = this
                onWebViewReady?.invoke(this)
            }
        },
        update = { view ->
            webView = view
        }
    )
}

/**
 * Load MusicXML into the WebView via JavaScript.
 */
private fun loadMusicXml(webView: WebView, musicXml: String) {
    // Escape the XML for JavaScript string literal
    val escaped = musicXml
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    webView.evaluateJavascript("loadMusicXml(\"$escaped\")", null)
}
