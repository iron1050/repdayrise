using System.Numerics;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;
using Windows.Foundation;
using Windows.UI;

namespace RepDayrise.Controls.Sky;

/// <summary>
/// The CPU-drawn living sky (XAML shapes), used when the GPU shader in <see cref="SkyCanvas"/> can't run.
/// <see cref="Progress"/> 0..1 raises the sun and shifts night to day; stars twinkle and clouds drift while visible.
/// </summary>
public sealed partial class SunriseSkyFallback : UserControl
{
    private sealed record Stop(Color Top, Color Mid, Color Horizon, Color HillBack, Color HillFront, Color Sun);

    private sealed record Star(float X, float Y, float Size, float Phase, float Speed);
    private sealed record Cloud(float X, float Y, float Scale, float Speed, float Alpha);

    private static readonly Star[] StarField = MakeStars();
    private static readonly Cloud[] Clouds =
    [
        new(0.15f, 0.22f, 1.0f, 0.010f, 0.85f), new(0.62f, 0.14f, 0.75f, 0.014f, 0.7f),
        new(0.88f, 0.34f, 0.9f, 0.008f, 0.6f), new(0.40f, 0.42f, 0.6f, 0.012f, 0.5f),
    ];

    private const float HillFraction = 0.24f;

    public static readonly DependencyProperty ProgressProperty = DependencyProperty.Register(
        nameof(Progress), typeof(double), typeof(SunriseSkyFallback), new PropertyMetadata(0.0, (d, _) => ((SunriseSkyFallback)d).OnProgressChanged()));

    public static readonly DependencyProperty DateProperty = DependencyProperty.Register(
        nameof(Date), typeof(DateTimeOffset), typeof(SunriseSkyFallback), new PropertyMetadata(DateTimeOffset.Now, (d, _) => ((SunriseSkyFallback)d).Render()));

    public double Progress { get => (double)GetValue(ProgressProperty); set => SetValue(ProgressProperty, value); }
    public DateTimeOffset Date { get => (DateTimeOffset)GetValue(DateProperty); set => SetValue(DateProperty, value); }

    /// <summary>Corner radius of the rounded clip. Zero for none.</summary>
    public double ClipRadius { get; set; } = 8;

    private readonly Canvas _canvas = new();
    private readonly Rectangle _sky = new();
    private readonly LinearGradientBrush _skyBrush = Gradient(0, 1);
    private readonly List<Ellipse> _stars = [];
    private readonly Ellipse _moonGlow = new();
    private readonly Ellipse _moonDark = new();
    private readonly Microsoft.UI.Xaml.Shapes.Path _moonLit = new();
    private readonly Ellipse _sunGlow = new();
    private readonly Ellipse _sun = new();
    private readonly Rectangle _haze = new();
    private readonly List<Microsoft.UI.Xaml.Shapes.Path> _clouds = [];
    private readonly Microsoft.UI.Xaml.Shapes.Path _hillBack = new();
    private readonly Microsoft.UI.Xaml.Shapes.Path _hillFront = new();

    private double _shown;        // the progress currently drawn
    private double _from, _to;    // tween endpoints
    private TimeSpan _tweenStart;
    private bool _tweening;
    private readonly System.Diagnostics.Stopwatch _clock = System.Diagnostics.Stopwatch.StartNew();
    private bool _rendering;
    private int _frame;

    public SunriseSkyFallback()
    {
        IsTabStop = false;
        _sky.Fill = _skyBrush;
        _canvas.Children.Add(_sky);
        foreach (var _ in StarField)
        {
            var e = new Ellipse { Fill = new SolidColorBrush(Microsoft.UI.Colors.White) };
            _stars.Add(e);
            _canvas.Children.Add(e);
        }
        _moonGlow.Fill = new RadialGradientBrush
        {
            GradientStops =
            {
                new GradientStop { Color = WithAlpha(C(0xF5F0D8), 0.22), Offset = 0 },
                new GradientStop { Color = WithAlpha(C(0xF5F0D8), 0.08), Offset = 0.5 },
                new GradientStop { Color = WithAlpha(C(0xF5F0D8), 0), Offset = 1 },
            },
        };
        _moonDark.Fill = new SolidColorBrush(WithAlpha(C(0x1B2040), 0.9));
        _moonLit.Fill = new SolidColorBrush(C(0xF5F0D8));
        _sunGlow.Fill = new RadialGradientBrush();
        _sun.Fill = Gradient(0, 1);
        _haze.Fill = Gradient(0, 1);
        foreach (var c in Clouds)
        {
            var p = new Microsoft.UI.Xaml.Shapes.Path { Fill = new SolidColorBrush(Microsoft.UI.Colors.White) };
            _clouds.Add(p);
        }
        _canvas.Children.Add(_moonGlow);
        _canvas.Children.Add(_moonDark);
        _canvas.Children.Add(_moonLit);
        _canvas.Children.Add(_sunGlow);
        _canvas.Children.Add(_sun);
        _canvas.Children.Add(_haze);
        foreach (var p in _clouds) _canvas.Children.Add(p);
        _canvas.Children.Add(_hillBack);
        _canvas.Children.Add(_hillFront);
        Content = _canvas;

        SizeChanged += (_, _) => { Layout(); Render(); };
        Loaded += (_, _) => StartRendering();
        Unloaded += (_, _) => StopRendering();
    }

    private void OnProgressChanged()
    {
        _from = _shown;
        _to = Math.Clamp(Progress, 0, 1);
        _tweenStart = _clock.Elapsed;
        _tweening = true;
        StartRendering();
    }

    private void StartRendering()
    {
        if (_rendering || !IsLoaded) return;
        _rendering = true;
        CompositionTarget.Rendering += OnFrame;
    }

    private void StopRendering()
    {
        if (!_rendering) return;
        _rendering = false;
        CompositionTarget.Rendering -= OnFrame;
    }

    private void OnFrame(object? sender, object e)
    {
        if (_tweening)
        {
            var t = Math.Clamp((_clock.Elapsed - _tweenStart).TotalSeconds / 1.2, 0, 1);
            _shown = _from + (_to - _from) * EaseOutCubic(t);
            if (t >= 1) _tweening = false;
            Render();
        }
        else if (++_frame % 2 == 0) // twinkle and drift at half the frame rate
        {
            Animate();
        }
    }

    /// <summary>Positions that depend only on size: stars, clipping and the hills.</summary>
    private void Layout()
    {
        double w = ActualWidth, h = ActualHeight;
        if (w <= 0 || h <= 0) return;
        _sky.Width = w;
        _sky.Height = h;
        var density = Math.Min(w, h) / 200.0;
        for (var i = 0; i < StarField.Length; i++)
        {
            var s = StarField[i];
            var r = s.Size * density;
            _stars[i].Width = _stars[i].Height = r * 2;
            Canvas.SetLeft(_stars[i], s.X * w - r);
            Canvas.SetTop(_stars[i], s.Y * h - r);
        }

        var horizonY = h * (1 - HillFraction);
        _hillBack.Data = HillGeometry(w, h, horizonY + h * 0.02,
            (w * 0.18, horizonY - h * 0.07, w * 0.34, horizonY - h * 0.05, w * 0.52, horizonY + h * 0.01),
            (w * 0.7, horizonY + h * 0.06, w * 0.86, horizonY - h * 0.02, w, horizonY - h * 0.05));
        var y = horizonY + h * 0.09;
        _hillFront.Data = HillGeometry(w, h, y + h * 0.03,
            (w * 0.22, y - h * 0.05, w * 0.4, y - h * 0.06, w * 0.6, y),
            (w * 0.78, y + h * 0.05, w * 0.9, y + h * 0.02, w, y - h * 0.02));

        _haze.Width = w;
        _haze.Height = h * 0.25;
        Canvas.SetTop(_haze, horizonY - h * 0.25);

        var visual = ElementCompositionPreview.GetElementVisual(this);
        var compositor = visual.Compositor;
        var geometry = compositor.CreateRoundedRectangleGeometry();
        geometry.Size = new Vector2((float)w, (float)h);
        geometry.CornerRadius = new Vector2((float)ClipRadius);
        visual.Clip = compositor.CreateGeometricClip(geometry);
    }

    /// <summary>Everything that depends on progress.</summary>
    private void Render()
    {
        double w = ActualWidth, h = ActualHeight;
        if (w <= 0 || h <= 0) return;
        var p = (float)Math.Clamp(_shown, 0, 1);
        var stop = At(p);

        SetStops(_skyBrush, (stop.Top, 0), (stop.Mid, 0.62), (stop.Horizon, 1));

        // Moon (sets as the sun rises)
        var moonAlpha = Math.Clamp(1 - p / 0.45, 0, 1);
        var mr = Math.Min(w, h) * 0.075;
        var mcx = w * 0.78;
        var mcy = h * 0.22 + p * h * 0.6;
        Place(_moonGlow, mcx, mcy, mr * 2.4);
        Place(_moonDark, mcx, mcy, mr);
        _moonGlow.Opacity = _moonDark.Opacity = moonAlpha;
        var phase = MoonPhase.Phase(DateOnly.FromDateTime(Date.DateTime));
        _moonLit.Data = MoonLitGeometry(phase, mcx, mcy, mr);
        _moonLit.RenderTransform = phase < 0.5 ? null : new ScaleTransform { ScaleX = -1, CenterX = mcx };
        _moonLit.Opacity = phase < 0.02 || phase > 0.98 ? 0 : moonAlpha;

        // Sun
        var horizonY = h * (1 - HillFraction);
        var sr = Math.Min(w, h) * 0.12;
        var sunStart = horizonY + sr * 1.15;
        var sunEnd = h * 0.24;
        var sunY = sunStart + (sunEnd - sunStart) * EaseOutCubic(p);
        var sunX = w * 0.5;
        var glowAlpha = 0.28 + 0.3 * (1 - p);
        Place(_sunGlow, sunX, sunY, sr * 3.4);
        var glow = (RadialGradientBrush)_sunGlow.Fill;
        glow.GradientStops.Clear();
        glow.GradientStops.Add(new GradientStop { Color = WithAlpha(stop.Sun, glowAlpha), Offset = 0 });
        glow.GradientStops.Add(new GradientStop { Color = WithAlpha(stop.Sun, glowAlpha * 0.35), Offset = 0.45 });
        glow.GradientStops.Add(new GradientStop { Color = WithAlpha(stop.Sun, 0), Offset = 1 });
        Place(_sun, sunX, sunY, sr);
        SetStops((LinearGradientBrush)_sun.Fill, (Lerp(stop.Sun, Microsoft.UI.Colors.White, 0.45f), 0), (stop.Sun, 1));

        SetStops((LinearGradientBrush)_haze.Fill, (WithAlpha(stop.Horizon, 0), 0), (WithAlpha(stop.Horizon, 0.55), 1));
        _hillBack.Fill = new SolidColorBrush(stop.HillBack);
        _hillFront.Fill = new SolidColorBrush(stop.HillFront);

        Animate();
    }

    /// <summary>Twinkling stars and drifting clouds.</summary>
    private void Animate()
    {
        double w = ActualWidth, h = ActualHeight;
        if (w <= 0 || h <= 0) return;
        var p = Math.Clamp(_shown, 0, 1);
        var time = _clock.Elapsed.TotalSeconds;

        var starAlpha = Math.Clamp(1 - p / 0.55, 0, 1);
        for (var i = 0; i < StarField.Length; i++)
        {
            var s = StarField[i];
            _stars[i].Opacity = starAlpha <= 0.01 ? 0 : starAlpha * (0.55 + 0.45 * Math.Sin(time * s.Speed + s.Phase));
        }

        var cloudAlpha = Math.Clamp((p - 0.5) / 0.5, 0, 1);
        for (var i = 0; i < Clouds.Length; i++)
        {
            var c = Clouds[i];
            var path = _clouds[i];
            path.Opacity = cloudAlpha * c.Alpha;
            if (cloudAlpha <= 0.01) continue;
            var span = w * 1.4;
            var drift = time * c.Speed * w % span;
            var cx = (c.X * w + drift) % span - w * 0.2;
            path.Data = CloudGeometry(cx, c.Y * h, Math.Min(w, h) * 0.16 * c.Scale);
        }
    }

    // ---- Geometry -----------------------------------------------------------------------------

    private static Geometry HillGeometry(double w, double h, double startY,
        (double, double, double, double, double, double) a, (double, double, double, double, double, double) b)
    {
        var figure = new PathFigure { StartPoint = new Point(0, startY), IsClosed = true, IsFilled = true };
        figure.Segments.Add(new BezierSegment { Point1 = new Point(a.Item1, a.Item2), Point2 = new Point(a.Item3, a.Item4), Point3 = new Point(a.Item5, a.Item6) });
        figure.Segments.Add(new BezierSegment { Point1 = new Point(b.Item1, b.Item2), Point2 = new Point(b.Item3, b.Item4), Point3 = new Point(b.Item5, b.Item6) });
        figure.Segments.Add(new LineSegment { Point = new Point(w, h) });
        figure.Segments.Add(new LineSegment { Point = new Point(0, h) });
        var g = new PathGeometry();
        g.Figures.Add(figure);
        return g;
    }

    /// <summary>The lit part of the moon for a waxing phase; waning phases are mirrored by the caller.</summary>
    private static Geometry MoonLitGeometry(double phase, double cx, double cy, double r)
    {
        var c = Math.Cos(2 * Math.PI * phase); // +1 new, 0 quarter, -1 full
        var tw = r * Math.Abs(c);
        var figure = new PathFigure { StartPoint = new Point(cx, cy - r), IsClosed = true, IsFilled = true };
        figure.Segments.Add(new ArcSegment { Point = new Point(cx, cy + r), Size = new Size(r, r), SweepDirection = SweepDirection.Clockwise });
        // Terminator: an ellipse bulging toward the lit side (crescent) or away from it (gibbous).
        if (tw >= 0.5)
            figure.Segments.Add(new ArcSegment
            {
                Point = new Point(cx, cy - r), Size = new Size(tw, r),
                SweepDirection = c > 0 ? SweepDirection.Counterclockwise : SweepDirection.Clockwise,
            });
        var g = new PathGeometry();
        g.Figures.Add(figure);
        return g;
    }

    private static Geometry CloudGeometry(double x, double y, double r)
    {
        var g = new GeometryGroup { FillRule = FillRule.Nonzero };
        void Circle(double dx, double dy, double rr) => g.Children.Add(new EllipseGeometry { Center = new Point(x + dx, y + dy), RadiusX = rr, RadiusY = rr });
        Circle(-r * 0.9, r * 0.15, r * 0.55);
        Circle(-r * 0.3, -r * 0.1, r * 0.75);
        Circle(r * 0.35, 0, r * 0.65);
        Circle(r * 0.95, r * 0.2, r * 0.5);
        g.Children.Add(new RectangleGeometry { Rect = new Rect(x - r * 0.9, y + r * 0.05, r * 1.85, r * 0.6) });
        return g;
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static void Place(FrameworkElement e, double cx, double cy, double r)
    {
        e.Width = e.Height = Math.Max(r * 2, 0);
        Canvas.SetLeft(e, cx - r);
        Canvas.SetTop(e, cy - r);
    }

    private static Stop At(float p)
    {
        var s = SkyPalette.At(p);
        return new Stop(ToColor(s.Top), ToColor(s.Mid), ToColor(s.Horizon), ToColor(s.HillBack), ToColor(s.HillFront), ToColor(s.Sun));
    }

    private static Color ToColor(Rgb c) => Color.FromArgb(0xFF, c.R8, c.G8, c.B8);

    private static LinearGradientBrush Gradient(double startY, double endY) =>
        new() { StartPoint = new Point(0.5, startY), EndPoint = new Point(0.5, endY) };

    private static void SetStops(LinearGradientBrush brush, params (Color Color, double Offset)[] stops)
    {
        if (brush.GradientStops.Count != stops.Length)
        {
            brush.GradientStops.Clear();
            foreach (var _ in stops) brush.GradientStops.Add(new GradientStop());
        }
        for (var i = 0; i < stops.Length; i++)
        {
            brush.GradientStops[i].Color = stops[i].Color;
            brush.GradientStops[i].Offset = stops[i].Offset;
        }
    }

    private static double EaseOutCubic(double t) { var u = 1 - t; return 1 - u * u * u; }

    private static Color Lerp(Color a, Color b, float t) => Color.FromArgb(
        (byte)(a.A + (b.A - a.A) * t), (byte)(a.R + (b.R - a.R) * t), (byte)(a.G + (b.G - a.G) * t), (byte)(a.B + (b.B - a.B) * t));

    private static Color WithAlpha(Color c, double a) => Color.FromArgb((byte)Math.Round(255 * Math.Clamp(a, 0, 1)), c.R, c.G, c.B);

    private static Color C(uint rgb) => Color.FromArgb(0xFF, (byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb);

    private static Star[] MakeStars()
    {
        var r = new Random(20250925);
        return Enumerable.Range(0, 90).Select(_ => new Star(
            r.NextSingle(), r.NextSingle() * 0.72f, 0.5f + r.NextSingle() * 1.4f, r.NextSingle() * 6.28f, 0.6f + r.NextSingle() * 1.6f)).ToArray();
    }
}
