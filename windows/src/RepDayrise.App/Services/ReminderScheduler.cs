using System.Security;
using RepDayrise.Core.Models;
using Windows.Data.Xml.Dom;
using Windows.UI.Notifications;

namespace RepDayrise.Services;

/// <summary>
/// Turns habit reminders into scheduled Windows toasts for the next week. Windows delivers them even when the
/// app is closed. Clicking one opens the habit (launch argument <c>habit=ID</c>).
/// </summary>
public sealed class ReminderScheduler
{
    private const int DaysAhead = 7;
    private const string Group = "reminders";
    private CancellationTokenSource? _pending;

    /// <summary>Reschedules soon, coalescing bursts of edits.</summary>
    public void RequestReschedule()
    {
        var cts = new CancellationTokenSource();
        Interlocked.Exchange(ref _pending, cts)?.Cancel();
        _ = Task.Delay(1500, cts.Token).ContinueWith(t => { if (!t.IsCanceled) Reschedule(); }, TaskScheduler.Default);
    }

    public void Reschedule()
    {
        try
        {
            var notifier = ToastNotificationManager.CreateToastNotifier();
            // Only ours: the focus phase-end toast lives in the same schedule and must survive a reschedule.
            foreach (var old in notifier.GetScheduledToastNotifications().Where(t => t.Group == Group)) notifier.RemoveFromSchedule(old);

            var store = AppServices.Store;
            var habits = store.GetAllHabits().Where(h => !h.Archived).ToList();
            var entries = store.GetEntries();
            var logic = AppServices.Logic;
            var now = DateTimeOffset.Now;
            var today = EpochDay.Today();

            foreach (var habit in habits)
            foreach (var reminder in habit.Reminders.Where(r => r.Enabled))
            for (var offset = 0; offset < DaysAhead; offset++)
            {
                var day = today.AddDays(offset);
                if (!DaysMask.Contains(reminder.DaysMask, day.DayOfWeek) || !logic.IsScheduledDay(habit, day)) continue;
                // A weekly/monthly habit whose goal is already met this period doesn't need a nudge.
                if (offset == 0 && !logic.IsDue(habit, entries, day)) continue;
                if (offset == 0 && logic.IsCompleted(habit, entries, day)) continue;
                var at = new DateTimeOffset(day.ToDateTime(new TimeOnly(reminder.Hour, reminder.Minute)));
                if (at <= now.AddSeconds(5)) continue;
                notifier.AddToSchedule(new ScheduledToastNotification(Toast(habit), at)
                {
                    Id = $"r{reminder.Id}d{offset}", // ids are limited to 16 characters
                    Group = Group,
                });
            }
        }
        catch (Exception e)
        {
            // Notifications can be turned off for the app; reminders are best effort.
            System.Diagnostics.Debug.WriteLine($"Reminder scheduling failed: {e}");
        }
    }

    private static XmlDocument Toast(Habit habit)
    {
        var body = habit.Type == HabitType.CHECK ? habit.ScheduleLabel() : $"Goal: {habit.GoalLabel()}";
        var xml = $"""
            <toast launch="habit={habit.Id}" scenario="reminder">
              <visual>
                <binding template="ToastGeneric">
                  <text>{SecurityElement.Escape(habit.Name)}</text>
                  <text>{SecurityElement.Escape(body)}</text>
                </binding>
              </visual>
              <actions>
                <action content="Open" arguments="habit={habit.Id}" activationType="foreground" />
                <action content="Dismiss" arguments="dismiss" activationType="system" />
              </actions>
            </toast>
            """;
        var doc = new XmlDocument();
        doc.LoadXml(xml);
        return doc;
    }
}
