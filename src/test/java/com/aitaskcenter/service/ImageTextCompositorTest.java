package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageTextCompositorTest {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    private final ImageTextCompositor compositor = new ImageTextCompositor();

    @Test
    void composesDialogueAndNarrationOnSixteenByNineImage() throws Exception {
        byte[] base = basePng(1536, 864);

        byte[] result = compositor.compose(base, List.of(
                ImageTextCompositor.TextOverlay.dialogue("Toby", "No! That is my cake!", .62, .18),
                ImageTextCompositor.TextOverlay.narration("The elephant lifts the cakes high.")));

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(1536, image.getWidth());
        assertEquals(864, image.getHeight());
        assertFalse(Arrays.equals(base, result));
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(base));
        assertFalse(samePixels(original, image));
        assertTrue(changedPixels(original, image, 0, 650) > 100, "speech bubble must alter the upper canvas");
        assertTrue(changedPixels(original, image, 690, 864) > 100, "narration must alter the bottom safe area");
        assertTrue(changedNear(original, image, (int) (.62 * 1535), (int) (.18 * 863), 8),
                "speech bubble pointer must reach the normalized anchor");
    }

    @Test
    void wrapsLongReadableTextWithoutChangingCanvasSize() throws Exception {
        byte[] result = compositor.compose(basePng(1536, 864), List.of(
                ImageTextCompositor.TextOverlay.dialogue("Amy",
                        "Please bring the bright yellow basket to the little green garden gate before lunch.",
                        .15, .28),
                ImageTextCompositor.TextOverlay.narration(
                        "Amy follows the winding path while her friends carefully carry the picnic basket.")));

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(1536, image.getWidth());
        assertEquals(864, image.getHeight());
    }

    @Test
    void rejectsTextThatCannotFitAtTheMinimumFontSizeInsteadOfClipping() throws Exception {
        String oversized = "A very long sentence that must stay readable. ".repeat(200);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> compositor.compose(basePng(1536, 864), List.of(
                        ImageTextCompositor.TextOverlay.dialogue("Amy", oversized, .5, .5))));

        assertTrue(error.getMessage().contains("无法排入") || error.getMessage().contains("溢出"));
    }

    @Test
    void rejectsNonSixteenByNineOrUndecodableBaseImages() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> compositor.compose(basePng(1024, 1024), List.of(
                        ImageTextCompositor.TextOverlay.narration("Caption"))));
        assertThrows(IllegalArgumentException.class,
                () -> compositor.compose(new byte[] {1, 2, 3}, List.of(
                        ImageTextCompositor.TextOverlay.narration("Caption"))));
    }

    @Test
    void normalizesANoTextJpegBaseIntoAnIndependentPng() throws Exception {
        byte[] base = baseImage(1536, 864, "jpeg");

        byte[] result = compositor.compose(base, List.of());

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(1536, decoded.getWidth());
        assertEquals(864, decoded.getHeight());
        assertEquals(0x89, Byte.toUnsignedInt(result[0]));
        assertEquals(80, Byte.toUnsignedInt(result[1]));
        assertEquals(78, Byte.toUnsignedInt(result[2]));
        assertEquals(71, Byte.toUnsignedInt(result[3]));
        assertTrue(samePixels(ImageIO.read(new ByteArrayInputStream(base)), decoded));
    }

    private static boolean samePixels(BufferedImage left, BufferedImage right) {
        if (left.getWidth() != right.getWidth() || left.getHeight() != right.getHeight()) return false;
        for (int y = 0; y < left.getHeight(); y++) {
            for (int x = 0; x < left.getWidth(); x++) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) return false;
            }
        }
        return true;
    }

    private static long changedPixels(BufferedImage left, BufferedImage right, int startY, int endY) {
        long changed = 0;
        for (int y = startY; y < endY; y++) {
            for (int x = 0; x < left.getWidth(); x++) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) changed++;
            }
        }
        return changed;
    }

    private static boolean changedNear(BufferedImage left, BufferedImage right, int centerX, int centerY, int radius) {
        for (int y = Math.max(0, centerY - radius); y <= Math.min(left.getHeight() - 1, centerY + radius); y++) {
            for (int x = Math.max(0, centerX - radius); x <= Math.min(left.getWidth() - 1, centerX + radius); x++) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) return true;
            }
        }
        return false;
    }

    private static byte[] basePng(int width, int height) throws Exception {
        return baseImage(width, height, "png");
    }

    private static byte[] baseImage(int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height,
                "jpeg".equals(format) ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(80, 120, 170));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }
}
