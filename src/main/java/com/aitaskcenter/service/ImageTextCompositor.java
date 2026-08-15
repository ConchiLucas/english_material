package com.aitaskcenter.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ImageTextCompositor {
    private static final int WIDTH = 1536;
    private static final int HEIGHT = 864;
    private static final int SAFE_MARGIN = 48;
    private static final int MIN_FONT_SIZE = 28;
    private static final int DIALOGUE_MAX_FONT_SIZE = 40;
    private static final int NARRATION_MAX_FONT_SIZE = 36;
    private static final int BUBBLE_MAX_WIDTH = 590;
    private static final int BUBBLE_MAX_HEIGHT = 240;
    private static final int TEXT_PADDING = 28;
    private static final int NARRATION_HEIGHT = 174;
    private static final Color DARK_TEXT = new Color(28, 31, 37);

    public byte[] compose(byte[] baseImage, List<TextOverlay> overlays) {
        BufferedImage source = decode(baseImage);
        if (source.getWidth() != WIDTH || source.getHeight() != HEIGHT) {
            throw new IllegalArgumentException("图片底图必须为 1536x864 的 16:9 图片");
        }
        List<TextOverlay> safeOverlays = overlays == null ? List.of() : List.copyOf(overlays);
        BufferedImage canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            List<TextOverlay> narration = safeOverlays.stream()
                    .filter(value -> value != null && value.type() == OverlayType.NARRATION).toList();
            int dialogueBottom = narration.isEmpty() ? HEIGHT - SAFE_MARGIN : HEIGHT - NARRATION_HEIGHT - 20;
            for (TextOverlay overlay : safeOverlays) {
                if (overlay == null) throw new IllegalArgumentException("文字图层不能为空");
                validate(overlay);
                if (overlay.type() == OverlayType.DIALOGUE) drawDialogue(graphics, overlay, dialogueBottom);
            }
            if (!narration.isEmpty()) drawNarration(graphics, narration);
        } finally {
            graphics.dispose();
        }
        return encode(canvas);
    }

    private void drawDialogue(Graphics2D graphics, TextOverlay overlay, int bottomLimit) {
        String content = StringUtils.hasText(overlay.speaker())
                ? overlay.speaker().trim() + ": " + overlay.text().trim()
                : overlay.text().trim();
        TextLayout layout = fit(graphics, content, DIALOGUE_MAX_FONT_SIZE,
                BUBBLE_MAX_WIDTH - TEXT_PADDING * 2, BUBBLE_MAX_HEIGHT - TEXT_PADDING * 2);
        int bubbleWidth = Math.min(BUBBLE_MAX_WIDTH, layout.maxLineWidth() + TEXT_PADDING * 2);
        int bubbleHeight = layout.height() + TEXT_PADDING * 2;
        int anchorX = (int) Math.round(overlay.anchorX() * (WIDTH - 1));
        int anchorY = (int) Math.round(overlay.anchorY() * (HEIGHT - 1));
        int preferredX = anchorX >= WIDTH / 2 ? anchorX - bubbleWidth - 70 : anchorX + 70;
        int x = clamp(preferredX, SAFE_MARGIN, WIDTH - SAFE_MARGIN - bubbleWidth);
        int preferredY = anchorY - bubbleHeight - 54;
        int y = clamp(preferredY, SAFE_MARGIN, Math.max(SAFE_MARGIN, bottomLimit - bubbleHeight));

        int pointerBaseX = clamp(anchorX, x + 36, x + bubbleWidth - 36);
        int pointerBaseY = anchorY < y ? y : y + bubbleHeight;
        Polygon pointer = new Polygon(
                new int[] {pointerBaseX - 18, pointerBaseX + 18, anchorX},
                new int[] {pointerBaseY, pointerBaseY, anchorY}, 3);
        graphics.setColor(new Color(255, 255, 255, 238));
        graphics.fill(pointer);
        graphics.fillRoundRect(x, y, bubbleWidth, bubbleHeight, 34, 34);
        graphics.setColor(new Color(45, 48, 54, 230));
        graphics.setStroke(new BasicStroke(3f));
        graphics.draw(pointer);
        graphics.drawRoundRect(x, y, bubbleWidth, bubbleHeight, 34, 34);
        drawLines(graphics, layout, x + TEXT_PADDING, y + TEXT_PADDING, DARK_TEXT);
    }

    private void drawNarration(Graphics2D graphics, List<TextOverlay> narration) {
        String content = narration.stream().map(TextOverlay::text).map(String::trim)
                .filter(StringUtils::hasText).reduce((left, right) -> left + " " + right).orElse("");
        int x = SAFE_MARGIN;
        int y = HEIGHT - NARRATION_HEIGHT;
        int width = WIDTH - SAFE_MARGIN * 2;
        TextLayout layout = fit(graphics, content, NARRATION_MAX_FONT_SIZE,
                width - TEXT_PADDING * 2, NARRATION_HEIGHT - TEXT_PADDING * 2);
        graphics.setColor(new Color(15, 18, 24, 196));
        graphics.fillRoundRect(x, y, width, NARRATION_HEIGHT - SAFE_MARGIN / 2, 24, 24);
        drawLines(graphics, layout, x + TEXT_PADDING, y + TEXT_PADDING, Color.WHITE);
    }

    private TextLayout fit(Graphics2D graphics, String text, int maxFont, int maxWidth, int maxHeight) {
        for (int size = maxFont; size >= MIN_FONT_SIZE; size--) {
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, size);
            graphics.setFont(font);
            FontMetrics metrics = graphics.getFontMetrics(font);
            List<String> lines = wrap(text, metrics, maxWidth);
            int lineHeight = metrics.getHeight() + Math.max(2, size / 7);
            int height = lines.size() * lineHeight;
            int maxLineWidth = lines.stream().mapToInt(metrics::stringWidth).max().orElse(0);
            if (maxLineWidth <= maxWidth && height <= maxHeight) {
                return new TextLayout(font, metrics, lines, lineHeight, height, maxLineWidth);
            }
        }
        throw new IllegalArgumentException("文字内容无法排入安全区域，拒绝裁切溢出内容");
    }

    private List<String> wrap(String text, FontMetrics metrics, int maxWidth) {
        List<String> result = new ArrayList<>();
        for (String paragraph : text.split("\\R", -1)) {
            String normalized = paragraph.trim().replaceAll("\\s+", " ");
            if (normalized.isEmpty()) continue;
            StringBuilder line = new StringBuilder();
            for (String word : normalized.split(" ")) {
                if (metrics.stringWidth(word) > maxWidth) {
                    if (!line.isEmpty()) {
                        result.add(line.toString());
                        line.setLength(0);
                    }
                    splitLongWord(word, metrics, maxWidth, result, line);
                    continue;
                }
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (metrics.stringWidth(candidate) <= maxWidth) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    result.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            if (!line.isEmpty()) result.add(line.toString());
        }
        return result;
    }

    private void splitLongWord(String word, FontMetrics metrics, int maxWidth,
                               List<String> result, StringBuilder remainder) {
        StringBuilder part = new StringBuilder();
        for (int offset = 0; offset < word.length();) {
            int end = word.offsetByCodePoints(offset, 1);
            String candidate = part + word.substring(offset, end);
            if (!part.isEmpty() && metrics.stringWidth(candidate) > maxWidth) {
                result.add(part.toString());
                part.setLength(0);
            }
            part.append(word, offset, end);
            offset = end;
        }
        remainder.append(part);
    }

    private void drawLines(Graphics2D graphics, TextLayout layout, int x, int y, Color color) {
        graphics.setFont(layout.font());
        graphics.setColor(color);
        int baseline = y + layout.metrics().getAscent();
        for (String line : layout.lines()) {
            graphics.drawString(line, x, baseline);
            baseline += layout.lineHeight();
        }
    }

    private void validate(TextOverlay overlay) {
        if (!StringUtils.hasText(overlay.text())) throw new IllegalArgumentException("文字图层内容不能为空");
        if (overlay.type() == null) throw new IllegalArgumentException("文字图层类型不能为空");
        if (overlay.type() == OverlayType.DIALOGUE
                && (!Double.isFinite(overlay.anchorX()) || !Double.isFinite(overlay.anchorY())
                || overlay.anchorX() < 0 || overlay.anchorX() > 1
                || overlay.anchorY() < 0 || overlay.anchorY() > 1)) {
            throw new IllegalArgumentException("对话锚点必须位于画面范围内");
        }
    }

    private BufferedImage decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("图片底图不能为空");
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) throw new IllegalArgumentException("图片底图无法解码");
            return image;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("图片底图无法解码", exception);
        }
    }

    private byte[] encode(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG 编码器不可用");
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("文字合成图片编码失败", exception);
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    public enum OverlayType { DIALOGUE, NARRATION }

    public record TextOverlay(OverlayType type, String speaker, String text, double anchorX, double anchorY) {
        public static TextOverlay dialogue(String speaker, String text, double anchorX, double anchorY) {
            return new TextOverlay(OverlayType.DIALOGUE, speaker, text, anchorX, anchorY);
        }

        public static TextOverlay narration(String text) {
            return new TextOverlay(OverlayType.NARRATION, "", text, 0, 0);
        }
    }

    private record TextLayout(Font font, FontMetrics metrics, List<String> lines,
                              int lineHeight, int height, int maxLineWidth) { }
}
