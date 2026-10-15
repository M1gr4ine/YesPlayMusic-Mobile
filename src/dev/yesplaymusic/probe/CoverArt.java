package dev.yesplaymusic.probe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

public final class CoverArt {
    private CoverArt() {
    }

    public static Bitmap create(int trackIndex) {
        final boolean sideA = Math.floorMod(trackIndex, 2) == 0;
        final int size = 512;
        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        int background = sideA ? 0xFF262625 : 0xFF111111;
        int accent = sideA ? 0xFFBDBDB8 : 0xFF777773;
        int muted = sideA ? 0xFF676763 : 0xFF41413E;
        int text = sideA ? 0xFFE6E6E1 : 0xFFD0D0CA;

        canvas.drawColor(background);

        paint.setColor(sideA ? 0xFF30302F : 0xFF191918);
        canvas.drawRect(0, 0, size, size, paint);

        // A quiet diagonal panel gives each sleeve a different grayscale silhouette.
        paint.setColor(sideA ? 0xFF3D3D3B : 0xFF242423);
        if (sideA) {
            canvas.drawRect(0, 0, 175, size, paint);
        } else {
            canvas.drawRect(337, 0, size, size, paint);
        }

        float centerX = sideA ? 330f : 190f;
        float centerY = 228f;
        float recordRadius = 160f;

        paint.setColor(sideA ? 0xFF151515 : 0xFF090909);
        canvas.drawCircle(centerX, centerY, recordRadius, paint);

        for (int ring = 0; ring < 7; ring++) {
            float radius = recordRadius - 19f - ring * 18f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ring % 2 == 0 ? 2.2f : 1.2f);
            paint.setColor(ring % 2 == 0 ? muted : 0xFF292928);
            canvas.drawCircle(centerX, centerY, radius, paint);
        }
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(accent);
        canvas.drawCircle(centerX, centerY, 31f, paint);
        paint.setColor(background);
        canvas.drawCircle(centerX, centerY, 8f, paint);

        paint.setColor(text);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(18f);

        canvas.drawText(sideA ? "YESPLAYMUSIC" : "YESPLAYMUSIC", 34f, 48f, paint);

        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(16f);
        paint.setColor(accent);
        canvas.drawText(sideA ? "LOCAL AUDIO  /  01" : "LOCAL AUDIO  /  02", 35f, 77f, paint);

        paint.setColor(text);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setTextSize(124f);
        canvas.drawText(sideA ? "A" : "B", 39f, 432f, paint);

        paint.setColor(accent);
        paint.setTextSize(17f);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        canvas.drawText(sideA ? "晴空 · TEST" : "夜航 · TEST", 42f, 468f, paint);

        paint.setColor(sideA ? 0xFF858580 : 0xFF5F5F5B);
        canvas.drawRect(34f, 487f, 478f, 490f, paint);

        paint.setTextSize(12f);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        canvas.drawText("AUDIO INTEGRATION", 35f, 507f, paint);

        return bitmap;
    }
}