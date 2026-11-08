# RepDayrise for Windows

A native Windows 11 version of RepDayrise, built for **focus sessions** at the PC. It is written in C# with WinUI 3 (Windows App SDK) on .NET 10, and packaged as an MSIX. Once installed, it appears in Start like any other app, and you remove it by right-clicking it there and choosing **Uninstall**. That also deletes its data.

It shares the Android app's shader-drawn sunrise sky, set in a clean, native Windows 11 frame: Segoe UI Variable, Mica, title-bar tabs and settings cards, with small spring microinteractions. It covers:

- **Focus sessions.** Countdowns with optional short and long breaks, which can be tagged to a timer habit.
- **Habits.** Check, count and timer habits on daily, weekly or monthly schedules, with streaks, groups and archive.
- **Progress.** The day's sky with dashboard tiles, focus stats, and the year graph.
- **Reminders.**
- **Accountability partners.** These use the same sharing server as Android, so a list shared from Windows can be followed from Android, and the other way round.

Health Connect has no Windows counterpart and is left out. Widgets are planned; see below.

## Layout

```
src/RepDayrise.Core/        Models, streak rules (HabitLogic), the focus engine, SQLite store, settings, sharing client. No UI.
src/RepDayrise.App/         The WinUI 3 app: window, pages, the GPU sky, notifications, MSIX manifest and icons.
tests/RepDayrise.Core.Tests xUnit tests, including ports of the Android HabitLogicTest and SharingTest.
scripts/                    New-DevCert.ps1 (one-time signing setup) and Install.ps1 (build + install).
```

## Requirements

- Windows 10 2004 or later; Windows 11 for the full look
- .NET 10 SDK: `winget install Microsoft.DotNet.SDK.10`
- A DirectX 11 GPU for the shader sky. Without one, a simpler CPU-drawn sky is used.

Visual Studio is optional. It adds the XAML designer and Hot Reload, but everything here builds with the .NET CLI.

## Install on this PC

```powershell
cd windows
.\scripts\New-DevCert.ps1   # once: creates a local signing certificate and trusts it (one admin prompt)
.\scripts\Install.ps1       # builds a signed MSIX and installs or updates it
```

Run `Install.ps1` again after changes. Each build gets a newer version, so it updates in place and keeps your habits and sessions. `-SharingUrl https://…` bakes in a default sharing server, and `-NoInstall` only builds the package (under `windows/AppPackages`).

The package is self-contained (it carries .NET and the Windows App SDK runtime), so it installs on any PC that trusts the certificate. To install it elsewhere, import the certificate's public half into **Local Machine › Trusted People** on that PC first.

The build prints a warning that `mspdbcmf.exe` could not be found. That tool only makes a separate debug-symbols package for Store uploads, which a sideload does not use.

## Develop

```powershell
dotnet test tests/RepDayrise.Core.Tests
dotnet build src/RepDayrise.App -p:Platform=x64
```

Set `DAYRISE_TEST_SERVER=http://localhost:8787` (with `npm run dev` running in `backend/`) to also run the client-to-server wire test. Set `DAYRISE_SKY=cpu` to force the CPU sky.

Data lives in the package's `LocalState` folder:

- `dayrise.db` (SQLite, schema v2)
- `settings.json`
- `sharing.json`, which holds bearer tokens and is kept apart from the database for that reason

**Settings › Open data folder** opens it.

## Focus sessions

`Core/Domain/FocusEngine.cs` is a pure state machine for **focus → break → focus…**:

- A long break comes every few focus phases.
- You can pause, resume, skip, stop, or add five minutes.
- Once a focus phase ends, a bonus focus phase can follow it.

`Core/Focus/FocusService.cs` runs the engine against the store:

- **The run survives a restart.** It is saved in SQLite, so the app can close mid-session. A future widget process reading the same database sees the same session.
- **Phase ends are recorded at their planned end.** A phase only starts by itself if the app notices the previous one ending within a minute. Closing the app or sleeping the PC therefore never invents focus time; the next phase waits for you instead.
- **Sessions are logged and credited to habits.** Finished sessions go to `focus_sessions`, which is sync-ready (uid, habitUid, soft deletes). A session tagged to a timer habit adds its minutes to that day's entry, exactly once, even if it's replayed after a crash. Deleting a session or moving it to another habit in the session history moves its minutes too.

**Notifications.** Phase ends are scheduled toasts, so they arrive even when the app is closed. Their buttons are *Start break*, *+5 min*, *Skip* and *Stop*. While the app is open, a heartbeat keeps several things up to date:

- a live timer in the title bar when you're on another tab
- the window title (shown in Alt+Tab and on the taskbar thumbnail)
- the taskbar progress fill

**Do not disturb.** Windows doesn't let ordinary apps switch on Windows Focus; that API is limited to partners Microsoft approves. So RepDayrise does what it can:

- It holds its own habit reminders until the focus phase ends.
- It shows whether Windows Focus is on, with a shortcut to turn it on.
- It plays a soft chime at the end of a phase when Windows Focus has muted the toast.

## Notes on the design

- **The sky.** `Controls/Sky/SunriseShader.cs` is a line-for-line port of the Android AGSL shader (`SunriseShader.kt`), written in C# and compiled to a Direct2D pixel shader by ComputeSharp at build time. It is drawn by a Win2D `CanvasAnimatedControl` with the moon and a scrim on top. Its palette lives in Core (`Domain/SkyPalette.cs`) so widgets can share it.
  - It runs at up to 30 fps, keeps its render target near 1.5 MP, and slows down when the window is in the background.
  - It draws a single frame when Windows animations are off or battery saver is on, and stops when minimised.
  - **Settings › Eco sky** lowers the frame rate further.
  - On the Focus page, the sun rises through each focus phase.
- **The look.** Quiet and native, so the sky can be the one expressive thing. `Styles/Typography.xaml` uses Segoe UI Variable, which is hinted for the screen and stays crisp at 100% scaling, unlike web fonts under WinUI's greyscale text rendering. `Colors.xaml` uses Mica with Windows 11 layer fills and one sunrise accent; `Controls.xaml` holds the buttons and grouped lists. `Services/Motion.cs` adds spring press feedback, completion pops and fades, all following the Windows animation setting. Navigation is a `SelectorBar` in the WinUI `TitleBar`. The habit palette and icon keys match Android (`Services/HabitVisuals.cs`).
- **Reminders.** These are scheduled toasts for the coming week, rebuilt whenever habits change and at launch. Rescheduling only touches the reminders group, so the focus toast is never cleared by accident.
- **Single instance.** `Program.cs` hands a second launch to the open window. That covers Start, a `dayrise://join/CODE` link, a reminder, and a focus toast button.

## Ready for sync

Syncing your own habits between the phone and this PC is planned but not built. The Windows store is already shaped for it:

- Synced rows carry a GUID `uid` and an `updatedAt`.
- Deletes are soft (`deletedAt`).
- Cleared entries stay as zero-value tombstones.
- Focus sessions record the habit's uid.
- Every write goes through `HabitStore`.

A later sync backend can then merge changes without migrating data. The Android Room schema would need the same columns.

## Adding Windows 11 widgets later

The pieces are in place:

- Widgets require a packaged app, which this is, and the `Microsoft.WindowsAppSDK` package already brings in `Microsoft.WindowsAppSDK.Widgets`.
- `AppServices.InitializeCore()` sets up the store, settings, sharing and focus without a window.
- `Program.Main` marks where a widget launch should branch off.

To add widgets (a focus timer with Start/Pause, plus the Android ones: Sunrise, Today's habits, Dashboard, Rhythm):

1. **Write the provider.** Add a class that implements `Microsoft.Windows.Widgets.Providers.IWidgetProvider` (`CreateWidget`, `DeleteWidget`, `OnActionInvoked`, `OnWidgetContextChanged`, `Activate`, `Deactivate`). Read data through `RepDayrise.Core`. A focus widget's buttons can call `FocusService` directly.
2. **Register it as an out-of-process COM server.** In `Program.Main`, when launched with `-RegisterProcessAsComServer`, call `AppServices.InitializeCore()`, register the class factory with `CoRegisterClassObject`, and keep the process alive while widgets are active.
3. **Declare it in `Package.appxmanifest`.** Add a `com:ExeServer` for the provider's CLSID, plus a `uap3:AppExtension` named `com.microsoft.windows.widgets` with a `WidgetProvider` definition per widget (sizes, display name, icons, screenshots).
4. **Describe each widget as an Adaptive Card template with a data JSON.** Widgets can't run shaders, so render the sky to an image with Win2D's `CanvasRenderTarget` and the same `SunriseShader`.
5. **Push fresh data.** Call `WidgetManager.GetDefault().UpdateWidget(...)` whenever `HabitStore.Changed` fires, the same hook that already drives sharing and reminders.
