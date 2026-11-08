using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.UI;

namespace RepDayrise.Controls;

/// <summary>Simple rounded vertical bars with a label under each, for focus minutes per day.</summary>
public sealed partial class BarChart : Grid
{
    public double BarAreaHeight { get; set; } = 120;

    /// <param name="values">Bar heights (any unit).</param>
    /// <param name="labels">One label per bar; empty strings are allowed.</param>
    /// <param name="tooltips">Tooltip per bar.</param>
    /// <param name="highlight">Index drawn in full colour (e.g. today).</param>
    public void Show(IReadOnlyList<double> values, IReadOnlyList<string> labels, IReadOnlyList<string> tooltips, Color color, int highlight = -1)
    {
        Children.Clear();
        ColumnDefinitions.Clear();
        RowDefinitions.Clear();
        RowDefinitions.Add(new RowDefinition { Height = new GridLength(BarAreaHeight) });
        RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        ColumnSpacing = 6;

        var max = Math.Max(values.DefaultIfEmpty(0).Max(), 1e-9);
        var muted = (Brush)Application.Current.Resources["TextMutedBrush"];
        for (var i = 0; i < values.Count; i++)
        {
            ColumnDefinitions.Add(new ColumnDefinition());
            var v = values[i];
            var height = v <= 0 ? 4 : Math.Max(6, v / max * BarAreaHeight);
            var bar = new Rectangle
            {
                Height = height,
                RadiusX = 5,
                RadiusY = 5,
                VerticalAlignment = VerticalAlignment.Bottom,
                Fill = new SolidColorBrush(v <= 0
                    ? Color.FromArgb(40, color.R, color.G, color.B)
                    : i == highlight ? color : Color.FromArgb(170, color.R, color.G, color.B)),
            };
            ToolTipService.SetToolTip(bar, tooltips[i]);
            SetColumn(bar, i);
            Children.Add(bar);

            var label = new TextBlock
            {
                Text = labels[i],
                FontSize = 11,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 6, 0, 0),
                Foreground = muted,
                FontWeight = i == highlight ? Microsoft.UI.Text.FontWeights.Bold : Microsoft.UI.Text.FontWeights.Normal,
            };
            SetColumn(label, i);
            SetRow(label, 1);
            Children.Add(label);
        }
    }
}
