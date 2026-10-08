package com.rongji.screentranslate;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenGeometryTest {
    @Test public void transparentLayerSubtractsStatusBarOriginWithoutShrinkingCapture() {
        ScreenGeometry.Transform transform = new ScreenGeometry.Transform(1080, 2400, 1080, 2400, 0, 72);
        ScreenGeometry.Box paragraph = transform.toView(new ScreenGeometry.Box(180, 400, 1000, 650));
        assertEquals(1f, transform.scale, 0f);
        assertEquals(180f, paragraph.left, 0f); assertEquals(328f, paragraph.top, 0f);
        assertEquals(1000f, paragraph.right, 0f); assertEquals(578f, paragraph.bottom, 0f);
    }
    @Test public void downsampledScreenLayerRetainsPhysicalPositionWithSideCutout() {
        ScreenGeometry.Transform transform = new ScreenGeometry.Transform(2400, 1080, 3200, 1440, 96, 0);
        ScreenGeometry.Box text = transform.toView(new ScreenGeometry.Box(600, 150, 1500, 300));
        assertEquals(704f, text.left, .001f); assertEquals(200f, text.top, .001f);
        assertEquals(1904f, text.right, .001f); assertEquals(400f, text.bottom, .001f);
    }
    @Test public void nonIntegerDensityReverseDragPreservesScreenshotCoordinates() {
        ScreenGeometry.Transform transform = new ScreenGeometry.Transform(1080, 2400, 393, 720);
        ScreenGeometry.Box display = transform.toView(new ScreenGeometry.Box(123, 456, 987, 1876));
        ScreenGeometry.Box selected = transform.dragToPixels(display.right, display.bottom, display.left, display.top);
        assertTrue(Math.abs(selected.left - 123) <= 1);
        assertTrue(Math.abs(selected.top - 456) <= 1);
        assertTrue(Math.abs(selected.right - 987) <= 1);
        assertTrue(Math.abs(selected.bottom - 1876) <= 1);
        assertEquals(69f, transform.offsetX * 2, .001f);
    }
    @Test public void selectionClampsToSnapshotWhenDraggingOutsideViewport() {
        ScreenGeometry.Transform transform = new ScreenGeometry.Transform(1080, 2400, 393, 720);
        ScreenGeometry.Box selected = transform.dragToPixels(-50, -100, 600, 900);
        assertEquals(0f, selected.left, 0f); assertEquals(0f, selected.top, 0f);
        assertEquals(1080f, selected.right, 0f); assertEquals(2400f, selected.bottom, 0f);
    }
    @Test public void singleAppLandscapeCaptureLetterboxesWithoutStretch() {
        ScreenGeometry.Transform transform = new ScreenGeometry.Transform(1600, 900, 400, 700);
        assertEquals(.25f, transform.scale, 0f);
        assertEquals(237.5f, transform.offsetY, 0f);
        ScreenGeometry.Box viewport = transform.viewport();
        assertEquals(400f, viewport.width(), 0f); assertEquals(225f, viewport.height(), 0f);
        assertFalse(viewport.contains(200, 100));
    }
    @Test public void safeAreaChangesOnlyViewportNotCropOrigin() {
        ScreenGeometry.Transform before = new ScreenGeometry.Transform(1080, 2400, 1080, 2400);
        ScreenGeometry.Transform after = new ScreenGeometry.Transform(1080, 2400, 1080, 1995);
        ScreenGeometry.Box crop = new ScreenGeometry.Box(180, 700, 980, 1200);
        ScreenGeometry.Box a = before.toView(crop), b = after.toView(crop);
        ScreenGeometry.Box selectedA = before.dragToPixels(a.left, a.top, a.right, a.bottom);
        ScreenGeometry.Box selectedB = after.dragToPixels(b.left, b.top, b.right, b.bottom);
        assertTrue(Math.abs(selectedA.left - selectedB.left) <= 1); assertTrue(Math.abs(selectedA.top - selectedB.top) <= 1);
        assertTrue(Math.abs(selectedA.right - selectedB.right) <= 1); assertTrue(Math.abs(selectedA.bottom - selectedB.bottom) <= 1);
    }
    @Test public void accidentalTapAndNarrowSliverCannotBeSubmitted() {
        ScreenGeometry.Transform transform = new ScreenGeometry.Transform(1080, 2400, 360, 800);
        assertFalse(ScreenGeometry.isUsableSelection(transform.dragToPixels(10, 10, 11, 11), transform, 24));
        assertFalse(ScreenGeometry.isUsableSelection(transform.dragToPixels(10, 10, 12, 600), transform, 24));
        assertTrue(ScreenGeometry.isUsableSelection(transform.dragToPixels(30, 60, 300, 400), transform, 24));
    }
}
