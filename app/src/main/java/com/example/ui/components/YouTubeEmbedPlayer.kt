package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.media.YouTubeSupport
import com.example.model.AspectRatioOption
import com.example.model.ViralClip
import com.example.model.formatTimePreciseMs
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonLime
import com.example.ui.theme.ScoreCoral

private class YouTubeJsBridge(
    private val mainHandler: Handler,
    private val onRelativeTimeMs: (Long) -> Unit,
    private val onEmbedError: (Int) -> Unit
) {
    @JavascriptInterface
    fun onTimeUpdate(relativeMs: Double) {
        val safe = relativeMs.toLong().coerceAtLeast(0L)
        mainHandler.post { onRelativeTimeMs(safe) }
    }

    @JavascriptInterface
    fun onPlayerError(code: Int) {
        mainHandler.post { onEmbedError(code) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeEmbedPlayerSurface(
    videoId: String,
    thumbnailUrl: String?,
    clip: ViralClip,
    isPlaying: Boolean,
    relativePositionMs: Long,
    onRelativePositionUpdate: (Long) -> Unit,
    onAttachLocalVideoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var embedErrorCode by remember(videoId) { mutableStateOf<Int?>(null) }

    if (embedErrorCode != null) {
        YouTubeEmbedFallbackCard(
            videoId = videoId,
            thumbnailUrl = thumbnailUrl ?: YouTubeSupport.defaultThumbnailUrl(videoId),
            startMs = clip.startMs,
            onOpenYouTube = {
                val url = YouTubeSupport.buildTimestampedShareUrl(videoId, clip.startMs)
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
            onAttachLocalVideoClick = onAttachLocalVideoClick,
            modifier = modifier
        )
        return
    }

    val startSecInitial = clip.startMs / 1000.0
    val endSecInitial = clip.endMs / 1000.0

    val webView = remember(videoId) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.parseColor("#0B0D12"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()

            val bridge = YouTubeJsBridge(
                mainHandler = mainHandler,
                onRelativeTimeMs = onRelativePositionUpdate,
                onEmbedError = { code ->
                    if (code == 100 || code == 101 || code == 150) {
                        embedErrorCode = code
                    }
                }
            )
            addJavascriptInterface(bridge, "ClipPulseBridge")
            loadDataWithBaseURL(
                "https://www.youtube.com",
                buildYouTubePlayerHtml(videoId, startSecInitial, endSecInitial),
                "text/html",
                "utf-8",
                null
            )
        }
    }

    LaunchedEffect(webView, clip.startMs, clip.endMs, isPlaying) {
        val startSec = clip.startMs / 1000.0
        val endSec = clip.endMs / 1000.0
        webView.evaluateJavascript(
            "if(window.clipPulseSync){window.clipPulseSync($startSec,$endSec,$isPlaying);}",
            null
        )
    }

    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webView.evaluateJavascript(
                        "if(window.clipPulsePause){window.clipPulsePause();}",
                        null
                    )
                    webView.onPause()
                }
                Lifecycle.Event.ON_RESUME -> webView.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView.stopLoading()
            webView.removeJavascriptInterface("ClipPulseBridge")
            webView.destroy()
        }
    }

    // Cover-scale 16:9 YouTube frame smoothly inside vertical 9:16 or 1:1 viewports + apply user pan/zoom
    val coverBoost = when (clip.aspectRatio) {
        AspectRatioOption.VERTICAL_9_16 -> 1.78f
        AspectRatioOption.SQUARE_1_1 -> 1.35f
        AspectRatioOption.ORIGINAL_16_9 -> 1.0f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CharcoalBg)
            .testTag("youtube_embed_player")
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = coverBoost * clip.zoomScale.coerceIn(1.0f, 1.5f)
                    scaleX = scale
                    scaleY = scale
                    translationX = -clip.effectivePanX * size.width * 0.28f * clip.zoomScale
                }
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(CharcoalBg.copy(alpha = 0.78f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isPlaying) ScoreCoral else Color(0xFF94A3B8))
            )
            Text(
                text = "YOUTUBE SYNC • ${formatTimePreciseMs(relativePositionMs)} / ${formatTimePreciseMs(clip.clipDurationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }
    }
}

@Composable
private fun YouTubeEmbedFallbackCard(
    videoId: String,
    thumbnailUrl: String,
    startMs: Long,
    onOpenYouTube: () -> Unit,
    onAttachLocalVideoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CharcoalBg)
            .testTag("youtube_embed_fallback")
    ) {
        AsyncImage(
            model = thumbnailUrl,
            contentDescription = "YouTube thumbnail for $videoId",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            alpha = 0.35f
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Channel Disabled Embeds",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                text = "This YouTube creator restricted embedded playback. Watch at the exact clip timestamp on YouTube or attach your local video file for native playback & MP4 export.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCBD5E1),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onOpenYouTube,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Watch at ${formatTimePreciseMs(startMs)} on YouTube", color = Color.White)
            }
            OutlinedButton(
                onClick = onAttachLocalVideoClick,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.65f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.VideoLibrary,
                    contentDescription = null,
                    tint = NeonLime,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Attach Local Video", color = NeonLime)
            }
        }
    }
}

private fun buildYouTubePlayerHtml(videoId: String, startSec: Double, endSec: Double): String {
    val safeId = videoId.replace(Regex("[^A-Za-z0-9_-]"), "")
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <style>
            html, body {
              margin: 0; padding: 0; width: 100%; height: 100%;
              background: #0B0D12; overflow: hidden;
            }
            #player {
              position: fixed; inset: 0; width: 100%; height: 100%;
              pointer-events: none;
            }
          </style>
        </head>
        <body>
          <div id="player"></div>
          <script src="https://www.youtube.com/iframe_api"></script>
          <script>
            var player = null;
            var clipStart = $startSec;
            var clipEnd = $endSec;
            var shouldPlay = true;
            var pollTimer = null;

            function onYouTubeIframeAPIReady() {
              player = new YT.Player('player', {
                videoId: '$safeId',
                playerVars: {
                  autoplay: 1,
                  controls: 0,
                  disablekb: 1,
                  fs: 0,
                  iv_load_policy: 3,
                  modestbranding: 1,
                  playsinline: 1,
                  rel: 0,
                  start: Math.floor(clipStart)
                },
                events: {
                  onReady: function(e) {
                    e.target.seekTo(clipStart, true);
                    if (shouldPlay) e.target.playVideo();
                    startPolling();
                  },
                  onStateChange: function(e) {
                    if (e.data === YT.PlayerState.ENDED && shouldPlay) {
                      player.seekTo(clipStart, true);
                      player.playVideo();
                    }
                  },
                  onError: function(e) {
                    if (window.ClipPulseBridge) {
                      window.ClipPulseBridge.onPlayerError(e.data);
                    }
                  }
                }
              });
            }

            function startPolling() {
              if (pollTimer) clearInterval(pollTimer);
              pollTimer = setInterval(function() {
                if (!player || typeof player.getCurrentTime !== 'function') return;
                var cur = player.getCurrentTime();
                if (shouldPlay && cur >= clipEnd) {
                  player.seekTo(clipStart, true);
                  cur = clipStart;
                } else if (cur < clipStart - 0.5) {
                  player.seekTo(clipStart, true);
                  cur = clipStart;
                }
                var relMs = Math.max(0, (cur - clipStart) * 1000.0);
                if (window.ClipPulseBridge) {
                  window.ClipPulseBridge.onTimeUpdate(relMs);
                }
              }, 100);
            }

            window.clipPulseSync = function(newStart, newEnd, playFlag) {
              var startChanged = Math.abs(newStart - clipStart) > 0.25;
              clipStart = newStart;
              clipEnd = newEnd;
              shouldPlay = playFlag;
              if (!player || typeof player.getCurrentTime !== 'function') return;
              var cur = player.getCurrentTime();
              if (startChanged || cur < clipStart || cur >= clipEnd) {
                player.seekTo(clipStart, true);
              }
              if (shouldPlay) {
                player.playVideo();
              } else {
                player.pauseVideo();
              }
            };

            window.clipPulsePause = function() {
              shouldPlay = false;
              if (player && typeof player.pauseVideo === 'function') {
                player.pauseVideo();
              }
            };
          </script>
        </body>
        </html>
    """.trimIndent()
}
