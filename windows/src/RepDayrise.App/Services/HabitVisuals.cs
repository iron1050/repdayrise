using Microsoft.UI;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace RepDayrise.Services;

/// <summary>Habit colours and icons. Icon keys match the Android catalogue (HabitIcons.kt) so shared lists render the same.</summary>
public static class HabitVisuals
{
    /// <summary>Palette used for habits, same order as Android's HabitColors.</summary>
    public static readonly Color[] Palette =
    [
        Hex(0xFF6B6B), // coral
        Hex(0xFF8C42), // orange
        Hex(0xFFB020), // amber
        Hex(0xF2D14B), // yellow
        Hex(0x4CC28E), // green
        Hex(0x2BB5B8), // teal
        Hex(0x4FA3E3), // sky
        Hex(0x5B7CFA), // blue
        Hex(0x7C6CF0), // indigo
        Hex(0xA86EF5), // purple
        Hex(0xF06AA8), // pink
        Hex(0xE8536F), // rose
    ];

    public static readonly Color Sunrise = Hex(0xF2803E);

    public static Color ColorOf(int index) => Palette[((index % Palette.Length) + Palette.Length) % Palette.Length];
    public static SolidColorBrush BrushOf(int index) => new(ColorOf(index));
    public static Color WithAlpha(Color c, double alpha) => Color.FromArgb((byte)Math.Round(255 * Math.Clamp(alpha, 0, 1)), c.R, c.G, c.B);

    /// <summary>Android icon key -> Segoe Fluent Icons glyph. Where Fluent has no exact match the closest shape is used.</summary>
    private static readonly (string Key, string Glyph)[] Icons =
    [
        ("sun", ""), ("sunrise", ""), ("moon", ""), ("star", ""), ("heart", ""),
        ("bolt", ""), ("flame", ""), ("check", ""), ("target", ""), ("trophy", ""),
        ("flag", ""), ("smile", ""), ("water", ""), ("coffee", ""), ("food", ""),
        ("cook", ""), ("apple", ""), ("nosugar", ""), ("nodrink", ""), ("nosmoke", ""),
        ("pill", ""), ("vitamin", ""), ("run", ""), ("walk", ""), ("bike", ""),
        ("hike", ""), ("swim", ""), ("gym", ""), ("yoga", ""), ("stretch", ""),
        ("soccer", ""), ("basketball", ""), ("tennis", ""), ("heartbeat", ""), ("scale", ""),
        ("sleep", ""), ("bed", ""), ("shower", ""), ("bath", ""), ("skin", ""),
        ("spa", ""), ("leaf", ""), ("plant", ""), ("pet", ""), ("book", ""),
        ("study", ""), ("journal", ""), ("write", ""), ("draw", ""), ("brush", ""),
        ("palette", ""), ("music", ""), ("piano", ""), ("headphones", ""), ("podcast", ""),
        ("camera", ""), ("language", ""), ("brain", ""), ("idea", ""), ("code", ""),
        ("work", ""), ("school", ""), ("news", ""), ("mail", ""), ("inbox", ""),
        ("call", ""), ("family", ""), ("kind", ""), ("nophone", ""), ("game", ""),
        ("money", ""), ("wallet", ""), ("shop", ""), ("clean", ""), ("laundry", ""),
        ("kitchen", ""), ("timer", ""), ("cake", ""), ("party", ""),
    ];

    private static readonly Dictionary<string, string> GlyphByKey = Icons.ToDictionary(i => i.Key, i => i.Glyph);

    public static string Glyph(string key) => GlyphByKey.GetValueOrDefault(key, "");

    /// <summary>Choices for the icon picker: one key per distinct glyph so the grid has no visual duplicates.</summary>
    public static readonly IReadOnlyList<IconChoice> Choices =
        Icons.GroupBy(i => i.Glyph).Select(g => new IconChoice(g.First().Key, g.Key)).ToList();

    private static Color Hex(uint rgb) => Color.FromArgb(0xFF, (byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb);
}

public sealed record IconChoice(string Key, string Glyph);

public sealed record ColorChoice(int Index)
{
    public SolidColorBrush Brush => HabitVisuals.BrushOf(Index);
}
