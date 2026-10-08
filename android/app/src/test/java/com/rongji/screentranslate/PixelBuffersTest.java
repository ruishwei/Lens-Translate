package com.rongji.screentranslate;

import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class PixelBuffersTest {
    @Test public void ignoresRowPaddingWithoutRequiringFinalPadding() {
        byte[] source = {1,2,3,4, 5,6,7,8, 99,99,99,99, 9,10,11,12, 13,14,15,16};
        assertArrayEquals(new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16}, PixelBuffers.rgba(ByteBuffer.wrap(source), 2, 2, 4, 12).array());
    }
    @Test public void normalRowsPreservePixelsAndInputPosition() {
        byte[] expected = {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16}; ByteBuffer input = ByteBuffer.wrap(expected);
        assertArrayEquals(expected, PixelBuffers.rgba(input, 2, 2, 4, 8).array()); assertEquals(0, input.position()); assertEquals(16, input.limit());
    }
    @Test public void honorsBufferOffsetAndPixelStride() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{99, 1,2,3,4,88,88,88,88, 5,6,7,8}); source.position(1);
        assertArrayEquals(new byte[]{1,2,3,4,5,6,7,8}, PixelBuffers.rgba(source, 2, 1, 8, 16).array()); assertEquals(1, source.position());
    }
    @Test public void rejectsTruncatedPlane() {
        try { PixelBuffers.rgba(ByteBuffer.allocate(19), 2, 2, 4, 12); fail("Truncated final pixel accepted"); } catch (IllegalArgumentException expected) { }
    }
    @Test public void rejectsInvalidStride() {
        try { PixelBuffers.rgba(ByteBuffer.allocate(16), 2, 2, 4, 4); fail("Overlapping rows accepted"); } catch (IllegalArgumentException expected) { }
    }
}
