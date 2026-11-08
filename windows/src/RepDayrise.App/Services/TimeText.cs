namespace RepDayrise.Services;

/// <summary>How times read on screen and to a screen reader.</summary>
public static class TimeText
{
    /// <summary>m:ss, or h:mm:ss from an hour. Rounds up so a phase never shows 0:00 while still running.</summary>
    public static string Clock(long ms)
    {
        var total = (long)Math.Ceiling(ms / 1000.0);
        var h = total / 3600;
        var m = total % 3600 / 60;
        var s = total % 60;
        return h > 0 ? $"{h}:{m:00}:{s:00}" : $"{m}:{s:00}";
    }

    /// <summary>"12 minutes 5 seconds", for accessibility names.</summary>
    public static string Spoken(long ms)
    {
        var total = (long)Math.Ceiling(ms / 1000.0);
        var m = total / 60;
        var s = total % 60;
        return m > 0 ? $"{m} minute{(m == 1 ? "" : "s")} {s} second{(s == 1 ? "" : "s")}" : $"{s} second{(s == 1 ? "" : "s")}";
    }

    /// <summary>"25 minutes", "1 minute", "1 h 30 min".</summary>
    public static string Minutes(double minutes)
    {
        var m = (int)Math.Round(minutes);
        if (m >= 60) return m % 60 == 0 ? $"{m / 60} h" : $"{m / 60} h {m % 60} min";
        return m == 1 ? "1 minute" : $"{m} minutes";
    }

    /// <summary>Compact: "50 min", "2 h 5 min", "0 min".</summary>
    public static string Short(double minutes)
    {
        var m = (int)Math.Floor(minutes);
        if (m >= 60) return m % 60 == 0 ? $"{m / 60} h" : $"{m / 60} h {m % 60} min";
        return $"{m} min";
    }
}
