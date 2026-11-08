using System.Security;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Focus;
using RepDayrise.Core.Models;
using Windows.Data.Xml.Dom;
using Windows.UI.Notifications;

namespace RepDayrise.Services;

/// <summary>
/// The phase-end notification, as a scheduled toast so Windows delivers it even when the app is closed.
/// Its buttons carry <c>focus=…</c> arguments that <see cref="App"/> hands to the focus service.
/// </summary>
public sealed class FocusNotifier : IFocusAlarm
{
    private const string Id = "focus";
    private const string Group = "focus";

    public void Schedule(FocusAlarm alarm)
    {
        try
        {
            var notifier = ToastNotificationManager.CreateToastNotifier();
            RemoveScheduled(notifier);
            var at = DateTimeOffset.FromUnixTimeMilliseconds(alarm.At);
            if (at <= DateTimeOffset.Now.AddSeconds(1)) return;
            notifier.AddToSchedule(new ScheduledToastNotification(Toast(alarm), at) { Id = Id, Group = Group });
        }
        catch (Exception e)
        {
            System.Diagnostics.Debug.WriteLine($"Focus alarm scheduling failed: {e}"); // notifications may be off for the app
        }
    }

    public void Cancel()
    {
        try
        {
            RemoveScheduled(ToastNotificationManager.CreateToastNotifier());
            ToastNotificationManager.History.Remove(Id, Group); // a finished-phase toast still on screen is stale now
        }
        catch (Exception e)
        {
            System.Diagnostics.Debug.WriteLine($"Focus alarm cancel failed: {e}");
        }
    }

    private static void RemoveScheduled(ToastNotifier notifier)
    {
        foreach (var t in notifier.GetScheduledToastNotifications().Where(t => t.Group == Group)) notifier.RemoveFromSchedule(t);
    }

    private static XmlDocument Toast(FocusAlarm a)
    {
        var habit = AppServices.Store.GetFocusRun()?.HabitId is long id ? AppServices.Store.GetHabit(id)?.Name : null;
        var nextLength = Minutes(a.NextMs);
        string title, body;
        var actions = new List<(string Label, string Arg)>();

        if (a.Ending == FocusPhase.FOCUS)
        {
            title = "Focus complete";
            var what = habit != null ? $"Nice work on {habit}." : "Nice work.";
            if (a.Next == null)
            {
                body = what;
                actions.Add(("+5 min", "focus=extend5"));
            }
            else if (a.NextStartsByItself)
            {
                body = $"{what} Your {nextLength} {BreakName(a.Next.Value)} has started.";
                actions.Add(("Skip break", "focus=skip"));
                actions.Add(("Stop", "focus=stop"));
            }
            else
            {
                body = $"{what} Time for a {nextLength} {BreakName(a.Next.Value)}.";
                actions.Add(("Start break", "focus=resume"));
                actions.Add(("+5 min", "focus=extend5"));
                actions.Add(("Stop", "focus=stop"));
            }
        }
        else
        {
            title = "Break's over";
            if (a.NextStartsByItself)
            {
                body = $"Your next {nextLength} focus has started.";
                actions.Add(("Pause", "focus=pause"));
                actions.Add(("Stop", "focus=stop"));
            }
            else
            {
                body = $"Ready for another {nextLength} of focus?";
                actions.Add(("Start focus", "focus=resume"));
                actions.Add(("Stop", "focus=stop"));
            }
        }

        var buttons = string.Concat(actions.Select(x =>
            $"""<action content="{SecurityElement.Escape(x.Label)}" arguments="{x.Arg}" activationType="foreground" />"""));
        var xml = $"""
            <toast launch="focus=open" scenario="reminder">
              <visual>
                <binding template="ToastGeneric">
                  <text>{SecurityElement.Escape(title)}</text>
                  <text>{SecurityElement.Escape(body)}</text>
                </binding>
              </visual>
              <audio src="ms-winsoundevent:Notification.Reminder" />
              <actions>{buttons}</actions>
            </toast>
            """;
        var doc = new XmlDocument();
        doc.LoadXml(xml);
        return doc;
    }

    private static string BreakName(FocusPhase p) => p == FocusPhase.LONG_BREAK ? "long break" : "break";

    private static string Minutes(long ms) => $"{Math.Max(1, (int)Math.Round(ms / 60000.0))}-minute";
}
