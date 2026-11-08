using System.Numerics;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using RepDayrise.Services;
using Windows.UI;
using Colors = Microsoft.UI.Colors;

namespace RepDayrise.Controls;

/// <summary>
/// A segmented control for the sky: a translucent capsule with a white selection that glides to the chosen option.
/// </summary>
public sealed partial class SkySegmented : Grid
{
    private const double ItemWidth = 84;
    private const double ItemHeight = 34;
    private static readonly SolidColorBrush Ink = new(Color.FromArgb(255, 0x15, 0x16, 0x1C));
    private static readonly SolidColorBrush Light = new(Color.FromArgb(235, 255, 255, 255));

    private readonly Border _indicator;
    private readonly StackPanel _row = new() { Orientation = Orientation.Horizontal };
    private readonly List<Button> _buttons = [];
    private int _selected = -1;

    /// <summary>Raised when the user picks an option (also when they pick the one already selected).</summary>
    public event Action<int>? ItemInvoked;

    public SkySegmented()
    {
        Padding = new Thickness(3);
        CornerRadius = new CornerRadius((ItemHeight + 6) / 2);
        Background = (Brush)Application.Current.Resources["SkyControlBrush"];
        BorderBrush = (Brush)Application.Current.Resources["SkyHairlineBrush"];
        BorderThickness = new Thickness(1);
        HorizontalAlignment = HorizontalAlignment.Center;
        AutomationProperties.SetAccessibilityView(this, AccessibilityView.Control);

        _indicator = new Border
        {
            Width = ItemWidth,
            Height = ItemHeight,
            CornerRadius = new CornerRadius(ItemHeight / 2),
            Background = new SolidColorBrush(Colors.White),
            HorizontalAlignment = HorizontalAlignment.Left,
            Opacity = 0,
        };
        Children.Add(_indicator);
        Children.Add(_row);
        Loaded += (_, _) => Motion.GlideTranslation(_indicator);
    }

    public void SetItems(IReadOnlyList<string> items)
    {
        _row.Children.Clear();
        _buttons.Clear();
        for (var i = 0; i < items.Count; i++)
        {
            var index = i;
            var b = new Button
            {
                Style = (Style)Application.Current.Resources["SoftButtonStyle"],
                Width = ItemWidth,
                Height = ItemHeight,
                CornerRadius = new CornerRadius(ItemHeight / 2),
                Content = items[i],
                FontSize = 14,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                Foreground = Light,
            };
            b.Click += (_, _) => ItemInvoked?.Invoke(index);
            _row.Children.Add(b);
            _buttons.Add(b);
        }
        Select(_selected, animate: false);
    }

    public void SetLabel(int index, string label)
    {
        if (index >= 0 && index < _buttons.Count) _buttons[index].Content = label;
    }

    /// <summary>Moves the selection; the white capsule glides there.</summary>
    public void Select(int index, bool animate = true)
    {
        _selected = index;
        for (var i = 0; i < _buttons.Count; i++)
        {
            _buttons[i].Foreground = i == index ? Ink : Light;
            AutomationProperties.SetName(_buttons[i], $"{_buttons[i].Content}{(i == index ? ", selected" : "")}");
        }
        _indicator.Opacity = index < 0 ? 0 : 1;
        if (index >= 0) _indicator.Translation = new Vector3((float)(index * ItemWidth), 0, 0);
    }

    public Button ButtonAt(int index) => _buttons[index];
}
