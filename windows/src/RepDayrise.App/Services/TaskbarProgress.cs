using System.Runtime.InteropServices;

namespace RepDayrise.Services;

/// <summary>Shows the focus phase as a progress fill on the app's taskbar button (ITaskbarList3).</summary>
public sealed partial class TaskbarProgress(IntPtr hwnd)
{
    public enum State { None = 0, Indeterminate = 1, Normal = 2, Error = 4, Paused = 8 }

    [ComImport, Guid("ea1afb91-9e28-4b86-90e9-9e9f8a5eefaf"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ITaskbarList3
    {
        void HrInit();
        void AddTab(IntPtr hwnd);
        void DeleteTab(IntPtr hwnd);
        void ActivateTab(IntPtr hwnd);
        void SetActiveAlt(IntPtr hwnd);
        void MarkFullscreenWindow(IntPtr hwnd, [MarshalAs(UnmanagedType.Bool)] bool fullscreen);
        void SetProgressValue(IntPtr hwnd, ulong completed, ulong total);
        void SetProgressState(IntPtr hwnd, State state);
    }

    [ComImport, Guid("56fdf344-fd6d-11d0-958a-006097c9a090"), ClassInterface(ClassInterfaceType.None)]
    private class TaskbarList;

    private ITaskbarList3? _list;
    private State _state = State.None;
    private ulong _value = ulong.MaxValue;

    private ITaskbarList3? List
    {
        get
        {
            if (_list != null) return _list;
            try
            {
                _list = (ITaskbarList3)new TaskbarList();
                _list.HrInit();
            }
            catch { _list = null; }
            return _list;
        }
    }

    /// <summary>Sets the fill to <paramref name="fraction"/> (0..1) in <paramref name="state"/>, or clears it.</summary>
    public void Set(State state, double fraction = 0)
    {
        var list = List;
        if (list == null) return;
        try
        {
            if (state != _state)
            {
                list.SetProgressState(hwnd, state);
                _state = state;
                _value = ulong.MaxValue;
            }
            if (state is State.Normal or State.Paused)
            {
                var v = (ulong)Math.Round(Math.Clamp(fraction, 0, 1) * 1000);
                if (v != _value) list.SetProgressValue(hwnd, v, 1000);
                _value = v;
            }
        }
        catch { /* the taskbar may be restarting */ }
    }
}
