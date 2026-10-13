using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using RepDayrise.Core.Models;

namespace RepDayrise.Services;

/// <summary>Fluent content dialogs shared by the pages.</summary>
public static class Dialogs
{
    private static ContentDialog Make(XamlRoot root, string title, object content, string primary, string? close = "Cancel")
    {
        var dialog = new ContentDialog
        {
            XamlRoot = root,
            Title = title,
            Content = content,
            PrimaryButtonText = primary,
            DefaultButton = ContentDialogButton.Primary,
            Style = (Style)Application.Current.Resources["DefaultContentDialogStyle"],
        };
        if (close != null) dialog.CloseButtonText = close;
        // Dialogs live in a popup layer; carry over the in-app theme choice.
        if (root.Content is FrameworkElement fe) dialog.RequestedTheme = fe.RequestedTheme;
        return dialog;
    }

    public static async Task<bool> Confirm(XamlRoot root, string title, string message, string action)
    {
        var dialog = Make(root, title, new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap }, action);
        return await dialog.ShowAsync() == ContentDialogResult.Primary;
    }

    public static async Task Message(XamlRoot root, string title, string message) =>
        await Make(root, title, new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap }, "OK", close: null).ShowAsync();

    /// <summary>Asks for a line of text. Returns null when cancelled.</summary>
    public static async Task<string?> Prompt(XamlRoot root, string title, string label, string initial = "", string action = "Save", string? placeholder = null)
    {
        var box = new TextBox { Header = label, Text = initial, PlaceholderText = placeholder ?? "", MinWidth = 320 };
        var dialog = Make(root, title, box, action);
        box.TextChanged += (_, _) => dialog.IsPrimaryButtonEnabled = box.Text.Trim().Length > 0;
        dialog.IsPrimaryButtonEnabled = box.Text.Trim().Length > 0;
        box.Loaded += (_, _) => { box.Focus(FocusState.Programmatic); box.SelectAll(); };
        return await dialog.ShowAsync() == ContentDialogResult.Primary ? box.Text.Trim() : null;
    }

    /// <summary>Sets the exact value for a count or timer habit on a day. Returns null when cancelled.</summary>
    public static async Task<double?> SetValue(XamlRoot root, Habit habit, DateOnly date, double current)
    {
        var isTimer = habit.Type == HabitType.TIMER;
        var box = new NumberBox
        {
            Header = isTimer ? "Minutes" : string.IsNullOrWhiteSpace(habit.Unit) ? "Count" : char.ToUpper(habit.Unit[0]) + habit.Unit[1..],
            Value = Math.Round(current, 1),
            Minimum = 0,
            SmallChange = 1,
            LargeChange = isTimer ? 10 : 5,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline,
            ValidationMode = NumberBoxValidationMode.InvalidInputOverwritten,
            MinWidth = 280,
        };
        var panel = new StackPanel { Spacing = 8 };
        panel.Children.Add(new TextBlock
        {
            Text = $"{date:dddd, MMMM d} · goal {habit.GoalLabel()}",
            Style = (Style)Application.Current.Resources["SecondaryTextStyle"],
        });
        panel.Children.Add(box);
        var dialog = Make(root, habit.Name, panel, "Save");
        box.Loaded += (_, _) => box.Focus(FocusState.Programmatic);
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return null;
        return double.IsNaN(box.Value) ? 0 : Math.Max(box.Value, 0);
    }

    /// <summary>First run: a short welcome with the option to start from the same starter habits as Android.</summary>
    public static async Task ShowOnboarding(XamlRoot root)
    {
        var panel = new StackPanel { Spacing = 12, MaxWidth = 420 };
        panel.Children.Add(new TextBlock
        {
            Text = "Finish your habits and the sun rises. Each day starts at night, and every habit you complete brings the morning a little closer.",
            TextWrapping = TextWrapping.Wrap,
        });
        panel.Children.Add(new TextBlock
        {
            Text = "Start with a few examples (a morning walk, water and reading), or begin with an empty list.",
            TextWrapping = TextWrapping.Wrap,
            Style = (Style)Application.Current.Resources["SecondaryTextStyle"],
        });
        var dialog = Make(root, "Welcome to RepDayrise", panel, "Add starter habits", close: null);
        dialog.SecondaryButtonText = "Start empty";
        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.Primary)
        {
            var store = AppServices.Store;
            store.SaveHabit(new Habit { Name = "Morning walk", Icon = "walk", ColorIndex = 4, Type = HabitType.TIMER, Goal = 20, Unit = "min" });
            store.SaveHabit(new Habit { Name = "Drink water", Icon = "water", ColorIndex = 6, Type = HabitType.COUNT, Goal = 8, Unit = "glasses" });
            store.SaveHabit(new Habit { Name = "Read", Icon = "book", ColorIndex = 8, Type = HabitType.CHECK });
        }
        AppServices.Settings.Update(s => s with { OnboardingDone = true });
    }
}
