using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;
using RepDayrise.Services;

namespace RepDayrise.Views;

/// <summary>A followed partner's day, drawn read-only from their last snapshot.</summary>
public sealed partial class PartnerDetailPage : Page
{
    private string _shareId = "";

    public PartnerDetailPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        _shareId = e.Parameter as string ?? "";
        AppServices.SharingChanged += Render;
        Render();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e) => AppServices.SharingChanged -= Render;

    private void Render()
    {
        var f = AppServices.Sharing.State.Following.FirstOrDefault(x => x.ShareId == _shareId);
        if (f == null)
        {
            if (Frame.CanGoBack) Frame.GoBack();
            return;
        }
        Avatar.DisplayName = f.OwnerName;
        NameText.Text = f.OwnerName;
        UpdatedText.Text = f.UpdatedAt > 0 ? $"Updated {PartnersPage.Ago(f.UpdatedAt)}" : "";
        EndedBar.IsOpen = f.Ended;

        var snap = f.Snapshot;
        ContentPanel.Visibility = snap == null ? Visibility.Collapsed : Visibility.Visible;
        WaitingCard.Visibility = snap == null ? Visibility.Visible : Visibility.Collapsed;
        if (snap == null) return;

        var habits = snap.Habits.Select(h => h.ToHabit()).ToList();
        var logic = new HabitLogic(snap.WeekStartsMonday ? DayOfWeek.Monday : DayOfWeek.Sunday);
        var today = EpochDay.Today();
        var (done, due) = logic.DayCounts(habits, snap.Entries, today);
        var progress = logic.DayProgress(habits, snap.Entries, today);
        Sky.Progress = progress;
        ProgressText.Text = due == 0 ? "A quiet day" : done == due ? "Their sun is up" : $"{done} of {due} done today";
        ProgressDetail.Text = $"{Math.Round(progress * 100)}% of the way to sunrise";

        HabitList.Children.Clear();
        foreach (var h in habits) HabitList.Children.Add(HabitRow(h, snap.Entries, logic, today));

        var start = logic.GraphStart(today);
        YearGraph.Show(logic.OverallHeatmap(habits, snap.Entries, start, today), start, today, HabitVisuals.Sunrise, logic.WeekStart);
    }

    private static UIElement HabitRow(Habit h, EntryMap entries, HabitLogic logic, DateOnly today)
    {
        var value = logic.Value(entries, h.Id, today);
        var stats = logic.Stats(h, entries, today);
        var isDue = logic.IsDue(h, entries, today);
        var status = !isDue ? "Done for this period"
            : logic.IsCompleted(h, value) ? "Done today"
            : h.IsMeasurable && value > 0 ? $"{Format.Value(Math.Round(value, 1))} of {h.GoalLabel()}"
            : "Not yet today";

        var grid = new Grid { ColumnSpacing = 14 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var badge = new Grid { Width = 36, Height = 36 };
        badge.Children.Add(new Microsoft.UI.Xaml.Shapes.Ellipse { Fill = HabitVisuals.BrushOf(h.ColorIndex) });
        badge.Children.Add(new FontIcon { Glyph = HabitVisuals.Glyph(h.Icon), FontSize = 16, Foreground = new SolidColorBrush(Microsoft.UI.Colors.White) });
        grid.Children.Add(badge);
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        text.Children.Add(new TextBlock { Text = h.Name, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], TextTrimming = TextTrimming.CharacterEllipsis });
        text.Children.Add(new TextBlock { Text = $"{status} · {h.ScheduleLabel()}", Style = (Style)Application.Current.Resources["SecondaryTextStyle"] });
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        var streak = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        streak.Children.Add(new TextBlock { Text = stats.CurrentStreak.ToString(), HorizontalAlignment = HorizontalAlignment.Right, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] });
        streak.Children.Add(new TextBlock { Text = $"{stats.StreakUnit} streak", HorizontalAlignment = HorizontalAlignment.Right, Style = (Style)Application.Current.Resources["SecondaryTextStyle"] });
        Grid.SetColumn(streak, 2);
        grid.Children.Add(streak);

        return new Border { Style = (Style)Application.Current.Resources["CardStyle"], Padding = new Thickness(12, 10, 16, 10), Child = grid };
    }

    private async void OnUnfollow(object sender, RoutedEventArgs e)
    {
        var name = NameText.Text;
        if (!await Dialogs.Confirm(XamlRoot, $"Unfollow {name}?", $"You'll stop seeing {name}'s habits. You'd need a new invite to follow again.", "Unfollow"))
            return;
        await AppServices.Sharing.Unfollow(_shareId); // Render() then navigates back
    }
}
