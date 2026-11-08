namespace RepDayrise.Core.Domain;

public static class MoonPhase
{
    private const double SynodicMonth = 29.530588853;
    private const long ReferenceNewMoonEpochSeconds = 947182440L; // 2000-01-06 18:14 UTC

    /// <summary>Returns the phase in [0, 1): 0 = new moon, 0.5 = full moon.</summary>
    public static double Phase(DateOnly date)
    {
        var seconds = new DateTimeOffset(date.ToDateTime(new TimeOnly(12, 0)), TimeSpan.Zero).ToUnixTimeSeconds();
        var days = (seconds - ReferenceNewMoonEpochSeconds) / 86400.0;
        var cycles = days / SynodicMonth;
        var frac = cycles - Math.Floor(cycles);
        return frac < 0 ? frac + 1 : frac;
    }

    public static string Name(DateOnly date)
    {
        var p = Phase(date);
        return p switch
        {
            < 0.03 or > 0.97 => "New moon",
            < 0.22 => "Waxing crescent",
            < 0.28 => "First quarter",
            < 0.47 => "Waxing gibbous",
            < 0.53 => "Full moon",
            < 0.72 => "Waning gibbous",
            < 0.78 => "Last quarter",
            _ => "Waning crescent",
        };
    }
}
