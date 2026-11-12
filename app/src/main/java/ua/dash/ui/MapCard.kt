package ua.dash.ui

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay

/** Перехват maplibre, чтобы после загрузки навести карту на Киев (для map.ukrainealarm.com) */
private const val HOOK_JS = """
(function(){
  if (window.__dashHook) return; window.__dashHook = true;
  var _m;
  try {
    Object.defineProperty(window, 'maplibregl', {
      configurable: true,
      get: function(){ return _m; },
      set: function(v){
        _m = v;
        try {
          var O = v.Map;
          var W = function(opts){ var m = new O(opts); window.__dashMap = m; return m; };
          W.prototype = O.prototype; Object.setPrototypeOf(W, O);
          v.Map = W;
        } catch(e) {}
      }
    });
  } catch(e) {}
})();
"""

private fun afterLoadJs(lat: Double, lon: Double, zoom: Double) = """
(function(){
  var css = '.header,.header-wrapper,.mainMapControls,.general-button,.maplibregl-ctrl-bottom-left,.maplibregl-ctrl-bottom-right,.maplibregl-ctrl-top-right{display:none!important}' +
            '.content{top:0!important;height:100vh!important;margin-top:0!important;padding-top:0!important}' +
            'body{overflow:hidden!important}';
  var st = document.getElementById('dash-css');
  if (!st) { st = document.createElement('style'); st.id = 'dash-css'; st.textContent = css; document.head.appendChild(st); }
  var tries = 0;
  (function go(){
    tries++;
    var m = window.__dashMap;
    if (m && m.jumpTo) { try { m.resize(); m.jumpTo({center:[$lon,$lat], zoom:$zoom}); } catch(e) {} return; }
    if (tries < 40) setTimeout(go, 500);
  })();
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapCard(url: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val isAlarmMap = url.contains("ukrainealarm")
    val webView = remember(url) {
        WebView(ctx).apply {
            setBackgroundColor(AColor.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            if (isAlarmMap && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, HOOK_JS, setOf("*"))
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u: String?) {
                    if (isAlarmMap) view.evaluateJavascript(afterLoadJs(50.45, 30.52, 6.6), null)
                }
            }
            loadUrl(url)
        }
    }
    // Раз в 30 минут перезагружаем, чтобы карта не «залипала»
    LaunchedEffect(webView) {
        while (true) {
            delay(30 * 60_000L)
            webView.reload()
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(d(18)))
            .background(p.card)
    ) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        Text(
            "⟳",
            color = Color.White, fontSize = t(18),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(d(8))
                .clip(RoundedCornerShape(50))
                .background(Color(0x99000000))
                .clickable { webView.reload() }
                .padding(horizontal = d(9), vertical = d(2))
        )
    }
}
