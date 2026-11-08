using System.Diagnostics;
using System.Numerics;
using ComputeSharp;
using ComputeSharp.D2D1.WinUI;
using Microsoft.Graphics.Canvas.UI;
using Microsoft.Graphics.Canvas.UI.Xaml;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Hosting;
using RepDayrise.Core.Domain;

namespace RepDayrise.Controls.Sky;

/// <summary>
/// The living sky, rendered on the GPU by <see cref="SunriseShader"/> with the moon and scrim on top.
/// <see cref="Progress"/> 0..1 raises the sun and animates over 1.4 s. Falls back to the CPU-drawn
/// <see cref="SunriseSkyFallback"/> when the shader can't run, and throttles itself via <see cref="SkyActivity"/>.
/// </summary>
public sealed partial class SkyCanvas : UserControl
{
    /// <summary>Keep the render target near this many pixels; the sky is soft and the dither hides upscaling.</summary>
    private const double PixelBudget = 3_800_000; // a maximised 1440p window renders at full resolution
    private const double TweenSeconds = 1.4;

    public static readonly DependencyProperty ProgressProperty = DependencyProperty.Register(
        nameof(Progress), typeof(double), typeof(SkyCanvas), new PropertyMetadata(0.0, (d, _) => ((SkyCanvas)d).OnInputsChanged()));

    public static readonly DependencyProperty DateProperty = DependencyProperty.Register(
        nameof(Date), typeof(DateOnly), typeof(SkyCanvas), new PropertyMetadata(DateOnly.FromDateTime(DateTime.Now), (d, _) => ((SkyCanvas)d).OnInputsChanged()));

    public static readonly DependencyProperty HillFractionProperty = DependencyProperty.Register(
        nameof(HillFraction), typeof(double), typeof(SkyCanvas), new PropertyMetadata(0.24, (d, _) => ((SkyCanvas)d).OnInputsChanged()));

    public double Progress { get => (double)GetValue(ProgressProperty); set => SetValue(ProgressProperty, value); }
    public DateOnly Date { get => (DateOnly)GetValue(DateProperty); set => SetValue(DateProperty, value); }
    public double HillFraction { get => (double)GetValue(HillFractionProperty); set => SetValue(HillFractionProperty, value); }

    /// <summary>Draw the dark scrim at the top for legible titles.</summary>
    public bool Scrim { get; set; } = true;

    /// <summary>Corner radius of the rounded clip. Zero for none.</summary>
    public double ClipRadius { get; set; }

    private CanvasAnimatedControl? _canvas;
    private SunriseSkyFallback? _fallback;
    private PixelShaderEffect<SunriseShader>? _effect;

    // Written on the UI thread, read on the render thread.
    private readonly Lock _gate = new();
    private float _target, _hill = 0.24f;
    private double _moon;
    private bool _scrim = true;

    // Render thread only.
    private float _shown, _from;
    private double _tweenStart = double.NegativeInfinity;
    private float _lastTarget = float.NaN;
    private readonly Stopwatch _clock = Stopwatch.StartNew();

    public SkyCanvas()
    {
        IsTabStop = false;
        Loaded += (_, _) => Attach();
        Unloaded += (_, _) => Detach();
        SizeChanged += (_, _) => { UpdateDpiScale(); UpdateClip(); };
    }

    private void Attach()
    {
        SkyActivity.Changed += OnActivityChanged;
        BuildSurface();
        OnInputsChanged();
    }

    private void Detach()
    {
        SkyActivity.Changed -= OnActivityChanged;
        TearDownCanvas();
        Content = null;
        _fallback = null;
    }

    private void BuildSurface()
    {
        if (SkyActivity.ForceCpu)
        {
            TearDownCanvas();
            _fallback ??= new SunriseSkyFallback { ClipRadius = 0 };
            Content = _fallback;
            return;
        }
        if (_canvas != null) return;
        // Rounded skies clear to transparent and clip their own drawing: swap chains ignore XAML/visual clips.
        _canvas = new CanvasAnimatedControl
        {
            IsFixedTimeStep = false,
            ClearColor = ClipRadius > 0 ? Windows.UI.Color.FromArgb(0, 0, 0, 0) : Windows.UI.Color.FromArgb(255, 5, 6, 15),
        };
        _canvas.Draw += OnDraw;
        _canvas.CreateResources += (_, _) => _effect = new PixelShaderEffect<SunriseShader>();
        Content = _canvas;
        UpdateDpiScale();
        ApplyMode();
    }

    private void TearDownCanvas()
    {
        if (_canvas == null) return;
        _canvas.Draw -= OnDraw;
        _canvas.RemoveFromVisualTree(); // Win2D leaks its swap chain otherwise
        _canvas = null;
        _effect = null;
    }

    private void OnActivityChanged()
    {
        if (DispatcherQueue.HasThreadAccess) Apply(); else DispatcherQueue.TryEnqueue(Apply);
        void Apply()
        {
            if (!IsLoaded) return;
            if (SkyActivity.ForceCpu && _canvas != null) { BuildSurface(); OnInputsChanged(); return; }
            ApplyMode();
        }
    }

    private void ApplyMode()
    {
        if (_canvas == null) return;
        var mode = SkyActivity.Mode;
        _canvas.TargetElapsedTime = TimeSpan.FromSeconds(1 / SkyActivity.FramesPerSecond);
        _canvas.Paused = mode is SkyMode.Static or SkyMode.Hidden;
        if (mode == SkyMode.Static) _canvas.Invalidate();
    }

    private void OnInputsChanged()
    {
        using (_gate.EnterScope())
        {
            _target = (float)Math.Clamp(Progress, 0, 1);
            _hill = (float)HillFraction;
            _moon = MoonPhase.Phase(Date);
            _scrim = Scrim;
        }
        if (_fallback != null) { _fallback.Progress = Progress; _fallback.Date = new DateTimeOffset(Date.ToDateTime(TimeOnly.MinValue)); }
        if (_canvas == null) return;
        if (SkyActivity.Mode == SkyMode.Static)
        {
            // No animation loop: jump straight to the new state and draw it once.
            _canvas.Invalidate();
        }
        else if (_canvas.Paused && SkyActivity.Mode != SkyMode.Hidden)
        {
            _canvas.Invalidate();
        }
    }

    private void UpdateDpiScale()
    {
        if (_canvas == null || ActualWidth <= 0 || ActualHeight <= 0) return;
        var scale = XamlRoot?.RasterizationScale ?? 1.0;
        var pixels = ActualWidth * ActualHeight * scale * scale;
        var dpiScale = Math.Min(1.0, Math.Sqrt(PixelBudget / pixels));
        dpiScale = Math.Max(0.35, Math.Round(dpiScale * 20) / 20); // 5% steps so resizing doesn't churn the swap chain
        if (Math.Abs(_canvas.DpiScale - dpiScale) > 0.001) _canvas.DpiScale = (float)dpiScale;
    }

    private void UpdateClip()
    {
        if (ClipRadius <= 0 || ActualWidth <= 0) return;
        var visual = ElementCompositionPreview.GetElementVisual(this);
        var geometry = visual.Compositor.CreateRoundedRectangleGeometry();
        geometry.Size = new Vector2((float)ActualWidth, (float)ActualHeight);
        geometry.CornerRadius = new Vector2((float)ClipRadius);
        visual.Clip = visual.Compositor.CreateGeometricClip(geometry);
    }

    private void OnDraw(ICanvasAnimatedControl sender, CanvasAnimatedDrawEventArgs args)
    {
        var effect = _effect;
        if (effect == null) return;
        float target, hill;
        double moon;
        bool scrim;
        using (_gate.EnterScope()) { target = _target; hill = _hill; moon = _moon; scrim = _scrim; }

        var now = _clock.Elapsed.TotalSeconds;
        var p = Advance(target, now, animate: SkyActivity.Mode is SkyMode.Full or SkyMode.Low);
        var time = (float)(now % 3600);

        var ds = args.DrawingSession;
        var size = sender.Size;
        float w = (float)size.Width, h = (float)size.Height;
        var px = ds.Dpi / 96f;
        var stop = SkyPalette.At(p);
        var radius = (float)ClipRadius;
        using var clip = radius > 0 ? Microsoft.Graphics.Canvas.Geometry.CanvasGeometry.CreateRoundedRectangle(ds, 0, 0, w, h, radius, radius) : null;
        using var layer = clip != null ? ds.CreateLayer(1f, clip) : null;
        try
        {
            effect.ConstantBuffer = new SunriseShader(
                new float2(w * px, h * px), time, p, hill, SkyPalette.StarAlpha(p), SkyPalette.CloudAlpha(p),
                F3(stop.Top), F3(stop.Mid), F3(stop.Horizon), F3(stop.HillBack), F3(stop.HillFront), F3(stop.Sun));
            ds.DrawImage(effect, new Windows.Foundation.Rect(0, 0, w, h), new Windows.Foundation.Rect(0, 0, w, h));
        }
        catch (Exception e)
        {
            Debug.WriteLine($"Sky shader failed, falling back to CPU: {e}");
            SkyActivity.ReportGpuFailure();
            return;
        }
        MoonRenderer.DrawMoon(ds, p, moon, w, h);
        if (scrim) MoonRenderer.DrawScrim(ds, w, h);
    }

    /// <summary>Eases the drawn progress toward the target (FastOutSlowIn over 1.4 s), or jumps when not animating.</summary>
    private float Advance(float target, double now, bool animate)
    {
        if (!animate) { _shown = _lastTarget = target; return target; }
        if (target != _lastTarget)
        {
            _from = float.IsNaN(_lastTarget) ? target : _shown;
            _lastTarget = target;
            _tweenStart = now;
        }
        var t = Math.Clamp((now - _tweenStart) / TweenSeconds, 0, 1);
        _shown = _from + (target - _from) * (float)FastOutSlowIn(t);
        return _shown;
    }

    /// <summary>cubic-bezier(0.4, 0, 0.2, 1), Compose's FastOutSlowInEasing.</summary>
    internal static double FastOutSlowIn(double x)
    {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        // Solve bezier x(t) = x for t by Newton's method, then return y(t).
        const double x1 = 0.4, x2 = 0.2, y1 = 0, y2 = 1;
        var t = x;
        for (var i = 0; i < 8; i++)
        {
            var bx = Bezier(t, x1, x2) - x;
            var d = BezierSlope(t, x1, x2);
            if (Math.Abs(d) < 1e-6) break;
            t = Math.Clamp(t - bx / d, 0, 1);
        }
        return Bezier(t, y1, y2);

        static double Bezier(double t, double a, double b) => 3 * a * (1 - t) * (1 - t) * t + 3 * b * (1 - t) * t * t + t * t * t;
        static double BezierSlope(double t, double a, double b) => 3 * a * (1 - t) * (1 - t) + 6 * (b - a) * (1 - t) * t + 3 * (1 - b) * t * t;
    }

    private static float3 F3(Rgb c) => new(c.R, c.G, c.B);
}
