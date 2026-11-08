using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.Foundation;
using Path = Microsoft.UI.Xaml.Shapes.Path;

namespace RepDayrise.Controls;

/// <summary>A thin ring that fills clockwise from the top as a focus phase runs, with a glowing tip.</summary>
public sealed partial class FocusDial : Grid
{
    public static readonly DependencyProperty ValueProperty = DependencyProperty.Register(
        nameof(Value), typeof(double), typeof(FocusDial), new PropertyMetadata(0.0, (d, _) => ((FocusDial)d).Redraw()));

    /// <summary>0..1 around the ring.</summary>
    public double Value { get => (double)GetValue(ValueProperty); set => SetValue(ValueProperty, value); }

    public double Thickness { get; set; } = 6;

    private readonly Ellipse _track = new() { Stroke = new SolidColorBrush(Windows.UI.Color.FromArgb(56, 255, 255, 255)) };
    private readonly Path _arc = new() { StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round };
    private readonly Ellipse _tip = new() { Fill = new SolidColorBrush(Colors.White), HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top };
    private readonly Ellipse _tipGlow = new() { HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top };

    public Brush ArcBrush
    {
        get => _arc.Stroke;
        set { _arc.Stroke = value; _tipGlow.Fill = new RadialGradientBrush
            {
                GradientStops =
                {
                    new GradientStop { Color = Windows.UI.Color.FromArgb(150, 255, 255, 255), Offset = 0 },
                    new GradientStop { Color = Windows.UI.Color.FromArgb(0, 255, 255, 255), Offset = 1 },
                },
            };
        }
    }

    public FocusDial()
    {
        IsHitTestVisible = false;
        ArcBrush = new SolidColorBrush(Colors.White);
        Children.Add(_track);
        Children.Add(_arc);
        Children.Add(_tipGlow);
        Children.Add(_tip);
        SizeChanged += (_, _) => Redraw();
    }

    private void Redraw()
    {
        var size = Math.Min(ActualWidth, ActualHeight);
        if (size <= 0) return;
        var t = Thickness;
        _track.StrokeThickness = _arc.StrokeThickness = t;
        _track.Width = _track.Height = size;

        var r = (size - t) / 2;
        var c = new Point(ActualWidth / 2, ActualHeight / 2);
        var v = Math.Clamp(Value, 0, 1);
        var end = Angle(c, r, v);

        if (v <= 0.0005)
        {
            _arc.Data = null;
        }
        else if (v >= 0.9995)
        {
            _arc.Data = new EllipseGeometry { Center = c, RadiusX = r, RadiusY = r };
        }
        else
        {
            var figure = new PathFigure { StartPoint = new Point(c.X, c.Y - r), IsClosed = false, IsFilled = false };
            figure.Segments.Add(new ArcSegment
            {
                Point = end,
                Size = new Size(r, r),
                SweepDirection = SweepDirection.Clockwise,
                IsLargeArc = v > 0.5,
            });
            var g = new PathGeometry();
            g.Figures.Add(figure);
            _arc.Data = g;
        }

        var tip = t * 1.9;
        _tip.Width = _tip.Height = tip;
        _tip.Margin = new Thickness(end.X - tip / 2, end.Y - tip / 2, 0, 0);
        var glow = t * 7;
        _tipGlow.Width = _tipGlow.Height = glow;
        _tipGlow.Margin = new Thickness(end.X - glow / 2, end.Y - glow / 2, 0, 0);
        _tip.Opacity = _tipGlow.Opacity = v is > 0.0005 and < 0.9995 ? 1 : 0;
    }

    private static Point Angle(Point c, double r, double fraction)
    {
        var a = fraction * 2 * Math.PI - Math.PI / 2;
        return new Point(c.X + r * Math.Cos(a), c.Y + r * Math.Sin(a));
    }
}
