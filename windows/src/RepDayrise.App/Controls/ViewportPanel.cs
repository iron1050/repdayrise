using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Foundation;

namespace RepDayrise.Controls;

/// <summary>
/// Scroll content wrapper that always asks for the full width it's offered. A ScrollViewer positions content by its
/// desired width, so a narrow max-width column (desired smaller than the viewport) would otherwise be drawn off
/// centre even though its layout slot is the full width. Children are laid out on top of each other, like a Grid.
/// </summary>
public sealed partial class ViewportPanel : Panel
{
    protected override Size MeasureOverride(Size availableSize)
    {
        double height = 0, width = 0;
        foreach (var child in Children)
        {
            child.Measure(availableSize);
            height = Math.Max(height, child.DesiredSize.Height);
            width = Math.Max(width, child.DesiredSize.Width);
        }
        return new Size(double.IsInfinity(availableSize.Width) ? width : availableSize.Width, height);
    }

    protected override Size ArrangeOverride(Size finalSize)
    {
        foreach (var child in Children) child.Arrange(new Rect(0, 0, finalSize.Width, Math.Max(finalSize.Height, child.DesiredSize.Height)));
        return finalSize;
    }
}
