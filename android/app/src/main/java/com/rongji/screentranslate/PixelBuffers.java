package com.rongji.screentranslate;

import java.nio.ByteBuffer;

/** Compacts Android Image.Plane rows without reading unmapped padding after the last pixel. */
final class PixelBuffers {
    private PixelBuffers() {}
    static ByteBuffer rgba(ByteBuffer source, int width, int height, int pixelStride, int rowStride) {
        long requiredRow = (long) (width - 1) * pixelStride + 4;
        long requiredBytes = (long) (height - 1) * rowStride + requiredRow;
        long outputSize = (long) width * height * 4;
        if (width <= 0 || height <= 0 || pixelStride < 4 || rowStride < requiredRow || requiredBytes > source.remaining()
                || outputSize > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid RGBA plane dimensions or buffer");
        ByteBuffer input = source.duplicate(), output = ByteBuffer.allocate((int) outputSize);
        int base = input.position();
        for (int row = 0; row < height; row++) {
            int offset = base + row * rowStride;
            if (pixelStride == 4) {
                input.limit(offset + width * 4); input.position(offset); output.put(input); input.limit(source.limit());
            } else {
                for (int column = 0; column < width; column++) {
                    int pixel = offset + column * pixelStride;
                    for (int channel = 0; channel < 4; channel++) output.put(input.get(pixel + channel));
                }
            }
        }
        output.flip(); return output;
    }
}
