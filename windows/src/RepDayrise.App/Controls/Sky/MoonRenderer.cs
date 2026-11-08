using System.Numerics;
using Microsoft.Graphics.Canvas;
using Microsoft.Graphics.Canvas.Brushes;
using Microsoft.Graphics.Canvas.Geometry;
using RepDayrise.Core.Domain;
using Windows.UI;

namespace RepDayrise.Controls.Sky;

/// <summary>The moon and the top scrim drawn over the shader sky. A port of drawSkyMoon/drawMoon in Sky.kt.</summary>
internal static class MoonRenderer
{
    private static readonly Color Light = Color.FromArgb(255, 0xF5, 0xF0, 0xD8);
    private static readonly Color Dark = Color.FromArgb(255, 0x1B, 0x20, 0x40);

    /// <summary>Draws the moon for <paramref name="progress"/> (it sets as the sun rises) in a w×h DIP area.</summary>
    public static void DrawMoon(CanvasDrawingSession ds, float progress, double phase, float w, float h)
    {
        var alpha = SkyPalette.MoonAlpha(progress);
        if (alpha <= 0.01f) return;
        // Sized for the phone hero; on a full desktop window that's too much moon, so cap it.
        var r = MathF.Min(MathF.Min(w, h) * 0.075f, 30f);
        var center = new Vector2(w * 0.78f, h * 0.22f + progress * h * 0.6f);

        using (var glow = new CanvasRadialGradientBrush(ds,
        [
            new CanvasGradientStop { Position = 0f, Color = WithAlpha(Light, alpha * 0.22f) },
            new CanvasGradientStop { Position = 0.5f, Color = WithAlpha(Light, alpha * 0.08f) },
            new CanvasGradientStop { Position = 1f, Color = WithAlpha(Light, 0f) },
        ]) { Center = center, RadiusX = r * 2.4f, RadiusY = r * 2.4f })
        {
            ds.FillCircle(center, r * 2.4f, glow);
        }
        ds.FillCircle(center, r, WithAlpha(Dark, alpha * 0.6f)); // the unlit part: a faint disc, not a hole
        if (phase < 0.02 || phase > 0.98) return;

        var c = (float)Math.Cos(2 * Math.PI * phase); // +1 new, 0 quarter, -1 full
        var tw = r * MathF.Abs(c);
        using var builder = new CanvasPathBuilder(ds);
        builder.BeginFigure(center.X, center.Y - r);
        builder.AddArc(new Vector2(center.X, center.Y + r), r, r, 0, CanvasSweepDirection.Clockwise, CanvasArcSize.Small);
        // Terminator: an ellipse bulging toward the lit side (crescent) or away from it (gibbous).
        if (tw >= 0.5f)
            builder.AddArc(new Vector2(center.X, center.Y - r), tw, r, 0,
                c > 0 ? CanvasSweepDirection.CounterClockwise : CanvasSweepDirection.Clockwise, CanvasArcSize.Small);
        builder.EndFigure(CanvasFigureLoop.Closed);
        using var lit = CanvasGeometry.CreatePath(builder);

        var saved = ds.Transform;
        if (phase >= 0.5) ds.Transform = Matrix3x2.CreateScale(-1, 1, center) * saved; // waning: mirror
        ds.FillGeometry(lit, WithAlpha(Light, alpha));
        ds.Transform = saved;
    }

    /// <summary>Soft dark scrim over the top 35% so titles stay legible against a bright noon sky.</summary>
    public static void DrawScrim(CanvasDrawingSession ds, float w, float h)
    {
        using var brush = new CanvasLinearGradientBrush(ds, Color.FromArgb(56, 0, 0, 0), Color.FromArgb(0, 0, 0, 0))
        {
            StartPoint = Vector2.Zero,
            EndPoint = new Vector2(0, h * 0.35f),
        };
        ds.FillRectangle(0, 0, w, h * 0.35f, brush);
    }

    private static Color WithAlpha(Color c, float a) => Color.FromArgb((byte)MathF.Round(255 * Math.Clamp(a, 0f, 1f)), c.R, c.G, c.B);
}
