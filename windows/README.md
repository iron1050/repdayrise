# RepDayrise for Windows

A native Windows 11 version of RepDayrise. It is written in C# with WinUI 3 (Windows App SDK) on .NET 10, and packaged as an MSIX. Once installed, it appears in Start like any other app, and you remove it by right-clicking it there and choosing **Uninstall**. That also deletes its data.

It covers the core of the Android app: check, count and timer habits, daily, weekly and monthly schedules, streaks, the month calendar and year graph, groups, archive, reminders, and accountability partners on the same sharing server. A list shared from Windows can be followed from Android, and the other way round. Health Connect has no Windows counterpart and is left out. Widgets are planned; see below.

## Layout

```
src/RepDayrise.Core/        Models, streak rules (HabitLogic), SQLite store, settings, sharing client. No UI.
src/RepDayrise.App/         The WinUI 3 app: window, pages, controls, reminders, MSIX manifest and icons.
tests/RepDayrise.Core.Tests xUnit tests, including ports of the Android HabitLogicTest and SharingTest.
scripts/                    New-DevCert.ps1 (one-time signing setup) and Install.ps1 (build + install).
```

## Requirements

- Windows 10 2004 or later; Windows 11 for Mica and the full look
- .NET 10 SDK: `winget install Microsoft.DotNet.SDK.10`

Visual Studio is optional. It adds the XAML designer and Hot Reload, but everything here builds with the .NET CLI.

## Install on this PC

```powershell
cd windows
.\scripts\New-DevCert.ps1   # once: creates a local signing certificate and trusts it (one admin prompt)
.\scripts\Install.ps1       # builds a signed MSIX and installs or updates it
```

Run `Install.ps1` again after changes. Each build gets a newer version, so it updates in place and keeps your habits. `-SharingUrl https://…` bakes in a default sharing server, and `-NoInstall` only builds the package (under `windows/AppPackages`).

The package is self-contained (it carries .NET and the Windows App SDK runtime), so it installs on any PC that trusts the certificate. To install it elsewhere, import the certificate's public half into **Local Machine › Trusted People** on that PC first.

The build prints a warning that `mspdbcmf.exe` could not be found. That tool only makes a separate debug-symbols package for Store uploads, which a sideload does not use.

## Develop

```powershell
dotnet test tests/RepDayrise.Core.Tests
dotnet build src/RepDayrise.App -p:Platform=x64
```

Set `DAYRISE_TEST_SERVER=http://localhost:8787` (with `npm run dev` running in `backend/`) to also run the client-to-server wire test.

Data lives in the package's `LocalState` folder: `dayrise.db` (SQLite), `settings.json`, and `sharing.json`. The last one holds bearer tokens and is kept apart from the database for that reason. **Settings › Open data folder** opens it.

## Notes on the design

- **Fluent first.** Mica, the WinUI `TitleBar`, `NavigationView`, cards, `InfoBar`, `ContentDialog`, Segoe Fluent Icons. The system accent is replaced by Sunrise orange (`Styles/Theme.xaml`), and the habit palette and icon keys match Android (`Services/HabitVisuals.cs`).
- **The sky.** `Controls/SunriseSky.cs` ports `Sky.kt`: the same colour keyframes, twinkling stars, moon phase, rising sun and drifting clouds.
- **Reminders.** These are scheduled toasts for the coming week, rebuilt whenever habits change and at launch. Windows delivers them with the app closed, and clicking one opens the habit.
- **Single instance.** `Program.cs` hands a second launch (from Start, a `dayrise://join/CODE` link or a reminder) to the open window.

## Ready for sync

Syncing your own habits between the phone and this PC is planned but not built. The Windows store is already shaped for it: synced rows carry a GUID `uid` and `updatedAt`, deletes are soft (`deletedAt`), cleared entries stay as zero-value tombstones, and every write goes through `HabitStore`. A later sync backend can then merge changes without migrating data. The Android Room schema would need the same columns.

## Adding Windows 11 widgets later

The pieces are already in place. Widgets require a packaged app, which this is, and the `Microsoft.WindowsAppSDK` package already brings in `Microsoft.WindowsAppSDK.Widgets`. To add the four Android widgets (Sunrise, Today's habits, Dashboard, Rhythm):

1. Add a class that implements `Microsoft.Windows.Widgets.Providers.IWidgetProvider` (`CreateWidget`, `DeleteWidget`, `OnActionInvoked`, `OnWidgetContextChanged`, `Activate`, `Deactivate`). Read data through `RepDayrise.Core` (`HabitStore`, `HabitLogic`), exactly as the pages do.
2. Register it as an out-of-process COM server. In `Program.Main`, when launched with `-RegisterProcessAsComServer`, register the class factory with `CoRegisterClassObject` and keep the process alive while widgets are active.
3. Declare it in `Package.appxmanifest`: a `com:ExeServer` for the provider's CLSID, plus a `uap3:AppExtension` named `com.microsoft.windows.widgets` with a `WidgetProvider` definition per widget (sizes, display name, icons, screenshots).
4. Describe each widget as an Adaptive Card template with a data JSON. The habit list and progress map naturally onto `Container`/`ColumnSet`, and checking off a habit becomes an `Action.Execute` handled in `OnActionInvoked`.
5. Push fresh data with `WidgetManager.GetDefault().UpdateWidget(...)` from `AppServices` whenever `HabitStore.Changed` fires, the same hook that already drives sharing and reminders.
