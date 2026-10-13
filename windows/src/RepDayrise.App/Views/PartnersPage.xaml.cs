using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;
using RepDayrise.Core.Sharing;
using RepDayrise.Services;
using Windows.ApplicationModel.DataTransfer;

namespace RepDayrise.Views;

/// <summary>Accountability partners: publish your list, follow other people's, and pick the server.</summary>
public sealed partial class PartnersPage : Page
{
    private string? _pendingCode;

    public PartnersPage()
    {
        InitializeComponent();
        Loaded += async (_, _) =>
        {
            if (_pendingCode is { } code) { _pendingCode = null; await ShowFollowDialog(code); }
        };
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        AppServices.SharingChanged += Render;
        AppServices.DataChanged += Render;
        Render();
        await RunAsync(async () =>
        {
            var a = await AppServices.Sharing.RefreshOutgoing();
            var b = await AppServices.Sharing.RefreshFollowing();
            return a ?? b;
        });
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        AppServices.SharingChanged -= Render;
        AppServices.DataChanged -= Render;
    }

    /// <summary>Opens the follow dialog with a code from an invite link.</summary>
    public async void OpenFollow(string code)
    {
        if (XamlRoot == null) { _pendingCode = code; return; }
        await ShowFollowDialog(code);
    }

    private void Render()
    {
        var sharing = AppServices.Sharing;
        var state = sharing.State;
        var culture = CultureInfo.CurrentCulture;
        var server = SharingService.NormalizeUrl(state.ServerUrl) is { Length: > 0 } s ? s : sharing.DefaultServerUrl;
        ServerText.Text = server.Length > 0 ? server : "Not set";
        ServerBar.IsOpen = server.Length == 0 && state.Outgoing == null;

        // Your list
        var outgoing = state.Outgoing;
        NotSharingPanel.Visibility = outgoing == null ? Visibility.Visible : Visibility.Collapsed;
        SharingPanel.Visibility = outgoing == null ? Visibility.Collapsed : Visibility.Visible;
        if (outgoing == null)
        {
            if (ShareNameBox.Text.Length == 0) ShareNameBox.Text = state.DisplayName;
            StartSharingButton.IsEnabled = ShareNameBox.Text.Trim().Length > 0 && server.Length > 0;
        }
        else
        {
            CodeText.Text = FormatCode(outgoing.Code);
            SyncText.Text = outgoing.LastSyncedAt > 0 ? $"Shared as {state.DisplayName} · updated {Ago(outgoing.LastSyncedAt)}" : $"Shared as {state.DisplayName}";
            PushErrorBar.IsOpen = outgoing.LastError != null;
            PushErrorBar.Message = outgoing.LastError ?? "";

            PartnerList.Children.Clear();
            NoPartnersText.Visibility = outgoing.Partners.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            foreach (var p in outgoing.Partners) PartnerList.Children.Add(PartnerRow(p));

            SharedHabitList.Children.Clear();
            foreach (var h in AppServices.Store.GetAllHabits().Where(h => !h.Archived)) SharedHabitList.Children.Add(ShareToggle(h, outgoing));
        }

        // Following
        FollowingList.Children.Clear();
        NoFollowingCard.Visibility = state.Following.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        foreach (var f in state.Following) FollowingList.Children.Add(FollowingRow(f));
    }

    private UIElement PartnerRow(Partner p)
    {
        var culture = CultureInfo.CurrentCulture;
        var grid = new Grid { ColumnSpacing = 12 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(new PersonPicture { DisplayName = p.Name, Width = 32, Height = 32 });
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        text.Children.Add(new TextBlock { Text = p.Name });
        var joined = p.JoinedAt > 0 ? $"Joined {DateTimeOffset.FromUnixTimeMilliseconds(p.JoinedAt).LocalDateTime.ToString("MMM d", culture)}" : "";
        var seen = p.LastSeenAt is { } at ? $" · last looked {Ago(at)}" : "";
        text.Children.Add(new TextBlock { Text = joined + seen, Style = (Style)Application.Current.Resources["SecondaryTextStyle"] });
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        var remove = new Button { Content = "Remove", VerticalAlignment = VerticalAlignment.Center };
        remove.Click += async (_, _) =>
        {
            if (await Dialogs.Confirm(XamlRoot, $"Remove {p.Name}?", $"{p.Name} will stop seeing your habits. They can follow again only with a new invite.", "Remove"))
                await RunAsync(() => AppServices.Sharing.RemovePartner(p.Id));
        };
        Grid.SetColumn(remove, 2);
        grid.Children.Add(remove);
        return grid;
    }

    private static UIElement ShareToggle(Habit h, Outgoing outgoing)
    {
        var toggle = new CheckBox { IsChecked = !outgoing.ExcludedHabitIds.Contains(h.Id) };
        var label = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        var badge = new Grid { Width = 24, Height = 24 };
        badge.Children.Add(new Microsoft.UI.Xaml.Shapes.Ellipse { Fill = HabitVisuals.BrushOf(h.ColorIndex) });
        badge.Children.Add(new FontIcon { Glyph = HabitVisuals.Glyph(h.Icon), FontSize = 12, Foreground = new SolidColorBrush(Microsoft.UI.Colors.White) });
        label.Children.Add(badge);
        label.Children.Add(new TextBlock { Text = h.Name, VerticalAlignment = VerticalAlignment.Center });
        toggle.Content = label;
        toggle.Click += async (_, _) => await AppServices.Sharing.SetHabitShared(h.Id, toggle.IsChecked == true);
        return toggle;
    }

    private UIElement FollowingRow(Following f)
    {
        var detail = f.Ended ? "No longer shared with you" : f.Snapshot == null ? "Waiting for their first update" : $"Updated {Ago(f.UpdatedAt)}";
        var progressText = "";
        double percent = 0;
        if (f.Snapshot is { } snap)
        {
            var habits = snap.Habits.Select(h => h.ToHabit()).ToList();
            var logic = new HabitLogic(snap.WeekStartsMonday ? DayOfWeek.Monday : DayOfWeek.Sunday);
            var today = EpochDay.Today();
            var (done, due) = logic.DayCounts(habits, snap.Entries, today);
            percent = logic.DayProgress(habits, snap.Entries, today) * 100;
            progressText = due == 0 ? "Nothing due today" : $"{done} of {due} today";
        }

        var grid = new Grid { ColumnSpacing = 14 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(new PersonPicture { DisplayName = f.OwnerName, Width = 40, Height = 40 });
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        text.Children.Add(new TextBlock { Text = f.OwnerName, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] });
        text.Children.Add(new TextBlock { Text = detail, Style = (Style)Application.Current.Resources["SecondaryTextStyle"] });
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        if (f.Snapshot != null)
        {
            var right = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Spacing = 4, Width = 140 };
            right.Children.Add(new TextBlock { Text = progressText, HorizontalAlignment = HorizontalAlignment.Right, Style = (Style)Application.Current.Resources["SecondaryTextStyle"] });
            right.Children.Add(new ProgressBar { Value = percent });
            Grid.SetColumn(right, 2);
            grid.Children.Add(right);
        }

        var button = new Button { Content = grid, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch, Padding = new Thickness(12, 10, 12, 10) };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, f.OwnerName);
        button.Click += (_, _) => Frame.Navigate(typeof(PartnerDetailPage), f.ShareId);
        return button;
    }

    // ---- Actions ------------------------------------------------------------------------------

    private void OnShareNameChanged(object sender, TextChangedEventArgs e) => Render();

    private async void OnStartSharing(object sender, RoutedEventArgs e) =>
        await RunAsync(() => AppServices.Sharing.StartSharing(ShareNameBox.Text));

    private async void OnRotateCode(object sender, RoutedEventArgs e)
    {
        if (await Dialogs.Confirm(XamlRoot, "Make a new invite code?", "The current code will stop working. People already following you stay connected.", "New code"))
            await RunAsync(AppServices.Sharing.RotateCode);
    }

    private async void OnStopSharing(object sender, RoutedEventArgs e)
    {
        if (await Dialogs.Confirm(XamlRoot, "Stop sharing?", "Your list is deleted from the server and every partner stops seeing it.", "Stop sharing"))
            await RunAsync(AppServices.Sharing.StopSharing);
    }

    private void OnCopyInvite(object sender, RoutedEventArgs e)
    {
        if (AppServices.Sharing.State.Outgoing is not { } o) return;
        var code = FormatCode(o.Code);
        var package = new DataPackage();
        package.SetText($"Be my accountability partner on RepDayrise: {SharingService.InviteLink(o.ServerUrl, o.Code)}\n\nOr enter {code} under Partners → Follow someone.");
        Clipboard.SetContent(package);
        ErrorBar.Severity = InfoBarSeverity.Success;
        ErrorBar.Title = "Invite copied";
        ErrorBar.Message = "Paste it into a message to your partner.";
        ErrorBar.IsOpen = true;
    }

    private async void OnFollow(object sender, RoutedEventArgs e) => await ShowFollowDialog("");

    private async void OnRefresh(object sender, RoutedEventArgs e) => await RunAsync(async () =>
    {
        var a = await AppServices.Sharing.RefreshOutgoing();
        await AppServices.Sharing.Push(force: true);
        var b = await AppServices.Sharing.RefreshFollowing();
        return a ?? b;
    });

    private async void OnEditServer(object sender, RoutedEventArgs e)
    {
        var current = AppServices.Sharing.State.ServerUrl;
        var url = await Dialogs.Prompt(XamlRoot, "Sharing server", "Address", current, placeholder: "https://your-worker.workers.dev");
        if (url != null) await AppServices.Sharing.SetServerUrl(url);
    }

    private async Task ShowFollowDialog(string code)
    {
        var state = AppServices.Sharing.State;
        var panel = new StackPanel { Spacing = 12, MinWidth = 340 };
        panel.Children.Add(new TextBlock
        {
            Text = "Paste the invite your partner sent you, or type their code.",
            TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["SecondaryTextStyle"],
        });
        var codeBox = new TextBox { Header = "Invite or code", Text = code, PlaceholderText = "ABCDE-FGHJK" };
        var nameBox = new TextBox { Header = "Your name, as they will see it", Text = state.DisplayName, MaxLength = 40 };
        panel.Children.Add(codeBox);
        if (string.IsNullOrWhiteSpace(state.DisplayName)) panel.Children.Add(nameBox);

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot, Title = "Follow someone", Content = panel,
            PrimaryButtonText = "Follow", CloseButtonText = "Cancel", DefaultButton = ContentDialogButton.Primary,
            Style = (Style)Application.Current.Resources["DefaultContentDialogStyle"],
            RequestedTheme = (XamlRoot.Content as FrameworkElement)?.RequestedTheme ?? ElementTheme.Default,
        };
        void Validate() => dialog.IsPrimaryButtonEnabled = SharingService.ExtractCode(codeBox.Text).Length >= 6 && nameBox.Text.Trim().Length > 0;
        codeBox.TextChanged += (_, _) => Validate();
        nameBox.TextChanged += (_, _) => Validate();
        Validate();
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        await RunAsync(() => AppServices.Sharing.Follow(SharingService.ExtractCode(codeBox.Text), nameBox.Text));
    }

    /// <summary>Runs a sharing action with the refresh button disabled and shows any failure.</summary>
    private async Task RunAsync(Func<Task<string?>> action)
    {
        RefreshButton.IsEnabled = false;
        try
        {
            var error = await action();
            if (error != null)
            {
                ErrorBar.Severity = InfoBarSeverity.Error;
                ErrorBar.Title = "Couldn't finish that";
                ErrorBar.Message = error;
                ErrorBar.IsOpen = true;
            }
            else if (ErrorBar.Severity == InfoBarSeverity.Error)
            {
                ErrorBar.IsOpen = false;
            }
        }
        finally
        {
            RefreshButton.IsEnabled = true;
            Render();
        }
    }

    private static string FormatCode(string code)
    {
        var clean = new string(code.Where(char.IsLetterOrDigit).ToArray());
        return clean.Length == 10 ? $"{clean[..5]}-{clean[5..]}" : clean;
    }

    internal static string Ago(long epochMs)
    {
        var span = DateTimeOffset.UtcNow - DateTimeOffset.FromUnixTimeMilliseconds(epochMs);
        if (span.TotalMinutes < 1) return "just now";
        if (span.TotalHours < 1) return $"{(int)span.TotalMinutes} min ago";
        if (span.TotalDays < 1) return $"{(int)span.TotalHours} h ago";
        if (span.TotalDays < 7) return $"{(int)span.TotalDays} d ago";
        return DateTimeOffset.FromUnixTimeMilliseconds(epochMs).LocalDateTime.ToString("MMM d", CultureInfo.CurrentCulture);
    }
}
