namespace RepDayrise.Core.Domain;

/// <summary>An sRGB colour with 0..1 channels, as the sky shader consumes it.</summary>
public readonly record struct Rgb(float R, float G, float B)
{
    public static Rgb FromHex(uint rgb) => new(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f);

    public static Rgb Lerp(Rgb a, Rgb b, float t) => new(a.R + (b.R - a.R) * t, a.G + (b.G - a.G) * t, a.B + (b.B - a.B) * t);

    public byte R8 => (byte)Math.Round(Math.Clamp(R, 0, 1) * 255);
    public byte G8 => (byte)Math.Round(Math.Clamp(G, 0, 1) * 255);
    public byte B8 => (byte)Math.Round(Math.Clamp(B, 0, 1) * 255);
}

/// <summary>
/// Colour keyframes and fade curves for the living sky, from deep night (0) to full daylight (1).
/// A port of the Android app's SkyPalette, shared by the shader sky, the fallback sky and widgets.
/// </summary>
public static class SkyPalette
{
    public readonly record struct Stop(Rgb Top, Rgb Mid, Rgb Horizon, Rgb HillBack, Rgb HillFront, Rgb Sun);

    private static Stop S(uint top, uint mid, uint hor, uint hillBack, uint hillFront, uint sun) => new(
        Rgb.FromHex(top), Rgb.FromHex(mid), Rgb.FromHex(hor), Rgb.FromHex(hillBack), Rgb.FromHex(hillFront), Rgb.FromHex(sun));

    private static readonly (float At, Stop Stop)[] Stops =
    [
        (0.00f, S(0x05060F, 0x0F1633, 0x1B2450, 0x0E1230, 0x06081A, 0xFF7A3D)),
        (0.25f, S(0x12173A, 0x3A2F6B, 0xB14E63, 0x241F4E, 0x0E1130, 0xFF8A3D)),
        (0.50f, S(0x223066, 0xC2587A, 0xF79B5E, 0x3A2C5C, 0x1C1740, 0xFFA24A)),
        (0.75f, S(0x3F7BC4, 0xF2A566, 0xFFD08A, 0x4B4C7A, 0x2B2A55, 0xFFC55C)),
        (1.00f, S(0x3B8FE0, 0x8CC7F5, 0xE2F1FF, 0x6C8FBF, 0x4A6CA0, 0xFFE27A)),
    ];

    public static Stop At(float progress)
    {
        var p = Math.Clamp(progress, 0f, 1f);
        for (var i = 0; i < Stops.Length - 1; i++)
        {
            var (p0, s0) = Stops[i];
            var (p1, s1) = Stops[i + 1];
            if (p > p1) continue;
            var t = Math.Clamp((p - p0) / (p1 - p0), 0f, 1f);
            return new Stop(Rgb.Lerp(s0.Top, s1.Top, t), Rgb.Lerp(s0.Mid, s1.Mid, t), Rgb.Lerp(s0.Horizon, s1.Horizon, t),
                Rgb.Lerp(s0.HillBack, s1.HillBack, t), Rgb.Lerp(s0.HillFront, s1.HillFront, t), Rgb.Lerp(s0.Sun, s1.Sun, t));
        }
        return Stops[^1].Stop;
    }

    /// <summary>Stars fade out by the time the day is just past half done.</summary>
    public static float StarAlpha(float p) => Math.Clamp(1f - p / 0.55f, 0f, 1f);

    /// <summary>Clouds drift in over the second half of the day.</summary>
    public static float CloudAlpha(float p) => Math.Clamp((p - 0.5f) / 0.5f, 0f, 1f);

    /// <summary>The moon sets as the sun rises.</summary>
    public static float MoonAlpha(float p) => Math.Clamp(1f - p / 0.45f, 0f, 1f);
}
