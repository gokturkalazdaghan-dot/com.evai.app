// android/app/src/main/java/com/eva/app/ui/eva/EvaAtomField.kt
package com.eva.app.ui.eva

import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eva.app.ui.theme.ElectricSky
import com.eva.app.ui.theme.NeonCyan
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val ATOM_COUNT = 2800

private val VoidBlack = Color(0xFF05060A)

/**
 * Mikrofon: koyu zeminde binlerce bagimsiz atom isigi.
 *
 * Serbestken her atom kendi yorungesinde gezer. Eva konusacagi zaman
 * (gather) merkeze cekilir ve cekirdek hafif parlar — ikon degil, isik.
 */
@Composable
fun EvaAtomMic(
    gather: Boolean,
    listening: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(VoidBlack)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
    ) {
        EvaAtomField(
            gather = gather,
            listening = listening,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun EvaAtomField(
    gather: Boolean,
    listening: Boolean,
    modifier: Modifier = Modifier,
) {
    val sim = remember { EvaAtomSim(ATOM_COUNT, Random(7)) }
    var frame by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(gather, listening) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.033f)
                    sim.step(dt, gather = gather, listening = listening)
                    frame += dt
                }
                last = now
            }
        }
    }

    val cyan = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            strokeCap = android.graphics.Paint.Cap.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            color = NeonCyan.toArgb()
        }
    }
    val sky = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            strokeCap = android.graphics.Paint.Cap.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            color = ElectricSky.toArgb()
        }
    }
    val white = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            strokeCap = android.graphics.Paint.Cap.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            color = android.graphics.Color.WHITE
        }
    }

    Canvas(modifier = modifier) {
        // frame okumasi yeniden cizimi tetikler
        frame
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f
        val radius = min(w, h) * 0.5f

        val glow = 0.10f + sim.gatherAmount * 0.38f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    NeonCyan.copy(alpha = glow),
                    ElectricSky.copy(alpha = glow * 0.35f),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = radius * (0.55f + sim.gatherAmount * 0.25f),
            ),
            radius = radius,
            center = Offset(cx, cy),
        )

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.clipPath(android.graphics.Path().apply {
                addCircle(cx, cy, radius, android.graphics.Path.Direction.CW)
            })
            sim.draw(native, w, h, cyan, sky, white)
            native.restore()
        }
    }
}

/**
 * Parcaciklar once uretilir, her karede yerinde guncellenir.
 * Her kare yeni nesne ayirmak 2800 atomda kare dusururdu.
 */
private class EvaAtomSim(count: Int, random: Random) {
    val gatherAmount: Float get() = gather

    private var gather = 0f
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val homeX = FloatArray(count)
    private val homeY = FloatArray(count)
    private val size = FloatArray(count)
    private val kind = ByteArray(count)
    private val ptsCyan = FloatArray(count * 2)
    private val ptsSky = FloatArray(count * 2)
    private val ptsWhite = FloatArray(count * 2)

    init {
        for (i in 0 until count) {
            val angle = random.nextFloat() * (Math.PI * 2).toFloat()
            val r = random.nextFloat()
            // Diskte daha yogun kenar, bos merkez: serbest gezerken
            // "bulut" gibi dursun, toplaninca dolsun.
            val dist = kotlin.math.sqrt(r) * 0.92f
            homeX[i] = 0.5f + cos(angle) * dist * 0.5f
            homeY[i] = 0.5f + sin(angle) * dist * 0.5f
            x[i] = homeX[i]
            y[i] = homeY[i]
            vx[i] = (random.nextFloat() - 0.5f) * 0.18f
            vy[i] = (random.nextFloat() - 0.5f) * 0.18f
            size[i] = 1.2f + random.nextFloat() * 2.8f
            kind[i] = when {
                random.nextFloat() < 0.12f -> 2
                random.nextFloat() < 0.45f -> 1
                else -> 0
            }.toByte()
        }
    }

    fun step(dt: Float, gather: Boolean, listening: Boolean) {
        val target = if (gather) 1f else 0f
        this.gather += (target - this.gather) * (1f - kotlin.math.exp(-dt * 4.2f))
        val g = this.gather
        val energy = if (listening && g < 0.4f) 1.55f else 1f
        val spring = 1.6f + g * 10f
        val damp = 0.985f - g * 0.04f
        val n = x.size
        for (i in 0 until n) {
            val tx = homeX[i] * (1f - g) + 0.5f * g
            val ty = homeY[i] * (1f - g) + 0.5f * g
            vx[i] = (vx[i] + (tx - x[i]) * spring * dt) * damp
            vy[i] = (vy[i] + (ty - y[i]) * spring * dt) * damp
            // Bagimsiz drift: her atom kendi ivmesini korur.
            if (g < 0.85f) {
                val wobble = (1f - g) * 0.22f * energy
                vx[i] += sin(x[i] * 40f + i) * wobble * dt
                vy[i] += cos(y[i] * 37f + i * 0.7f) * wobble * dt
            }
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
        }
    }

    fun draw(
        canvas: android.graphics.Canvas,
        width: Float,
        height: Float,
        cyan: android.graphics.Paint,
        sky: android.graphics.Paint,
        white: android.graphics.Paint,
    ) {
        var c = 0
        var s = 0
        var w = 0
        val n = x.size
        val glowBoost = 1f + gather * 1.6f
        for (i in 0 until n) {
            val px = x[i] * width
            val py = y[i] * height
            when (kind[i].toInt()) {
                2 -> {
                    ptsWhite[w++] = px
                    ptsWhite[w++] = py
                }
                1 -> {
                    ptsSky[s++] = px
                    ptsSky[s++] = py
                }
                else -> {
                    ptsCyan[c++] = px
                    ptsCyan[c++] = py
                }
            }
        }
        cyan.strokeWidth = 2.1f * glowBoost
        sky.strokeWidth = 2.4f * glowBoost
        white.strokeWidth = 1.6f * glowBoost
        cyan.alpha = (110 + gather * 90).toInt().coerceIn(0, 255)
        sky.alpha = (90 + gather * 100).toInt().coerceIn(0, 255)
        white.alpha = (70 + gather * 120).toInt().coerceIn(0, 255)
        if (c > 0) canvas.drawPoints(ptsCyan, 0, c, cyan)
        if (s > 0) canvas.drawPoints(ptsSky, 0, s, sky)
        if (w > 0) canvas.drawPoints(ptsWhite, 0, w, white)
    }
}
