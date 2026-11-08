using System.Numerics;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Input;
using Windows.UI.ViewManagement;

namespace RepDayrise.Services;

/// <summary>
/// Small, physical microinteractions on the composition thread: a spring squash when something is pressed, a pop
/// when something completes, and soft fades when parts of a page appear or leave. Everything respects the Windows
/// "Animation effects" setting.
/// </summary>
public static class Motion
{
    private static readonly UISettings Ui = new();

    public static bool Enabled
    {
        get { try { return Ui.AnimationsEnabled; } catch { return true; } }
    }

    // ---- Press: an attached property so styles can opt every button in ---------------------------

    public static readonly DependencyProperty PressProperty = DependencyProperty.RegisterAttached(
        "Press", typeof(bool), typeof(Motion), new PropertyMetadata(false, OnPressChanged));

    public static bool GetPress(DependencyObject d) => (bool)d.GetValue(PressProperty);
    public static void SetPress(DependencyObject d, bool value) => d.SetValue(PressProperty, value);

    private static void OnPressChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not UIElement el || e.NewValue is not true) return;
        // handledEventsToo: buttons mark pointer events handled before we'd see them.
        el.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler((s, _) => Squash((UIElement)s, 0.96f)), true);
        el.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler((s, _) => Squash((UIElement)s, 1f)), true);
        el.AddHandler(UIElement.PointerCaptureLostEvent, new PointerEventHandler((s, _) => Squash((UIElement)s, 1f)), true);
        el.AddHandler(UIElement.PointerExitedEvent, new PointerEventHandler((s, _) => Squash((UIElement)s, 1f)), true);
    }

    private static void Squash(UIElement el, float scale)
    {
        if (!Enabled) return;
        var visual = ElementCompositionPreview.GetElementVisual(el);
        var size = el.ActualSize;
        visual.CenterPoint = new Vector3(size.X / 2, size.Y / 2, 0);
        var spring = visual.Compositor.CreateSpringVector3Animation();
        spring.FinalValue = new Vector3(scale, scale, 1);
        spring.DampingRatio = scale < 1 ? 0.9f : 0.55f;
        spring.Period = TimeSpan.FromMilliseconds(scale < 1 ? 40 : 55);
        visual.StartAnimation("Scale", spring);
    }

    /// <summary>A quick overshoot, e.g. when a habit gets checked off.</summary>
    public static void Pop(UIElement el)
    {
        if (!Enabled) return;
        var visual = ElementCompositionPreview.GetElementVisual(el);
        var size = el.ActualSize;
        visual.CenterPoint = new Vector3(size.X / 2, size.Y / 2, 0);
        visual.Scale = new Vector3(0.82f, 0.82f, 1);
        var spring = visual.Compositor.CreateSpringVector3Animation();
        spring.FinalValue = Vector3.One;
        spring.DampingRatio = 0.45f;
        spring.Period = TimeSpan.FromMilliseconds(60);
        visual.StartAnimation("Scale", spring);
    }

    // ---- Appear / leave ---------------------------------------------------------------------------

    /// <summary>
    /// Gives <paramref name="el"/> implicit show/hide animations: it fades and drifts <paramref name="rise"/> px
    /// when its Visibility changes, instead of popping.
    /// </summary>
    public static void FadeOnVisibility(UIElement el, float rise = 8)
    {
        var visual = ElementCompositionPreview.GetElementVisual(el);
        var c = visual.Compositor;
        ElementCompositionPreview.SetIsTranslationEnabled(el, true);

        var show = c.CreateAnimationGroup();
        var fadeIn = c.CreateScalarKeyFrameAnimation();
        fadeIn.Target = "Opacity";
        fadeIn.InsertKeyFrame(0, 0);
        fadeIn.InsertKeyFrame(1, 1, c.CreateCubicBezierEasingFunction(new Vector2(0.2f, 0), new Vector2(0, 1)));
        fadeIn.Duration = TimeSpan.FromMilliseconds(260);
        var slideIn = c.CreateVector3KeyFrameAnimation();
        slideIn.Target = "Translation";
        slideIn.InsertKeyFrame(0, new Vector3(0, rise, 0));
        slideIn.InsertKeyFrame(1, Vector3.Zero, c.CreateCubicBezierEasingFunction(new Vector2(0.2f, 0), new Vector2(0, 1)));
        slideIn.Duration = TimeSpan.FromMilliseconds(320);
        show.Add(fadeIn);
        show.Add(slideIn);

        var hide = c.CreateAnimationGroup();
        var fadeOut = c.CreateScalarKeyFrameAnimation();
        fadeOut.Target = "Opacity";
        fadeOut.InsertKeyFrame(1, 0);
        fadeOut.Duration = TimeSpan.FromMilliseconds(140);
        var slideOut = c.CreateVector3KeyFrameAnimation();
        slideOut.Target = "Translation";
        slideOut.InsertKeyFrame(1, new Vector3(0, -rise / 2, 0));
        slideOut.Duration = TimeSpan.FromMilliseconds(140);
        hide.Add(fadeOut);
        hide.Add(slideOut);

        if (Enabled)
        {
            ElementCompositionPreview.SetImplicitShowAnimation(el, show);
            ElementCompositionPreview.SetImplicitHideAnimation(el, hide);
        }
    }

    /// <summary>Lets an element's Translation glide (spring) to wherever it's set next.</summary>
    public static void GlideTranslation(UIElement el)
    {
        ElementCompositionPreview.SetIsTranslationEnabled(el, true);
        if (!Enabled) return;
        var visual = ElementCompositionPreview.GetElementVisual(el);
        var c = visual.Compositor;
        var implicitAnimations = c.CreateImplicitAnimationCollection();
        var glide = c.CreateVector3KeyFrameAnimation();
        glide.Target = "Translation";
        glide.InsertExpressionKeyFrame(1, "this.FinalValue", c.CreateCubicBezierEasingFunction(new Vector2(0.25f, 0.9f), new Vector2(0.3f, 1)));
        glide.Duration = TimeSpan.FromMilliseconds(320);
        implicitAnimations["Translation"] = glide;
        visual.ImplicitAnimations = implicitAnimations;
    }

    /// <summary>Crossfades text in place: fades out, swaps, fades in.</summary>
    public static void SwapText(Microsoft.UI.Xaml.Controls.TextBlock text, string value)
    {
        if (text.Text == value) return;
        if (!Enabled || string.IsNullOrEmpty(text.Text)) { text.Text = value; return; }
        var visual = ElementCompositionPreview.GetElementVisual(text);
        var c = visual.Compositor;
        var batch = c.CreateScopedBatch(CompositionBatchTypes.Animation);
        var outAnim = c.CreateScalarKeyFrameAnimation();
        outAnim.InsertKeyFrame(1, 0);
        outAnim.Duration = TimeSpan.FromMilliseconds(90);
        visual.StartAnimation("Opacity", outAnim);
        batch.End();
        batch.Completed += (_, _) =>
        {
            text.Text = value;
            var inAnim = c.CreateScalarKeyFrameAnimation();
            inAnim.InsertKeyFrame(0, 0);
            inAnim.InsertKeyFrame(1, 1);
            inAnim.Duration = TimeSpan.FromMilliseconds(160);
            visual.StartAnimation("Opacity", inAnim);
        };
    }
}
