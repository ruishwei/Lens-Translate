package com.rongji.screentranslate;

/** Pixel geometry shared by capture selection and result rendering; independent of Android density. */
public final class ScreenGeometry {
    private ScreenGeometry() {}

    public static final class Box {
        public final float left, top, right, bottom;
        public Box(float left, float top, float right, float bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public float width() { return Math.max(0, right - left); }
        public float height() { return Math.max(0, bottom - top); }
        public boolean contains(float x, float y) { return x >= left && x <= right && y >= top && y <= bottom; }
    }

    /** FIT_CENTER: letterbox, never stretch; origin is relative to the actual content View. */
    public static final class Transform {
        public final int imageWidth, imageHeight;
        public final float scale, offsetX, offsetY;
        public Transform(int imageWidth, int imageHeight, float viewWidth, float viewHeight) {
            this(imageWidth, imageHeight, viewWidth, viewHeight, 0, 0);
        }
        /** Place full-display capture pixels in a window whose origin can exclude system bars. */
        public Transform(int imageWidth, int imageHeight, float viewWidth, float viewHeight, float windowX, float windowY) {
            if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) throw new IllegalArgumentException("Invalid viewport");
            this.imageWidth = imageWidth; this.imageHeight = imageHeight;
            scale = Math.min(viewWidth / imageWidth, viewHeight / imageHeight);
            offsetX = (viewWidth - imageWidth * scale) / 2f - windowX;
            offsetY = (viewHeight - imageHeight * scale) / 2f - windowY;
        }
        public Box viewport() { return new Box(offsetX, offsetY, offsetX + imageWidth * scale, offsetY + imageHeight * scale); }
        public Box toView(Box pixels) {
            return new Box(offsetX + pixels.left * scale, offsetY + pixels.top * scale,
                    offsetX + pixels.right * scale, offsetY + pixels.bottom * scale);
        }
        public Box dragToPixels(float x1, float y1, float x2, float y2) {
            float left = clamp((Math.min(x1, x2) - offsetX) / scale, 0, imageWidth);
            float top = clamp((Math.min(y1, y2) - offsetY) / scale, 0, imageHeight);
            float right = clamp((Math.max(x1, x2) - offsetX) / scale, 0, imageWidth);
            float bottom = clamp((Math.max(y1, y2) - offsetY) / scale, 0, imageHeight);
            // Include the pixel under each edge instead of shifting a fractional selection inward.
            return new Box((float)Math.floor(left), (float)Math.floor(top), (float)Math.ceil(right), (float)Math.ceil(bottom));
        }
    }

    public static float clamp(float value, float low, float high) { return Math.max(low, Math.min(value, high)); }
    public static boolean isUsableSelection(Box selected, Transform transform, float minimumViewPixels) {
        return selected != null && selected.width() * transform.scale >= minimumViewPixels
                && selected.height() * transform.scale >= minimumViewPixels;
    }
}
