// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Bends what is behind a capsule of glass near its rim, as a thick lens would: within [band] pixels
 * of the edge, each point shows what lies a little further out, pulled in more strongly the closer it
 * is to the rim. Colour is never split: parting the channels fringes white edges in cyan, which
 * real glass this thin does not do. The middle is untouched.
 * Coordinates are those of the recorded backdrop, which reaches [margin] pixels past the capsule on
 * every side so the rim has something beyond the edge to show.
 */
private const val REFRACTION = """
uniform shader content;
uniform float2 size;
uniform float margin;
uniform float band;
uniform float strength;

float capsule(float2 p, float2 hs, float r) {
    float2 q = abs(p) - hs + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 p = coord - margin - hs;
    float r = min(hs.x, hs.y);
    float inside = -capsule(p, hs, r);
    if (inside <= 0.0 || inside >= band) return content.eval(coord);
    float e = 0.5;
    float2 n = normalize(float2(
        capsule(p + float2(e, 0.0), hs, r) - capsule(p - float2(e, 0.0), hs, r),
        capsule(p + float2(0.0, e), hs, r) - capsule(p - float2(0.0, e), hs, r)));
    float t = 1.0 - inside / band;
    // The slope of a rounded bevel: flat where the band begins, rising smoothly to the rim.
    float k = strength * (1.0 - sqrt(1.0 - t * t));
    half4 bent = content.eval(coord + n * k);
    // Where the bent sample falls outside what was recorded, keep the straight one.
    return mix(content.eval(coord), bent, bent.a);
}
"""

/**
 * The glass's effect on its backdrop: refraction at the rim, then a frosted blur. Null before
 * Android 13, which has no runtime shaders; the caller blurs only.
 */
fun liquidGlassEffect(widthPx: Float, heightPx: Float, marginPx: Float, bandPx: Float, strengthPx: Float, blurPx: Float): RenderEffect? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) refraction(widthPx, heightPx, marginPx, bandPx, strengthPx, blurPx) else null

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun refraction(w: Float, h: Float, margin: Float, band: Float, strength: Float, blur: Float): RenderEffect {
    val shader = RuntimeShader(REFRACTION).apply {
        setFloatUniform("size", w, h)
        setFloatUniform("margin", margin)
        setFloatUniform("band", band)
        setFloatUniform("strength", strength)
    }
    val bend = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
    val frost = android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP)
    // The blur is applied to the bent image, so the bending stays smooth rather than sharp-edged.
    return android.graphics.RenderEffect.createChainEffect(frost, bend).asComposeRenderEffect()
}
