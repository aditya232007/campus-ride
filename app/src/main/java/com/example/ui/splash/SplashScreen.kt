package com.example.ui.splash

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

@Composable
fun SplashScreen(
    onNavigateNext: () -> Unit
) {
    // Animation properties for ~2 second cinematic sequence
    val textAlpha = remember { Animatable(0f) }
    val textScale = remember { Animatable(0.92f) }
    val letterSpacingAnim = remember { Animatable(3f) }
    val flareProgress = remember { Animatable(0f) }
    val subtitleAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Play synchronized original cinematic sound effect in background thread
        launch(Dispatchers.IO) {
            playCinematicSoundEffect()
        }

        // 0ms - 1000ms: Smooth light flare expansion and text reveal
        launch {
            flareProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 1100,
                    easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
                )
            )
        }

        launch {
            textAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 900,
                    easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
                )
            )
        }

        launch {
            textScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 1400,
                    easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)
                )
            )
        }

        launch {
            letterSpacingAnim.animateTo(
                targetValue = 9f,
                animationSpec = tween(
                    durationMillis = 1800,
                    easing = CubicBezierEasing(0.1f, 0.8f, 0.2f, 1f)
                )
            )
        }

        // 500ms - 1500ms: Subtitle fade-in
        delay(450)
        subtitleAlpha.animateTo(
            targetValue = 0.85f,
            animationSpec = tween(
                durationMillis = 750,
                easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
            )
        )

        // Hold until 2.0s mark, then navigate
        delay(800)
        onNavigateNext()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF060913),
                        Color(0xFF020408)
                    ),
                    center = Offset.Unspecified,
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Background Cinematic Flare Lines & Radial Glow Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val center = Offset(canvasWidth / 2f, canvasHeight / 2f)

            val glowRadius = (320.dp.toPx() * flareProgress.value).coerceAtLeast(1f)
            // Dynamic Ambient Center Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x3338BDF8),
                        Color(0x1A0284C7),
                        Color.Transparent
                    ),
                    center = center,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = center
            )

            // Futuristic Horizontal Anamorphic Beam Sweep
            val beamWidth = canvasWidth * 0.85f * flareProgress.value
            val beamHeight = 2.dp.toPx()
            val left = center.x - (beamWidth / 2f)

            if (beamWidth > 0) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x8038BDF8),
                            Color(0xFFF0F9FF),
                            Color(0x8038BDF8),
                            Color.Transparent
                        ),
                        startX = left,
                        endX = left + beamWidth
                    ),
                    topLeft = Offset(left, center.y - (beamHeight / 2f)),
                    size = Size(beamWidth, beamHeight)
                )

                // Secondary soft horizontal diffusion line
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x220284C7),
                            Color(0x5538BDF8),
                            Color(0x220284C7),
                            Color.Transparent
                        ),
                        startX = left,
                        endX = left + beamWidth
                    ),
                    topLeft = Offset(left, center.y - (12.dp.toPx() / 2f)),
                    size = Size(beamWidth, 12.dp.toPx())
                )
            }
        }

        // Central Minimal Typography Layout (No Logo)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .alpha(textAlpha.value)
                .scale(textScale.value)
        ) {
            // Main Product Name: TESSERACT DYNAMICS
            Text(
                text = "TESSERACT DYNAMICS",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = letterSpacingAnim.value.sp,
                color = Color(0xFFF8FAFC)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Sub-headline: Minimalistic product tagline
            Text(
                text = "CAMPUS RIDE",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = (letterSpacingAnim.value * 0.4f).sp,
                color = Color(0xFF38BDF8),
                modifier = Modifier.alpha(subtitleAlpha.value)
            )
        }
    }
}

/**
 * Synthesizes a clean, original futuristic audio effect for Tesseract Dynamics:
 * A rich low-frequency sub-bass swell paired with a subtle metallic harmonic resonance sweep.
 */
private fun playCinematicSoundEffect() {
    try {
        val sampleRate = 44100
        val durationMs = 1800
        val numSamples = sampleRate * durationMs / 1000
        val generatedSnd = ByteArray(2 * numSamples)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            
            // Sub-bass sweep (62 Hz dropping smoothly to 34 Hz)
            val subFreq = 62.0 - (28.0 * (t / 1.8))
            val subEnvelope = exp(-1.8 * t) * (1.0 - exp(-15.0 * t))
            val subWave = sin(2.0 * PI * subFreq * t) * subEnvelope

            // High harmonic metallic shimmer (480 Hz & 960 Hz resonance blend)
            val shimmerFreq1 = 480.0 + 40.0 * sin(2.0 * PI * 1.5 * t)
            val shimmerFreq2 = 960.0
            val shimmerEnvelope = exp(-2.8 * t) * (1.0 - exp(-25.0 * t))
            val shimmerWave = (sin(2.0 * PI * shimmerFreq1 * t) * 0.6 + sin(2.0 * PI * shimmerFreq2 * t) * 0.4) * shimmerEnvelope

            // Combine sub-bass and harmonic shimmer with clean headroom clipping prevention
            val combined = (subWave * 0.75 + shimmerWave * 0.25).coerceIn(-1.0, 1.0)
            val sampleVal = (combined * 32767).toInt().toShort()

            generatedSnd[2 * i] = (sampleVal.toInt() and 0x00FF).toByte()
            generatedSnd[2 * i + 1] = ((sampleVal.toInt() and 0xFF00) shr 8).toByte()
        }

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(generatedSnd.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(generatedSnd, 0, generatedSnd.size)
        audioTrack.play()
    } catch (e: Exception) {
        // Silently handle audio hardware limitations gracefully
    }
}

