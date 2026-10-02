package com.robustvision.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ImageEnhancementTest {
    @Test
    void scanlineLookupIsPixelIdenticalForAllStandardColorModelsAndSubimages() {
        int[] types = {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_INT_RGB,
                BufferedImage.TYPE_INT_BGR, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR,
                BufferedImage.TYPE_4BYTE_ABGR_PRE, BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_USHORT_GRAY,
                BufferedImage.TYPE_BYTE_BINARY, BufferedImage.TYPE_BYTE_INDEXED, BufferedImage.TYPE_USHORT_565_RGB,
                BufferedImage.TYPE_USHORT_555_RGB};
        for (int type : types) {
            BufferedImage input = fixture(53, 29, type);
            assertSamePixels(input);
            assertSamePixels(input.getSubimage(3, 4, 31, 19));
        }
        BufferedImage allValues = new BufferedImage(256, 1, BufferedImage.TYPE_INT_ARGB);
        for (int value = 0; value < 256; value++) allValues.setRGB(value, 0, value << 24 | value << 16 | value << 8 | value);
        assertSamePixels(allValues);
    }

    @Test
    @EnabledIfSystemProperty(named = "performance.benchmark", matches = "true")
    void benchmarkIdentical1080pImageProcessing() {
        BufferedImage source = fixture(1920, 1080, BufferedImage.TYPE_3BYTE_BGR);
        for (int i = 0; i < 5; i++) { original(source); FileService.enhance(source); }
        double[] before = new double[15], after = new double[15];
        for (int i = 0; i < before.length; i++) {
            if (i % 2 == 0) { before[i] = measure(source, false); after[i] = measure(source, true); }
            else { after[i] = measure(source, true); before[i] = measure(source, false); }
        }
        Arrays.sort(before); Arrays.sort(after);
        System.out.printf(Locale.ROOT,
                "IMAGE_BENCHMARK size=1920x1080 type=3BYTE_BGR warmup=5 samples=15 originalMedianMs=%.3f optimizedMedianMs=%.3f originalP95Ms=%.3f optimizedP95Ms=%.3f temporaryInts=1920%n",
                before[7], after[7], before[14], after[14]);
    }

    private double measure(BufferedImage source, boolean optimized) {
        long start = System.nanoTime();
        BufferedImage result = optimized ? FileService.enhance(source) : original(source);
        double milliseconds = (System.nanoTime() - start) / 1_000_000.0;
        assertThat(result.getRGB(13, 17)).isEqualTo(originalPixel(source.getRGB(13, 17)));
        return milliseconds;
    }

    private void assertSamePixels(BufferedImage source) {
        int width = source.getWidth(), height = source.getHeight();
        int[] before = source.getRGB(0, 0, width, height, null, 0, width);
        assertThat(FileService.enhance(source).getRGB(0, 0, width, height, null, 0, width))
                .containsExactly(original(source).getRGB(0, 0, width, height, null, 0, width));
        assertThat(source.getRGB(0, 0, width, height, null, 0, width)).containsExactly(before);
    }

    private BufferedImage fixture(int width, int height, int type) {
        BufferedImage input = new BufferedImage(width, height, type);
        Random random = new Random(27);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) input.setRGB(x, y, random.nextInt());
        return input;
    }

    private BufferedImage original(BufferedImage source) {
        BufferedImage output = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++)
            output.setRGB(x, y, originalPixel(source.getRGB(x, y)));
        return output;
    }
    private int originalPixel(int argb) {
        return (argb & 0xff000000) | (adjust((argb >>> 16) & 0xff) << 16) | (adjust((argb >>> 8) & 0xff) << 8) | adjust(argb & 0xff);
    }
    private int adjust(int value) { return Math.max(0, Math.min(255, (int) ((value - 128) * 1.08 + 136))); }
}
