package com.repdayrise.app.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb

/**
 * GPU sunrise. The CPU still picks the palette (SkyPalette) for the current progress; the shader paints everything
 * else per pixel so the result has no banding and real depth:
 *  - a smooth three-stop sky ramp with dithering,
 *  - a sun disc with a tight bloom, a wide atmospheric halo, a horizon glow and faint god rays,
 *  - drifting fractal-noise clouds that are lit from the sun's side and gain a silver lining,
 *  - twinkling stars that fade out as the day brightens,
 *  - two hill ridges with aerial perspective and a warm rim light.
 * The moon is drawn on top by [drawSkyMoon], since it needs the phase maths from the Canvas path.
 */
private const val SUNRISE_AGSL = """
uniform float2 resolution;
uniform float time;
uniform float progress;
uniform float hillFrac;
uniform float starAlpha;
uniform float cloudAlpha;
layout(color) uniform half4 cTop;
layout(color) uniform half4 cMid;
layout(color) uniform half4 cHor;
layout(color) uniform half4 cHillBack;
layout(color) uniform half4 cHillFront;
layout(color) uniform half4 cSun;

float hash21(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * vnoise(p);
        p = p * 2.03 + float2(17.1, 9.2);
        a *= 0.5;
    }
    return v;
}

float ridgeBack(float x, float h, float hf) {
    float hy = h * (1.0 - hf);
    return hy - h * 0.025 + h * 0.035 * sin(x * 5.4 + 0.6) + h * 0.012 * sin(x * 13.9 + 1.0);
}

float ridgeFront(float x, float h, float hf) {
    float hy = h * (1.0 - hf);
    return hy + h * 0.085 + h * 0.040 * sin(x * 4.2 + 2.2) + h * 0.012 * sin(x * 11.5);
}

half4 main(float2 fc) {
    float w = resolution.x;
    float h = resolution.y;
    float m = min(w, h);
    float2 uv = fc / resolution;
    float horizonN = 1.0 - hillFrac;
    float horizonY = h * horizonN;

    float3 top = float3(cTop.rgb);
    float3 mid = float3(cMid.rgb);
    float3 hor = float3(cHor.rgb);
    float3 sunC = float3(cSun.rgb);

    // Sky ramp
    float3 col = mix(top, mid, smoothstep(0.0, 0.62, uv.y));
    col = mix(col, hor, smoothstep(0.50, 1.0, uv.y));

    // Stars
    if (starAlpha > 0.01 && uv.y < 0.74) {
        float cell = m * 0.055;
        float2 g = fc / cell;
        float2 id = floor(g);
        float2 f = fract(g) - 0.5;
        float rnd = hash21(id);
        if (rnd > 0.78) {
            float2 off = float2(hash21(id + 3.1), hash21(id + 7.7)) - 0.5;
            float d = length(f - off * 0.6);
            float size = 0.035 + 0.05 * hash21(id + 11.3);
            float tw = 0.55 + 0.45 * sin(time * (0.6 + 1.6 * hash21(id + 5.5)) + rnd * 40.0);
            float s = smoothstep(size, 0.0, d) + 0.18 * smoothstep(size * 4.0, 0.0, d);
            col += float3(s * tw * starAlpha);
        }
    }

    // Sun
    float sunR = m * 0.12;
    float sunStart = horizonY + sunR * 1.15;
    float sunEnd = h * 0.24;
    float e = 1.0 - pow(1.0 - progress, 3.0);
    float sunY = mix(sunStart, sunEnd, e);
    float2 sp = float2(w * 0.5, sunY);
    float2 dv = fc - sp;
    float d = length(dv);
    float glowA = 0.28 + 0.3 * (1.0 - progress);

    float bloom = exp(-pow(d / (sunR * 2.4), 1.25));
    float halo = exp(-d / (sunR * 7.0));
    col += sunC * (bloom * (0.30 + 0.35 * glowA) + halo * 0.10);

    float hg = exp(-pow((uv.y - horizonN) / 0.16, 2.0));
    float xg = exp(-pow((uv.x - 0.5) / 0.55, 2.0));
    col += sunC * hg * xg * 0.16 * (1.0 - 0.5 * progress);

    float ang = atan(dv.y, dv.x);
    float ray = pow(0.5 + 0.5 * sin(ang * 11.0 + time * 0.08 + 1.7 * vnoise(float2(ang * 3.0, time * 0.05))), 5.0);
    float aboveHorizon = smoothstep(horizonY, horizonY - h * 0.2, fc.y);
    col += sunC * ray * exp(-d / (sunR * 9.0)) * 0.05 * (1.0 - 0.6 * progress) * aboveHorizon;

    float disc = 1.0 - smoothstep(sunR - 1.0, sunR + 1.0, d);
    float3 discC = mix(mix(sunC, float3(1.0), 0.45), sunC, clamp((fc.y - (sunY - sunR)) / (2.0 * sunR), 0.0, 1.0));
    col = mix(col, discC, disc);

    // Clouds
    if (cloudAlpha > 0.01) {
        float2 cp = fc / m;
        float2 drift = float2(time * 0.012, 0.0);
        float2 sc = float2(1.7, 3.4);
        float dens = fbm(cp * sc + drift + float2(3.0, 1.0));
        float band = smoothstep(0.02, 0.18, uv.y) * (1.0 - smoothstep(0.50, 0.74, uv.y));
        float cm = smoothstep(0.57, 0.80, dens) * band * (1.0 - disc * 0.65);
        float2 toSun = normalize(sp - fc + float2(0.001)) * 0.025;
        float dens2 = fbm((cp + toSun) * sc + drift + float2(3.0, 1.0));
        float lit = clamp(0.5 + (dens2 - dens) * 7.0, 0.0, 1.0);
        float3 base = mix(float3(0.62, 0.66, 0.80), float3(1.0), lit);
        float3 cc = mix(base, base * 0.6 + sunC * 0.6, (1.0 - progress) * 0.35 * smoothstep(0.2, 0.7, uv.y));
        float rim = smoothstep(0.57, 0.68, dens) * (1.0 - smoothstep(0.68, 0.84, dens));
        cc += sunC * rim * 0.22 * lit;
        col = mix(col, cc, cm * cloudAlpha * 0.85);
    }

    // Hills
    float yb = ridgeBack(uv.x, h, hillFrac);
    float back = smoothstep(yb - 1.0, yb + 1.0, fc.y);
    float3 hb = mix(float3(cHillBack.rgb), hor, 0.22);
    hb = mix(hb, float3(cHillBack.rgb) * 0.7, smoothstep(yb, h, fc.y));
    float rimB = exp(-max(fc.y - yb, 0.0) / 6.0) * exp(-pow((uv.x - 0.5) / 0.45, 2.0)) * (1.0 - 0.5 * progress);
    hb += sunC * rimB * 0.30;
    col = mix(col, hb, back);

    float yf = ridgeFront(uv.x, h, hillFrac);
    float front = smoothstep(yf - 1.0, yf + 1.0, fc.y);
    float3 hf = mix(float3(cHillFront.rgb), hor, 0.08);
    float rimF = exp(-max(fc.y - yf, 0.0) / 5.0) * exp(-pow((uv.x - 0.5) / 0.6, 2.0)) * (1.0 - 0.5 * progress);
    hf += sunC * rimF * 0.16;
    col = mix(col, hf, front);

    // Roll highlights off softly instead of clipping them to flat cream
    float peak = max(col.r, max(col.g, col.b));
    if (peak > 0.82) {
        float rolled = 0.82 + (1.0 - exp(-(peak - 0.82) * 5.0)) * 0.18;
        col *= rolled / peak;
    }

    // Dither so the long gradients never band on OLED
    col += (hash21(fc) - 0.5) / 255.0;
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

/** The shared shader, or null below Android 13 or if it somehow fails to compile (callers fall back to Canvas). */
@Composable
fun rememberSunriseShader(): RuntimeShader? = remember {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null
    else runCatching { RuntimeShader(SUNRISE_AGSL) }
        .onFailure { android.util.Log.w("SunriseShader", "AGSL sunrise failed, using the Canvas sky", it) }
        .getOrNull()
}

/**
 * Paints the shader sky into a [w] x [h] rect at the current origin. [decor] = false leaves out stars and clouds, used
 * by the blurred glass echo where they would just be noise.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun DrawScope.drawSunriseShader(
    shader: RuntimeShader,
    progress: Float,
    time: Float,
    hillFraction: Float,
    w: Float = size.width,
    h: Float = size.height,
    decor: Boolean = true,
) {
    val p = progress.coerceIn(0f, 1f)
    val stop = SkyPalette.at(p)
    shader.setFloatUniform("resolution", w, h)
    shader.setFloatUniform("time", time)
    shader.setFloatUniform("progress", p)
    shader.setFloatUniform("hillFrac", hillFraction)
    shader.setFloatUniform("starAlpha", if (decor) (1f - p / 0.55f).coerceIn(0f, 1f) else 0f)
    shader.setFloatUniform("cloudAlpha", if (decor) ((p - 0.5f) / 0.5f).coerceIn(0f, 1f) else 0f)
    shader.setColorUniform("cTop", stop.top.toArgb())
    shader.setColorUniform("cMid", stop.mid.toArgb())
    shader.setColorUniform("cHor", stop.horizon.toArgb())
    shader.setColorUniform("cHillBack", stop.hillBack.toArgb())
    shader.setColorUniform("cHillFront", stop.hillFront.toArgb())
    shader.setColorUniform("cSun", stop.sun.toArgb())
    drawRect(ShaderBrush(shader), size = Size(w, h))
}
