using System.Globalization;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;
using RepDayrise.Services;
using Windows.UI;

namespace RepDayrise.Controls;

/// <summary>One month of a habit: each day shows how much of the goal was reached. Clicking a day edits it.</summary>
public sealed partial class MonthCalendar : UserControl
{
    private readonly TextBlock _title = new() { Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], VerticalAlignment = VerticalAlignment.Center };
    private readonly Button _previous = NavButton("", "Previous month");
    private readonly Button _next = NavButton("", "Next month");
    private readonly Grid _days = new() { RowSpacing = 4, ColumnSpacing = 4 };

    private Habit? _habit;
    private EntryMap _entries = new();
    private HabitLogic _logic = new();

    public DateOnly Month { get; private set; } = new(EpochDay.Today().Year, EpochDay.Today().Month, 1);

    /// <summary>Raised when a past or present day is clicked.</summary>
    public event Action<DateOnly>? DayInvoked;

    public MonthCalendar()
    {
        var header = new Grid { Margin = new Thickness(0, 0, 0, 8) };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.Children.Add(_title);
        Grid.SetColumn(_previous, 1);
        Grid.SetColumn(_next, 2);
        header.Children.Add(_previous);
        header.Children.Add(_next);
        _previous.Click += (_, _) => { Month = Month.AddMonths(-1); Draw(); };
        _next.Click += (_, _) => { Month = Month.AddMonths(1); Draw(); };

        for (var i = 0; i < 7; i++) _days.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var panel = new StackPanel();
        panel.Children.Add(header);
        panel.Children.Add(_days);
        Content = panel;
        ActualThemeChanged += (_, _) => Draw();
    }

    public void Show(Habit habit, EntryMap entries, HabitLogic logic)
    {
        _habit = habit;
        _entries = entries;
        _logic = logic;
        Draw();
    }

    private void Draw()
    {
        _days.Children.Clear();
        _days.RowDefinitions.Clear();
        if (_habit == null) return;
        var habit = _habit;
        var culture = CultureInfo.CurrentCulture;
        _title.Text = Month.ToString("MMMM yyyy", culture);
        var today = EpochDay.Today();
        _next.IsEnabled = Month.AddMonths(1) <= today;

        // Text colours are inherited (and dimmed with opacity) so they follow the in-app theme override.
        var color = HabitVisuals.ColorOf(habit.ColorIndex);
        var accent = new SolidColorBrush(HabitVisuals.Sunrise);

        _days.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var week = _logic.WeekDays(today);
        for (var col = 0; col < 7; col++)
        {
            var label = new TextBlock
            {
                Text = culture.DateTimeFormat.GetShortestDayName(week[col].DayOfWeek),
                HorizontalAlignment = HorizontalAlignment.Center, FontSize = 12, Opacity = 0.7,
            };
            Grid.SetColumn(label, col);
            _days.Children.Add(label);
        }

        var first = Month;
        var offset = first.DayNumber - _logic.WeekStartOf(first).DayNumber;
        var daysInMonth = DateTime.DaysInMonth(first.Year, first.Month);
        var rows = (offset + daysInMonth + 6) / 7;
        for (var r = 0; r < rows; r++) _days.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        for (var d = 1; d <= daysInMonth; d++)
        {
            var date = new DateOnly(first.Year, first.Month, d);
            var index = offset + d - 1;
            var status = _logic.DayStatus(habit, _entries, date);
            var future = date > today;

            var fill = new Ellipse
            {
                Width = 34, Height = 34,
                Fill = new SolidColorBrush(status.Completed ? color : HabitVisuals.WithAlpha(color, status.Fraction > 0 ? 0.25 + 0.45 * status.Fraction : 0)),
            };
            var ring = new Ellipse
            {
                Width = 34, Height = 34, StrokeThickness = date == today ? 2 : 1,
                Stroke = date == today ? accent : new SolidColorBrush(HabitVisuals.WithAlpha(color, status.Scheduled && !future ? 0.35 : 0)),
            };
            var number = new TextBlock
            {
                Text = d.ToString(culture),
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
                FontWeight = status.Completed ? FontWeights.SemiBold : FontWeights.Normal,
                Opacity = !status.Completed && (future || !status.Scheduled) ? 0.4 : 1,
            };
            if (status.Completed) number.Foreground = new SolidColorBrush(Microsoft.UI.Colors.White);
            var cell = new Grid { Width = 34, Height = 34 };
            cell.Children.Add(fill);
            cell.Children.Add(ring);
            cell.Children.Add(number);

            var button = new Button
            {
                Content = cell, Padding = new Thickness(0), BorderThickness = new Thickness(0), Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
                HorizontalAlignment = HorizontalAlignment.Center, IsEnabled = !future && date >= habit.StartDate,
                CornerRadius = new CornerRadius(17),
            };
            ToolTipService.SetToolTip(button, $"{date:dddd, MMM d}" + (status.Value > 0 && habit.IsMeasurable ? $" · {Format.Value(Math.Round(status.Value, 1))} {habit.Unit}" : ""));
            AutomationProperties(button, date, status.Completed);
            button.Click += (_, _) => DayInvoked?.Invoke(date);
            Grid.SetRow(button, index / 7 + 1);
            Grid.SetColumn(button, index % 7);
            _days.Children.Add(button);
        }
    }

    private static void AutomationProperties(Button b, DateOnly date, bool done) =>
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(b, $"{date:D}, {(done ? "done" : "not done")}");

    private static Button NavButton(string glyph, string name)
    {
        var b = new Button
        {
            Content = new FontIcon { Glyph = glyph, FontSize = 12 },
            Style = (Style)Application.Current.Resources["IconButtonStyle"],
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), BorderThickness = new Thickness(0),
            Margin = new Thickness(4, 0, 0, 0),
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(b, name);
        ToolTipService.SetToolTip(b, name);
        return b;
    }
}
