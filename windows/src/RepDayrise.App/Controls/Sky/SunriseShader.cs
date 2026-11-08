using ComputeSharp;
using ComputeSharp.D2D1;

namespace RepDayrise.Controls.Sky;

/// <summary>
/// The living sky as a Direct2D pixel shader: a line-for-line port of the Android app's SUNRISE_AGSL
/// (ui/components/SunriseShader.kt). Colours are sRGB-encoded 0..1 values, exactly as on Android.
/// Squares of possibly-negative values are written as x*x because HLSL pow() is undefined for negative bases.
/// </summary>
[D2DInputCount(0)]
[D2DRequiresScenePosition]
[D2DShaderProfile(D2D1ShaderProfile.PixelShader50)]
[D2DGeneratedPixelShaderDescriptor]
internal readonly partial struct SunriseShader : ID2D1PixelShader
{
    private readonly float2 resolution;
    private readonly float time;
    private readonly float progress;
    private readonly float hillFrac;
    private readonly float starAlpha;
    private readonly float cloudAlpha;
    private readonly float3 cTop;
    private readonly float3 cMid;
    private readonly float3 cHor;
    private readonly float3 cHillBack;
    private readonly float3 cHillFront;
    private readonly float3 cSun;

    public SunriseShader(float2 resolution, float time, float progress, float hillFrac, float starAlpha, float cloudAlpha,
        float3 cTop, float3 cMid, float3 cHor, float3 cHillBack, float3 cHillFront, float3 cSun)
    {
        this.resolution = resolution;
        this.time = time;
        this.progress = progress;
        this.hillFrac = hillFrac;
        this.starAlpha = starAlpha;
        this.cloudAlpha = cloudAlpha;
        this.cTop = cTop;
        this.cMid = cMid;
        this.cHor = cHor;
        this.cHillBack = cHillBack;
        this.cHillFront = cHillFront;
        this.cSun = cSun;
    }

    private static float Hash21(float2 p)
    {
        float3 p3 = Hlsl.Frac(new float3(p.X, p.Y, p.X) * 0.1031f);
        p3 += Hlsl.Dot(p3, new float3(p3.Y, p3.Z, p3.X) + 33.33f);
        return Hlsl.Frac((p3.X + p3.Y) * p3.Z);
    }

    private static float VNoise(float2 p)
    {
        float2 i = Hlsl.Floor(p);
        float2 f = Hlsl.Frac(p);
        f = f * f * (3.0f - 2.0f * f);
        float a = Hash21(i);
        float b = Hash21(i + new float2(1.0f, 0.0f));
        float c = Hash21(i + new float2(0.0f, 1.0f));
        float d = Hash21(i + new float2(1.0f, 1.0f));
        return Hlsl.Lerp(Hlsl.Lerp(a, b, f.X), Hlsl.Lerp(c, d, f.X), f.Y);
    }

    private static float Fbm(float2 p)
    {
        float v = 0.0f;
        float a = 0.5f;
        for (int i = 0; i < 5; i++)
        {
            v += a * VNoise(p);
            p = p * 2.03f + new float2(17.1f, 9.2f);
            a *= 0.5f;
        }
        return v;
    }

    private static float RidgeBack(float x, float h, float hf)
    {
        float hy = h * (1.0f - hf);
        return hy - h * 0.025f + h * 0.035f * Hlsl.Sin(x * 5.4f + 0.6f) + h * 0.012f * Hlsl.Sin(x * 13.9f + 1.0f);
    }

    private static float RidgeFront(float x, float h, float hf)
    {
        float hy = h * (1.0f - hf);
        return hy + h * 0.085f + h * 0.040f * Hlsl.Sin(x * 4.2f + 2.2f) + h * 0.012f * Hlsl.Sin(x * 11.5f);
    }

    public float4 Execute()
    {
        float2 fc = D2D.GetScenePosition().XY;
        float w = resolution.X;
        float h = resolution.Y;
        float m = Hlsl.Min(w, h);
        float2 uv = fc / resolution;
        float horizonN = 1.0f - hillFrac;
        float horizonY = h * horizonN;

        // Sky ramp
        float3 col = Hlsl.Lerp(cTop, cMid, Hlsl.SmoothStep(0.0f, 0.62f, uv.Y));
        col = Hlsl.Lerp(col, cHor, Hlsl.SmoothStep(0.50f, 1.0f, uv.Y));

        // Stars
        if (starAlpha > 0.01f && uv.Y < 0.74f)
        {
            float cell = m * 0.055f;
            float2 g = fc / cell;
            float2 id = Hlsl.Floor(g);
            float2 f = Hlsl.Frac(g) - 0.5f;
            float rnd = Hash21(id);
            if (rnd > 0.78f)
            {
                float2 off = new float2(Hash21(id + 3.1f), Hash21(id + 7.7f)) - 0.5f;
                float sd = Hlsl.Length(f - off * 0.6f);
                float size = 0.035f + 0.05f * Hash21(id + 11.3f);
                float tw = 0.55f + 0.45f * Hlsl.Sin(time * (0.6f + 1.6f * Hash21(id + 5.5f)) + rnd * 40.0f);
                float s = Hlsl.SmoothStep(size, 0.0f, sd) + 0.18f * Hlsl.SmoothStep(size * 4.0f, 0.0f, sd);
                col += new float3(1.0f, 1.0f, 1.0f) * (s * tw * starAlpha);
            }
        }

        // Sun
        float sunR = m * 0.12f;
        float sunStart = horizonY + sunR * 1.15f;
        float sunEnd = h * 0.24f;
        float inv = 1.0f - progress;
        float e = 1.0f - inv * inv * inv;
        float sunY = Hlsl.Lerp(sunStart, sunEnd, e);
        float2 sp = new float2(w * 0.5f, sunY);
        float2 dv = fc - sp;
        float d = Hlsl.Length(dv);
        float glowA = 0.28f + 0.3f * (1.0f - progress);

        float bloom = Hlsl.Exp(-Hlsl.Pow(Hlsl.Abs(d / (sunR * 2.4f)), 1.25f));
        float halo = Hlsl.Exp(-d / (sunR * 7.0f));
        col += cSun * (bloom * (0.30f + 0.35f * glowA) + halo * 0.10f);

        float hy = (uv.Y - horizonN) / 0.16f;
        float hx = (uv.X - 0.5f) / 0.55f;
        float hg = Hlsl.Exp(-(hy * hy));
        float xg = Hlsl.Exp(-(hx * hx));
        col += cSun * (hg * xg * 0.16f * (1.0f - 0.5f * progress));

        float ang = Hlsl.Atan2(dv.Y, dv.X);
        float rb = 0.5f + 0.5f * Hlsl.Sin(ang * 11.0f + time * 0.08f + 1.7f * VNoise(new float2(ang * 3.0f, time * 0.05f)));
        float rb2 = rb * rb;
        float ray = rb2 * rb2 * rb;
        float aboveHorizon = Hlsl.SmoothStep(horizonY, horizonY - h * 0.2f, fc.Y);
        col += cSun * (ray * Hlsl.Exp(-d / (sunR * 9.0f)) * 0.05f * (1.0f - 0.6f * progress) * aboveHorizon);

        float disc = 1.0f - Hlsl.SmoothStep(sunR - 1.0f, sunR + 1.0f, d);
        float3 white = new float3(1.0f, 1.0f, 1.0f);
        float3 discC = Hlsl.Lerp(Hlsl.Lerp(cSun, white, 0.45f), cSun, Hlsl.Saturate((fc.Y - (sunY - sunR)) / (2.0f * sunR)));
        col = Hlsl.Lerp(col, discC, disc);

        // Clouds
        if (cloudAlpha > 0.01f)
        {
            float2 cp = fc / m;
            float2 drift = new float2(time * 0.012f, 0.0f);
            float2 sc = new float2(1.7f, 3.4f);
            float dens = Fbm(cp * sc + drift + new float2(3.0f, 1.0f));
            float band = Hlsl.SmoothStep(0.02f, 0.18f, uv.Y) * (1.0f - Hlsl.SmoothStep(0.50f, 0.74f, uv.Y));
            float cm = Hlsl.SmoothStep(0.57f, 0.80f, dens) * band * (1.0f - disc * 0.65f);
            float2 toSun = Hlsl.Normalize(sp - fc + new float2(0.001f, 0.001f)) * 0.025f;
            float dens2 = Fbm((cp + toSun) * sc + drift + new float2(3.0f, 1.0f));
            float lit = Hlsl.Saturate(0.5f + (dens2 - dens) * 7.0f);
            float3 cbase = Hlsl.Lerp(new float3(0.62f, 0.66f, 0.80f), white, lit);
            float3 cc = Hlsl.Lerp(cbase, cbase * 0.6f + cSun * 0.6f, (1.0f - progress) * 0.35f * Hlsl.SmoothStep(0.2f, 0.7f, uv.Y));
            float rim = Hlsl.SmoothStep(0.57f, 0.68f, dens) * (1.0f - Hlsl.SmoothStep(0.68f, 0.84f, dens));
            cc += cSun * (rim * 0.22f * lit);
            col = Hlsl.Lerp(col, cc, cm * cloudAlpha * 0.85f);
        }

        // Hills
        float yb = RidgeBack(uv.X, h, hillFrac);
        float back = Hlsl.SmoothStep(yb - 1.0f, yb + 1.0f, fc.Y);
        float3 hb = Hlsl.Lerp(cHillBack, cHor, 0.22f);
        hb = Hlsl.Lerp(hb, cHillBack * 0.7f, Hlsl.SmoothStep(yb, h, fc.Y));
        float bx = (uv.X - 0.5f) / 0.45f;
        float rimB = Hlsl.Exp(-Hlsl.Max(fc.Y - yb, 0.0f) / 6.0f) * Hlsl.Exp(-(bx * bx)) * (1.0f - 0.5f * progress);
        hb += cSun * (rimB * 0.30f);
        col = Hlsl.Lerp(col, hb, back);

        float yf = RidgeFront(uv.X, h, hillFrac);
        float front = Hlsl.SmoothStep(yf - 1.0f, yf + 1.0f, fc.Y);
        float3 hf = Hlsl.Lerp(cHillFront, cHor, 0.08f);
        float fx = (uv.X - 0.5f) / 0.6f;
        float rimF = Hlsl.Exp(-Hlsl.Max(fc.Y - yf, 0.0f) / 5.0f) * Hlsl.Exp(-(fx * fx)) * (1.0f - 0.5f * progress);
        hf += cSun * (rimF * 0.16f);
        col = Hlsl.Lerp(col, hf, front);

        // Roll highlights off softly instead of clipping them to flat cream
        float peak = Hlsl.Max(col.X, Hlsl.Max(col.Y, col.Z));
        if (peak > 0.82f)
        {
            float rolled = 0.82f + (1.0f - Hlsl.Exp(-(peak - 0.82f) * 5.0f)) * 0.18f;
            col *= rolled / peak;
        }

        // Dither so the long gradients never band
        col += (Hash21(fc) - 0.5f) / 255.0f;
        return new float4(Hlsl.Saturate(col), 1.0f);
    }
}
