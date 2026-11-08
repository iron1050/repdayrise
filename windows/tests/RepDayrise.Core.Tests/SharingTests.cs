using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;
using RepDayrise.Core.Sharing;

namespace RepDayrise.Core.Tests;

/// <summary>Port of the Android SharingTest.kt. The wire format must stay compatible with the Android app.</summary>
public class SharingTests
{
    private static readonly DateOnly Today = new(2026, 9, 15);

    private static readonly Habit Read = new() { Id = 1, Name = "Read", Note = "private thoughts", StartDate = Today.AddDays(-30) };
    private static readonly Habit Water = new() { Id = 2, Name = "Water", Type = HabitType.COUNT, Goal = 8.0, Unit = "glasses", StartDate = Today.AddDays(-30) };
    private static readonly Habit Retired = new() { Id = 3, Name = "Old", Archived = true, StartDate = Today.AddDays(-30) };
    private static readonly Habit Swim = new() { Id = 4, Name = "Swim", ScheduleType = ScheduleType.WEEKLY, TimesPerPeriod = 2, StartDate = Today.AddDays(-30) };

    private static readonly EntryMap Entries = new()
    {
        [1] = new() { [Today.ToEpochDay()] = 1.0, [Today.AddDays(-900).ToEpochDay()] = 1.0 },
        [2] = new() { [Today.ToEpochDay()] = 4.0, [Today.AddDays(1).ToEpochDay()] = 8.0 },
        [3] = new() { [Today.ToEpochDay()] = 1.0 },
    };

    private static Snapshot MakeSnapshot(params long[] excluded) =>
        Snapshot.Build([Read, Water, Retired, Swim], Entries, excluded.ToHashSet(), weekStartsMonday: false, today: Today);

    [Fact]
    public void SnapshotLeavesOutArchivedExcludedOldAndFutureData()
    {
        var s = MakeSnapshot(4);
        Assert.Equal([1L, 2L], s.Habits.Select(h => h.Id));
        Assert.Equal([Today.ToEpochDay()], s.Entries[1].Keys);
        Assert.Equal([Today.ToEpochDay()], s.Entries[2].Keys);
        Assert.False(s.Entries.ContainsKey(3));
    }

    [Fact]
    public void SnapshotNeverCarriesNotesOrUids()
    {
        var json = SharingJson.Encode(MakeSnapshot());
        Assert.DoesNotContain("private thoughts", json);
        Assert.DoesNotContain(Read.Uid, json);
    }

    [Fact]
    public void SnapshotSurvivesTheWireAndDrivesTheSameLogic()
    {
        var json = SharingJson.Encode(MakeSnapshot());
        var decoded = SharingJson.Decode<Snapshot>(json);
        Assert.Equal(json, SharingJson.Encode(decoded));
        var habits = decoded.Habits.Select(h => h.ToHabit()).ToList();
        Assert.Equal(ScheduleType.WEEKLY, habits.First(h => h.Id == 4).ScheduleType);
        var logic = new HabitLogic(DayOfWeek.Sunday);
        // Read done, water half done, swim due but untouched.
        Assert.Equal(0.5f, logic.DayProgress(habits, decoded.Entries, Today), 3);
        Assert.Equal((1, 3), logic.DayCounts(habits, decoded.Entries, Today));
    }

    [Fact]
    public void ReadsTheAndroidWireFormat()
    {
        // As kotlinx.serialization writes it: camelCase, doubles with ".0", map keys as strings.
        const string android = """
            {"schema":1,"weekStartsMonday":true,"habits":[{"id":5,"name":"Walk","icon":"walk","colorIndex":4,"type":"COUNT","goal":8000.0,
             "unit":"steps","scheduleType":"DAILY","daysMask":31,"timesPerPeriod":1,"startDate":20700}],"entries":{"5":{"20711":4000.0}}}
            """;
        var s = SharingJson.Decode<Snapshot>(android);
        var habit = s.Habits.Single().ToHabit();
        Assert.Equal(HabitType.COUNT, habit.Type);
        Assert.Equal(DaysMask.WEEKDAYS, habit.DaysMask);
        Assert.Equal(4000.0, s.Entries[5][20711]);
        Assert.Equal(0.5f, new HabitLogic().DayProgress([habit], s.Entries, Today), 3);
    }

    [Fact]
    public void UnknownFieldsFromANewerAppAreIgnored()
    {
        var s = SharingJson.Decode<Snapshot>("""{"schema":2,"mood":"great","habits":[{"id":7,"name":"Run","type":"SPRINT","emoji":"x"}]}""");
        Assert.Equal(HabitType.CHECK, s.Habits.Single().ToHabit().Type);
    }

    [Fact]
    public void CodesAreExtractedFromWhateverGetsPasted()
    {
        Assert.Equal("ABCDEFGHJK", SharingService.ExtractCode(" abcde-fghjk "));
        Assert.Equal("ABCDEFGHJK", SharingService.ExtractCode("dayrise://join/ABCDEFGHJK"));
        Assert.Equal("ABCDEFGHJK", SharingService.ExtractCode("Be my partner https://x.workers.dev/j/ABCDEFGHJK\n\nOr enter"));
    }

    [Fact]
    public void ServerAddressesAreTidied()
    {
        Assert.Equal("", SharingService.NormalizeUrl("  "));
        Assert.Equal("https://x.workers.dev", SharingService.NormalizeUrl(" x.workers.dev/ "));
        Assert.Equal("http://localhost:8787", SharingService.NormalizeUrl("http://localhost:8787"));
        Assert.Equal("https://x.dev/j/ABCDEFGHJK", SharingService.InviteLink("https://x.dev", "ABCDE-FGHJK"));
    }

    /// <summary>Talks to a real backend. Runs only when DAYRISE_TEST_SERVER is set, e.g. http://localhost:8787.</summary>
    [Fact]
    public async Task ClientAndServerAgreeOnTheWireFormat()
    {
        var baseUrl = Environment.GetEnvironmentVariable("DAYRISE_TEST_SERVER") ?? "";
        if (string.IsNullOrWhiteSpace(baseUrl)) return; // not configured
        var api = new SharingApi();
        var snapshotJson = SharingJson.Encode(MakeSnapshot());
        var share = await api.CreateShare(baseUrl, "Owner");
        await api.Publish(baseUrl, share.Id, share.Token, "Owner", MakeSnapshot());

        var joined = await api.Join(baseUrl, share.Code, "Partner");
        Assert.Equal(share.Id, joined.ShareId);
        Assert.Equal("Owner", joined.OwnerName);

        var feed = (await api.Feed(baseUrl, joined.ShareId, joined.Token, -1))!;
        Assert.Equal(snapshotJson, SharingJson.Encode(feed.Snapshot));
        Assert.Equal(1, feed.Version);
        Assert.Null(await api.Feed(baseUrl, joined.ShareId, joined.Token, feed.Version));

        var status = await api.Status(baseUrl, share.Id, share.Token);
        Assert.Equal(share.Code, status.Code);
        Assert.Equal(["Partner"], status.Subscribers!.Select(p => p.Name));

        var newCode = await api.RotateCode(baseUrl, share.Id, share.Token);
        Assert.NotEqual(share.Code, newCode);

        await api.RemovePartner(baseUrl, share.Id, share.Token, status.Subscribers!.Single().Id);
        var locked = await Assert.ThrowsAsync<ApiException>(() => api.Feed(baseUrl, joined.ShareId, joined.Token, -1));
        Assert.Equal(401, locked.Status);

        var again = await api.Join(baseUrl, newCode, "Partner");
        await api.Unsubscribe(baseUrl, again.Token);
        await api.DeleteShare(baseUrl, share.Id, share.Token);
        Assert.Equal(401, (await Assert.ThrowsAsync<ApiException>(() => api.Status(baseUrl, share.Id, share.Token))).Status);
    }
}
