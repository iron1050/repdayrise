using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using RepDayrise.Core.Models;
using RepDayrise.Services;

namespace RepDayrise.Views;

/// <summary>Recorded focus sessions, newest first, grouped by day. Sessions can be retagged or deleted.</summary>
public sealed partial class FocusHistoryPage : Page
{
    private int _days = 30;

    public FocusHistoryPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        AppServices.DataChanged += Refresh;
        Refresh();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e) => AppServices.DataChanged -= Refresh;

    private void OnMore(object sender, RoutedEventArgs e)
    {
        _days *= 4;
        Refresh();
    }

    private void Refresh()
    {
        var culture = CultureInfo.CurrentCulture;
        var today = EpochDay.Today();
        var sessions = AppServices.Store.GetFocusSessions(today.AddDays(-_days), today);
        var habits = AppServices.Store.GetAllHabits();
        var byId = habits.ToDictionary(h => h.Id);
        var timerHabits = habits.Where(h => h.Type == HabitType.TIMER && !h.Archived).ToList();

        List.Children.Clear();
        EmptyText.Visibility = sessions.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        MoreButton.Visibility = AppServices.Store.GetAllFocusSessions().Count > sessions.Count ? Visibility.Visible : Visibility.Collapsed;

        foreach (var day in sessions.GroupBy(s => s.Date).OrderByDescending(g => g.Key))
        {
            var total = day.Sum(s => s.FocusedMinutes);
            List.Children.Add(new TextBlock
            {
                Text = $"{(day.Key == today ? "Today" : day.Key == today.AddDays(-1) ? "Yesterday" : day.Key.ToString("dddd d MMMM", culture))} · {Format.Duration(total)}",
                Style = (Style)Application.Current.Resources["TitleSmallStyle"],
                Foreground = (Brush)Application.Current.Resources["TextMutedBrush"],
                Margin = new Thickness(2, 14, 0, 2),
            });
            foreach (var s in day.OrderByDescending(s => s.StartedAt))
                List.Children.Add(Row(s, s.HabitId is long id ? byId.GetValueOrDefault(id) : null, timerHabits, culture));
        }
    }

    private static Border Row(FocusSession s, Habit? habit, IReadOnlyList<Habit> timerHabits, CultureInfo culture)
    {
        var start = DateTimeOffset.FromUnixTimeMilliseconds(s.StartedAt).LocalDateTime;
        var end = DateTimeOffset.FromUnixTimeMilliseconds(s.EndedAt).LocalDateTime;

        var grid = new Grid { ColumnSpacing = 14 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var swatch = new Border
        {
            Width = 6, CornerRadius = new CornerRadius(3),
            Background = habit != null ? HabitVisuals.BrushOf(habit.ColorIndex) : (Brush)Application.Current.Resources["OutlineBrush"],
        };
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        text.Children.Add(new TextBlock
        {
            Text = habit?.Name ?? "Untagged focus",
            Style = (Style)Application.Current.Resources["TitleSmallStyle"],
        });
        var status = s.Status switch
        {
            FocusSessionStatus.STOPPED => " · stopped early",
            FocusSessionStatus.SKIPPED => " · skipped to break",
            _ => "",
        };
        text.Children.Add(new TextBlock
        {
            Text = $"{start.ToString("t", culture)} – {end.ToString("t", culture)}{status}",
            Style = (Style)Application.Current.Resources["BodySmallStyle"],
            Foreground = (Brush)Application.Current.Resources["TextMutedBrush"],
        });
        var minutes = new TextBlock
        {
            Text = Format.Duration(s.FocusedMinutes) is var d && d == "0m" ? "<1m" : d,
            Style = (Style)Application.Current.Resources["TitleLargeStyle"],
            VerticalAlignment = VerticalAlignment.Center,
        };
        Grid.SetColumn(text, 1);
        Grid.SetColumn(minutes, 2);
        grid.Children.Add(swatch);
        grid.Children.Add(text);
        grid.Children.Add(minutes);

        var card = new Border { Style = (Style)Application.Current.Resources["DayriseCardStyle"], Padding = new Thickness(14, 12, 18, 12), Child = grid };

        var menu = new MenuFlyout();
        var move = new MenuFlyoutSubItem { Text = "Count toward", Icon = new FontIcon { Glyph = "" } };
        var none = new MenuFlyoutItem { Text = "No habit" };
        none.Click += (_, _) => AppServices.Store.RetagFocusSession(s.Uid, null);
        move.Items.Add(none);
        foreach (var h in timerHabits)
        {
            var target = h;
            var item = new MenuFlyoutItem { Text = h.Name };
            item.Click += (_, _) => AppServices.Store.RetagFocusSession(s.Uid, target.Id);
            move.Items.Add(item);
        }
        menu.Items.Add(move);
        var delete = new MenuFlyoutItem { Text = "Delete session", Icon = new FontIcon { Glyph = "" } };
        delete.Click += (_, _) => AppServices.Store.DeleteFocusSession(s.Uid);
        menu.Items.Add(delete);
        card.ContextFlyout = menu;
        return card;
    }
}
