package com.example.ui.splash

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricRickshaw
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Singleton manager for the Campus Ride Cinematic Sonic Logo.
 * Ensures the sound plays exactly once per launch, prevents dual/overlapping audio,
 * and guarantees immediate cleanup when the splash screen is dismissed.
 */
object CampusSonicLogoPlayer {
    @Volatile
    private var currentAudioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)

    /**
     * Synthesizes and plays the original multi-layered Campus Ride brand sonic identity:
     * 1. Atmospheric opening texture (0.0s - 0.4s)
     * 2. Rising electric mobility acceleration swell (0.3s - 1.0s)
     * 3. Distinctive cinematic sub-bass impact (1.0s)
     * 4. Pristine crystal tech harmonic chord (1.0s - 2.4s)
     * 5. Smooth natural exponential reverb decay (completed by 2.5s)
     */
    fun playSonicLogo() {
        if (isPlaying.getAndSet(true)) {
            // Prevent duplicate or overlapping triggers
            return
        }

        Thread {
            var track: AudioTrack? = null
            try {
                val sampleRate = 44100
                val durationMs = 2500 // 2.5 seconds total
                val numSamples = sampleRate * durationMs / 1000
                val audioBuffer = ByteArray(2 * numSamples)

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate

                    // --- LAYER 1: Atmospheric Ambient Pad (t: 0.0s -> 1.2s) ---
                    val padEnv = when {
                        t < 0.35 -> (t / 0.35) * (t / 0.35)
                        t < 0.85 -> 1.0 - 0.4 * ((t - 0.35) / 0.5)
                        t < 1.2 -> 0.6 * (1.0 - (t - 0.85) / 0.35)
                        else -> 0.0
                    }
                    val padMod = sin(2.0 * PI * 1.5 * t) * 1.2
                    val padWave = (
                        sin(2.0 * PI * (220.0 + padMod) * t) * 0.40 +
                        sin(2.0 * PI * (329.63 - padMod) * t) * 0.35 +
                        sin(2.0 * PI * 440.0 * t) * 0.25
                    ) * padEnv

                    // --- LAYER 2: Rising Acceleration Swell (t: 0.25s -> 1.02s) ---
                    val riseEnv = when {
                        t in 0.25..1.02 -> {
                            val norm = (t - 0.25) / 0.77
                            norm * norm * norm
                        }
                        t in 1.02..1.18 -> {
                            (1.0 - (t - 1.02) / 0.16)
                        }
                        else -> 0.0
                    }
                    val riseProgress = ((t - 0.25) / 0.77).coerceIn(0.0, 1.0)
                    val riseFreq = 110.0 * (523.25 / 110.0).pow(riseProgress)
                    val riseWave = (
                        sin(2.0 * PI * riseFreq * t) * 0.70 +
                        sin(2.0 * PI * (riseFreq * 2.0) * t) * 0.30
                    ) * riseEnv

                    // --- LAYER 3: Distinctive Low-Frequency Impact (Hits at t = 0.98s) ---
                    val impactEnv = if (t >= 0.98) {
                        val dt = t - 0.98
                        exp(-4.8 * dt) * (1.0 - exp(-75.0 * dt))
                    } else 0.0
                    val impactFreq = if (t >= 0.98) {
                        val dt = t - 0.98
                        42.0 + 56.0 * exp(-12.0 * dt)
                    } else 0.0
                    val impactWave = if (t >= 0.98) {
                        sin(2.0 * PI * impactFreq * (t - 0.98)) * impactEnv
                    } else 0.0

                    // --- LAYER 4: Bright Crystal Tech Chime Chord (t: 1.0s -> 2.45s) ---
                    val chimeEnv = if (t >= 1.0) {
                        val dt = t - 1.0
                        exp(-2.1 * dt) * (1.0 - exp(-50.0 * dt))
                    } else 0.0
                    val chimeWave = if (t >= 1.0) {
                        val dt = t - 1.0
                        (
                            sin(2.0 * PI * 523.25 * dt) * 0.35 +  // C5
                            sin(2.0 * PI * 783.99 * dt) * 0.30 +  // G5
                            sin(2.0 * PI * 1046.50 * dt) * 0.22 + // C6
                            sin(2.0 * PI * 1318.51 * dt) * 0.13   // E6
                        ) * chimeEnv
                    } else 0.0

                    // --- MASTER SUMMING & WARM HEADROOM SATURATION ---
                    val rawMix = (padWave * 0.24 + riseWave * 0.28 + impactWave * 0.46 + chimeWave * 0.36)
                    val mastered = tanh(rawMix * 1.3) * 0.85
                    val sampleVal = (mastered * 32767.0).toInt().coerceIn(-32768, 32767).toShort()

                    audioBuffer[2 * i] = (sampleVal.toInt() and 0x00FF).toByte()
                    audioBuffer[2 * i + 1] = ((sampleVal.toInt() and 0xFF00) shr 8).toByte()
                }

                track = AudioTrack.Builder()
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
                    .setBufferSizeInBytes(audioBuffer.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                currentAudioTrack = track
                track.write(audioBuffer, 0, audioBuffer.size)
                track.play()

                // Sleep safely during playback, checking for cancellation
                val sleepSteps = durationMs / 100
                for (s in 0 until sleepSteps) {
                    if (!isPlaying.get()) break
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                // Silently handle audio hardware limitations gracefully
            } finally {
                try {
                    track?.stop()
                    track?.release()
                } catch (ignored: Exception) {}
                currentAudioTrack = null
                isPlaying.set(false)
            }
        }.apply {
            name = "CampusSonicLogoThread"
            isDaemon = true
            start()
        }
    }

    /**
     * Immediately stops audio playback and releases resources upon screen exit.
     */
    fun stopAndRelease() {
        isPlaying.set(false)
        try {
            currentAudioTrack?.stop()
            currentAudioTrack?.release()
        } catch (ignored: Exception) {}
        currentAudioTrack = null
    }
}

@Composable
fun SplashScreen(
    onNavigateNext: () -> Unit
) {
    // Synchronized visual animation states
    val logoScale = remember { Animatable(0.85f) }
    val logoAlpha = remember { Animatable(0f) }
    val flareProgress = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val letterSpacingAnim = remember { Animatable(4f) }
    val subtitleAlpha = remember { Animatable(0f) }
    val pulseHalo = remember { Animatable(0f) }

    // Ensure audio stops if splash composable is left/disposed early
    DisposableEffect(Unit) {
        onDispose {
            CampusSonicLogoPlayer.stopAndRelease()
        }
    }

    LaunchedEffect(Unit) {
        // Trigger synchronized Campus Ride sonic logo on IO
        launch(Dispatchers.IO) {
            CampusSonicLogoPlayer.playSonicLogo()
        }

        // 0.0s - 0.8s: Atmospheric opening & subtle flare expansion
        launch {
            flareProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 1100,
                    easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
                )
            )
        }

        // 0.2s - 1.0s: Rising logo reveal & scale
        launch {
            logoAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 800,
                    easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
                )
            )
        }

        launch {
            logoScale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(
                    durationMillis = 1100,
                    easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)
                )
            )
        }

        // 0.95s - 1.3s: Sonic impact pulse halo effect
        delay(950)
        launch {
            pulseHalo.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 500, easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1f))
            )
        }

        // 1.0s - 1.8s: Brand text reveal & typography letter-spacing expansion
        launch {
            textAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 750,
                    easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
                )
            )
        }

        launch {
            letterSpacingAnim.animateTo(
                targetValue = 8f,
                animationSpec = tween(
                    durationMillis = 1400,
                    easing = CubicBezierEasing(0.1f, 0.8f, 0.2f, 1f)
                )
            )
        }

        // 1.3s - 2.0s: Subtitle fade-in
        delay(350)
        subtitleAlpha.animateTo(
            targetValue = 0.9f,
            animationSpec = tween(
                durationMillis = 600,
                easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
            )
        )

        // Hold smoothly until 2.6s mark, then transition naturally to the app
        delay(900)
        CampusSonicLogoPlayer.stopAndRelease()
        onNavigateNext()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF070C18),
                        Color(0xFF020408)
                    ),
                    center = Offset.Unspecified,
                    radius = 1200f
                )
            )
            .testTag("splash_screen_container"),
        contentAlignment = Alignment.Center
    ) {
        // Background Cinematic Ambient Canvas & Light Streaks
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val center = Offset(canvasWidth / 2f, canvasHeight / 2f)

            // Dynamic Ambient Radial Glow
            val glowRadius = (280.dp.toPx() * flareProgress.value).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x3838BDF8),
                        Color(0x150284C7),
                        Color.Transparent
                    ),
                    center = center,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = center
            )

            // Sonic Impact Radiant Halo Ring
            if (pulseHalo.value > 0f) {
                val haloRadius = 70.dp.toPx() + (140.dp.toPx() * pulseHalo.value)
                val haloAlpha = (1f - pulseHalo.value) * 0.45f
                drawCircle(
                    color = Color(0xFF38BDF8).copy(alpha = haloAlpha),
                    radius = haloRadius,
                    center = center.copy(y = center.y - 45.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx() * (1f - pulseHalo.value))
                )
            }

            // Anamorphic Horizontal Light Streak
            val beamWidth = canvasWidth * 0.9f * flareProgress.value
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
            }
        }

        // Central Campus Ride Brand Emblem & Typography Layout
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            // Vehicle Mobility Brand Emblem
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF0284C7),
                                Color(0xFF0369A1)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🚗",
                    fontSize = 36.sp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Main Brand Title: CAMPUS RIDE
            Text(
                text = "CAMPUS RIDE",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = letterSpacingAnim.value.sp,
                color = Color(0xFFF8FAFC),
                modifier = Modifier
                    .alpha(textAlpha.value)
                    .scale(logoScale.value)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Subtitle Tagline: Smart Campus Mobility
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.6f),
                modifier = Modifier.alpha(subtitleAlpha.value)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF38BDF8))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "IIIT BHAGALPUR • SMART MOBILITY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.5.sp,
                        color = Color(0xFF38BDF8)
                    )
                }
            }
        }
    }
}


