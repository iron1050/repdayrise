using RepDayrise.Core.Domain;

namespace RepDayrise.Core.Tests;

public class SkyPaletteTests
{
    [Fact]
    public void KeyframesMatchAndroid()
    {
        Assert.Equal(Rgb.FromHex(0x05060F), SkyPalette.At(0f).Top);
        Assert.Equal(Rgb.FromHex(0xF79B5E), SkyPalette.At(0.5f).Horizon);
        Assert.Equal(Rgb.FromHex(0xFFE27A), SkyPalette.At(1f).Sun);
    }

    [Fact]
    public void InterpolatesBetweenKeyframesAndClamps()
    {
        var mid = SkyPalette.At(0.125f).Top;
        var a = Rgb.FromHex(0x05060F);
        var b = Rgb.FromHex(0x12173A);
        Assert.Equal((a.R + b.R) / 2, mid.R, 4);
        Assert.Equal(SkyPalette.At(0f), SkyPalette.At(-1f));
        Assert.Equal(SkyPalette.At(1f), SkyPalette.At(2f));
    }

    [Fact]
    public void FadeCurves()
    {
        Assert.Equal(1f, SkyPalette.StarAlpha(0f));
        Assert.Equal(0f, SkyPalette.StarAlpha(0.55f));
        Assert.Equal(0f, SkyPalette.CloudAlpha(0.5f));
        Assert.Equal(0.5f, SkyPalette.CloudAlpha(0.75f), 4);
        Assert.Equal(1f, SkyPalette.CloudAlpha(1f));
        Assert.Equal(0f, SkyPalette.MoonAlpha(0.45f));
    }

    [Fact]
    public void HexRoundTrips()
    {
        var c = Rgb.FromHex(0xF2803E);
        Assert.Equal((byte)0xF2, c.R8);
        Assert.Equal((byte)0x80, c.G8);
        Assert.Equal((byte)0x3E, c.B8);
    }
}
