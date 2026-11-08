using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;
using RepDayrise.Services;
using Windows.UI;

namespace RepDayrise.Controls;

/// <summary>A year of days as a GitHub-style grid: one column per week, shaded by how much of the goal was reached.</summary>
public sealed partial class ContributionGraph : UserControl
{
    private const double Cell = 12, Gap = 3, LabelHeight = 18, DayLabelWidth = 30;

    private readonly ScrollViewer _scroller = new()
    {
        HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
        VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
        HorizontalScrollMode = ScrollMode.Enabled,
        VerticalScrollMode = ScrollMode.Disabled,
    };
    private readonly Canvas _canvas = new();

    private IReadOnlyDictionary<long, float> _values = new Dictionary<long, float>();
    private DateOnly _start, _end;
    private Color _color = HabitVisuals.Sunrise;
    private DayOfWeek _weekStart = DayOfWeek.Monday;

    public ContributionGraph()
    {
        _scroller.Content = _canvas;
        Content = _scroller;
        ActualThemeChanged += (_, _) => Draw();
        Loaded += (_, _) => _scroller.ChangeView(_canvas.Width, null, null, disableAnimation: true);
    }

    /// <summary>Shows <paramref name="values"/> (epochDay -> fraction) for the weeks from <paramref name="start"/> to <paramref name="end"/>.</summary>
    public void Show(IReadOnlyDictionary<long, float> values, DateOnly start, DateOnly end, Color color, DayOfWeek weekStart)
    {
        _values = values;
        _start = start;
        _end = end;
        _color = color;
        _weekStart = weekStart;
        Draw();
        _scroller.UpdateLayout();
        _scroller.ChangeView(_canvas.Width, null, null, disableAnimation: true);
    }

    private void Draw()
    {
        _canvas.Children.Clear();
        if (_end < _start) return;
        var dark = ActualTheme == ElementTheme.Dark;
        var empty = new SolidColorBrush(dark ? Color.FromArgb(0x24, 0xFF, 0xFF, 0xFF) : Color.FromArgb(0x14, 0x00, 0x00, 0x00));
        var levels = new Brush[Contribution.Levels + 1];
        levels[0] = empty;
        for (var l = 1; l <= Contribution.Levels; l++)
            levels[l] = new SolidColorBrush(HabitVisuals.WithAlpha(_color, l switch { 1 => 0.3, 2 => 0.5, 3 => 0.75, _ => 1.0 }));

        var weeks = (_end.DayNumber - _start.DayNumber) / 7 + 1;
        _canvas.Width = DayLabelWidth + weeks * (Cell + Gap);
        _canvas.Height = LabelHeight + 7 * (Cell + Gap);

        // Weekday labels for rows 1, 3 and 5, like GitHub.
        for (var row = 1; row < 7; row += 2)
        {
            var day = DaysMask.Week[((_weekStart == DayOfWeek.Sunday ? 6 : 0) + row) % 7];
            AddText(CultureInfo.CurrentCulture.DateTimeFormat.GetAbbreviatedDayName(day), 0, LabelHeight + row * (Cell + Gap) - 2);
        }

        var today = EpochDay.Today();
        var lastMonth = -1;
        for (var week = 0; week < weeks; week++)
        {
            var x = DayLabelWidth + week * (Cell + Gap);
            var first = _start.AddDays(week * 7);
            if (first.Month != lastMonth && week < weeks - 1)
            {
                if (lastMonth != -1 || first.Day <= 7)
                    AddText(CultureInfo.CurrentCulture.DateTimeFormat.GetAbbreviatedMonthName(first.Month), x, 0);
                lastMonth = first.Month;
            }
            for (var row = 0; row < 7; row++)
            {
                var date = first.AddDays(row);
                if (date > _end || date > today) break;
                var fraction = _values.TryGetValue(date.ToEpochDay(), out var f) ? f : 0f;
                var rect = new Rectangle
                {
                    Width = Cell, Height = Cell, RadiusX = 2.5, RadiusY = 2.5,
                    Fill = levels[Contribution.Level(fraction)],
                };
                ToolTipService.SetToolTip(rect, $"{date:ddd, MMM d, yyyy} · {(fraction <= 0 ? "Nothing logged" : $"{Math.Round(fraction * 100)}%")}");
                Canvas.SetLeft(rect, x);
                Canvas.SetTop(rect, LabelHeight + row * (Cell + Gap));
                _canvas.Children.Add(rect);
            }
        }
    }

    /// <summary>Secondary text. Foreground is inherited so it follows the in-app theme override.</summary>
    private void AddText(string text, double x, double y)
    {
        var t = new TextBlock { Text = text, FontSize = 11, Opacity = 0.7 };
        Canvas.SetLeft(t, x);
        Canvas.SetTop(t, y);
        _canvas.Children.Add(t);
    }
}
