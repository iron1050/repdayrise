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
        var v = Package.Current.Id.Version;
        VersionText.Text = $"Version {v.Major}.{v.Minor}.{v.Build}";
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

    private async void OnOpenDataFolder(object sender, RoutedEventArgs e) =>
        await Launcher.LaunchFolderPathAsync(AppServices.DataFolder);
}
