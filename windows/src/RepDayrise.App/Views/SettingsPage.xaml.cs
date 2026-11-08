using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using RepDayrise.Core.Models;
using RepDayrise.Services;
using Windows.ApplicationModel;
using Windows.System;

namespace RepDayrise.Views;

public sealed partial class SettingsPage : Page
{
    private bool _loading;

    public SettingsPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        _loading = true;
        var s = AppServices.Settings.Current;
        ThemeButtons.SelectedIndex = (int)s.ThemeMode;
        WeekStartBox.SelectedIndex = s.WeekStartsMonday ? 0 : 1;
        CompletedSwitch.IsOn = s.CompletedToBottom;
        EcoSwitch.IsOn = s.SkyQuality == Core.Data.SkyQuality.Eco;
        var f = s.Focus;
        ShortBreakBox.Value = f.ShortBreakMinutes;
        LongBreakBox.Value = f.LongBreakMinutes;
        LongEveryBox.Value = f.LongBreakEvery;
        DailyGoalBox.Value = f.DailyGoalMinutes;
        AutoBreakSwitch.IsOn = f.AutoStartBreaks;
        AutoFocusSwitch.IsOn = f.AutoStartFocus;
        PartialSwitch.IsOn = f.CountPartial;
        QuietSwitch.IsOn = f.QuietReminders;
        SoundSwitch.IsOn = f.Sound;
        var v = Package.Current.Id.Version;
        AboutCard.Description = $"Version {v.Major}.{v.Minor}.{v.Build}";
        _loading = false;
    }

    private void OnThemeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading || ThemeButtons.SelectedIndex < 0) return;
        AppServices.Settings.Update(s => s with { ThemeMode = (ThemeMode)ThemeButtons.SelectedIndex });
    }

    private void OnWeekStartChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading) return;
        AppServices.Settings.Update(s => s with { WeekStartsMonday = WeekStartBox.SelectedIndex == 0 });
    }

    private void OnCompletedToggled(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        AppServices.Settings.Update(s => s with { CompletedToBottom = CompletedSwitch.IsOn });
    }

    private void OnEcoToggled(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        AppServices.Settings.Update(s => s with { SkyQuality = EcoSwitch.IsOn ? Core.Data.SkyQuality.Eco : Core.Data.SkyQuality.High });
    }

    private void OnFocusNumber(NumberBox sender, NumberBoxValueChangedEventArgs e)
    {
        if (_loading || double.IsNaN(sender.Value)) return;
        int V(NumberBox b) => (int)Math.Round(double.IsNaN(b.Value) ? b.Minimum : b.Value);
        AppServices.Settings.Update(s => s with
        {
            Focus = s.Focus with
            {
                ShortBreakMinutes = V(ShortBreakBox),
                LongBreakMinutes = V(LongBreakBox),
                LongBreakEvery = V(LongEveryBox),
                DailyGoalMinutes = V(DailyGoalBox),
            },
        });
    }

    private void OnFocusToggle(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        AppServices.Settings.Update(s => s with
        {
            Focus = s.Focus with
            {
                AutoStartBreaks = AutoBreakSwitch.IsOn,
                AutoStartFocus = AutoFocusSwitch.IsOn,
                CountPartial = PartialSwitch.IsOn,
                QuietReminders = QuietSwitch.IsOn,
                Sound = SoundSwitch.IsOn,
            },
        });
    }

    private async void OnOpenFocusSettings(object sender, RoutedEventArgs e) => await QuietHours.OpenSystemSettings();

    private async void OnOpenNotifications(object sender, RoutedEventArgs e) =>
        await Launcher.LaunchUriAsync(new Uri("ms-settings:notifications"));

    private async void OnOpenDataFolder(object sender, RoutedEventArgs e) =>
        await Launcher.LaunchFolderPathAsync(AppServices.DataFolder);
}
