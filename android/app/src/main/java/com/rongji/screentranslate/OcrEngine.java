package com.rongji.screentranslate;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bundled, local OCR with retained paragraph/line geometry. Images never leave the device. */
final class OcrEngine implements AutoCloseable {
    interface Callback { void success(String text); void failure(Exception error); }
    interface DocumentCallback { void success(OcrDocument document); void failure(Exception error); }
    private final TextRecognizer latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final TextRecognizer chinese = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

    void recognize(Bitmap bitmap, Callback callback) {
        recognize(bitmap, "自动检测", callback);
    }

    /** Legacy import path transfers bitmap ownership to this method. */
    void recognize(Bitmap bitmap, String source, Callback callback) {
        recognizeDocument(bitmap, source, new DocumentCallback() {
            @Override public void success(OcrDocument document) {
                try { callback.success(document.text()); } finally { bitmap.recycle(); }
            }
            @Override public void failure(Exception error) {
                try { callback.failure(error); } finally { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
            }
        });
    }

    /** The caller owns the bitmap and must keep it alive until either callback completes. */
    void recognizeDocument(Bitmap bitmap, String source, DocumentCallback callback) {
        if (bitmap == null || bitmap.isRecycled()) {
            callback.failure(new IllegalArgumentException("截图已失效，请重新截图。")); return;
        }
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        if ("简体中文".equals(source) || "繁體中文".equals(source)) {
            process(chinese, image).addOnSuccessListener(text -> callback.success(document(text, width, height)))
                    .addOnFailureListener(callback::failure);
        } else if (source != null && !"自动检测".equals(source)) {
            process(latin, image).addOnSuccessListener(text -> callback.success(document(text, width, height)))
                    .addOnFailureListener(callback::failure);
        } else {
            // Latin OCR is materially more reliable for English UI text. The second recognizer
            // contributes Chinese regions only, rather than replacing English with its guesses.
            process(latin, image).addOnCompleteListener(latinTask -> {
                process(chinese, image).addOnCompleteListener(chineseTask -> {
                    if (!latinTask.isSuccessful() && !chineseTask.isSuccessful()) {
                        Exception error = latinTask.getException();
                        callback.failure(error == null ? new java.io.IOException("文字识别失败，请重新截图。") : error);
                        return;
                    }
                    OcrDocument a = latinTask.isSuccessful() ? document(latinTask.getResult(), width, height) : null;
                    OcrDocument b = chineseTask.isSuccessful() ? document(chineseTask.getResult(), width, height) : null;
                    callback.success(a == null ? b : b == null ? a : ScreenLayout.combineScripts(a, b));
                });
            });
        }
    }

    private static Task<Text> process(TextRecognizer recognizer, InputImage image) {
        // Closing a capture session may race the second recognizer in automatic mode.
        try { return recognizer.process(image); }
        catch (RuntimeException error) { return Tasks.forException(error); }
    }

    private static OcrDocument document(Text result, int width, int height) {
        List<List<ScreenTextBlock.Line>> groups = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            List<ScreenTextBlock.Line> lines = new ArrayList<>();
            for (Text.Line line : block.getLines()) addLineFragments(lines, line, width, height);
            if (!lines.isEmpty()) groups.add(lines);
        }
        return ScreenLayout.document(width, height, groups);
    }

    private static void addLineFragments(List<ScreenTextBlock.Line> output, Text.Line line, int width, int height) {
        Rect box = line.getBoundingBox();
        if (box == null || line.getText().trim().isEmpty()) return;
        List<Text.Element> elements = new ArrayList<>(line.getElements());
        elements.removeIf(element -> element.getBoundingBox() == null || element.getText().trim().isEmpty());
        elements.sort(Comparator.comparingInt(element -> element.getBoundingBox().left));
        boolean hasWideGap = false;
        for (int i = 1; i < elements.size(); i++) {
            if (elements.get(i).getBoundingBox().left - elements.get(i - 1).getBoundingBox().right > box.height() * 1.65f) hasWideGap = true;
        }
        if (!hasWideGap) { addClamped(output, line.getText(), box, width, height); return; }
        Rect groupBox = null;
        StringBuilder groupText = new StringBuilder();
        for (Text.Element element : elements) {
            Rect elementBox = element.getBoundingBox();
            if (groupBox != null && elementBox.left - groupBox.right > box.height() * 1.65f) {
                addClamped(output, groupText.toString(), groupBox, width, height);
                groupBox = null; groupText.setLength(0);
            }
            if (groupBox == null) groupBox = new Rect(elementBox); else groupBox.union(elementBox);
            if (groupText.length() > 0 && !(isHan(groupText.charAt(groupText.length() - 1)) && isHan(element.getText().charAt(0)))) groupText.append(' ');
            groupText.append(element.getText());
        }
        if (groupBox != null) addClamped(output, groupText.toString(), groupBox, width, height);
    }

    private static void addClamped(List<ScreenTextBlock.Line> output, String text, Rect box, int width, int height) {
        int left = Math.max(0, box.left), top = Math.max(0, box.top), right = Math.min(width, box.right), bottom = Math.min(height, box.bottom);
        if (right > left && bottom > top && !text.trim().isEmpty()) output.add(new ScreenTextBlock.Line(text, left, top, right, bottom));
    }

    private static boolean isHan(int character) {
        return Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN;
    }

    static Bitmap load(Context context, Uri uri) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), uri), (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                if (max > 2400) decoder.setTargetSampleSize((int) Math.ceil(max / 2400.0));
            });
        }
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        try (var in = context.getContentResolver().openInputStream(uri)) { android.graphics.BitmapFactory.decodeStream(in, null, bounds); }
        bounds.inJustDecodeBounds = false; bounds.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / bounds.inSampleSize > 2400) bounds.inSampleSize *= 2;
        Bitmap bitmap;
        try (var in = context.getContentResolver().openInputStream(uri)) { bitmap = android.graphics.BitmapFactory.decodeStream(in, null, bounds); }
        if (bitmap == null) throw new java.io.IOException("无法读取这张图片，请选择 JPEG、PNG 或 WebP。");
        int orientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL;
        try (var in = context.getContentResolver().openInputStream(uri)) {
            orientation = new androidx.exifinterface.media.ExifInterface(in).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
        } catch (java.io.IOException ignored) { /* PNG and some other images do not carry EXIF metadata. */ }
        android.graphics.Matrix rotation = new android.graphics.Matrix();
        switch (orientation) {
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> rotation.postRotate(90);
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> rotation.postRotate(180);
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> rotation.postRotate(270);
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> rotation.postScale(-1, 1);
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> rotation.postScale(1, -1);
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> { rotation.postRotate(90); rotation.postScale(-1, 1); }
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> { rotation.postRotate(270); rotation.postScale(-1, 1); }
        }
        if (!rotation.isIdentity()) { Bitmap corrected = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotation, true); if (corrected != bitmap) bitmap.recycle(); bitmap = corrected; }
        return bitmap;
    }
    @Override public void close() { latin.close(); chinese.close(); }
}
