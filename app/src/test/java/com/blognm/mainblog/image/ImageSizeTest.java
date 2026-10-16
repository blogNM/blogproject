package com.blognm.mainblog.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HexFormat;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 사진 머리에서 가로·세로 읽기 (그림을 풀지 않음) */
class ImageSizeTest {

    @Test
    @DisplayName("jpg·png·gif의 가로·세로를 머리에서 읽는다")
    void readsCommonFormats() throws Exception {
        assertThat(ImageSize.read(ImageType.JPEG, image("jpg"))).contains(new ImageSize.Size(20, 10));
        assertThat(ImageSize.read(ImageType.PNG, image("png"))).contains(new ImageSize.Size(20, 10));
        assertThat(ImageSize.read(ImageType.GIF, image("gif"))).contains(new ImageSize.Size(20, 10));
    }

    @Test
    @DisplayName("webp는 VP8·VP8L·VP8X 머리를 모두 읽는다")
    void readsWebp() {
        // VP8 (손실): 프레임 태그 3바이트, 시작 코드 9d 01 2a, 가로·세로 14비트
        byte[] vp8 = webp("VP8 ", "000000" + "9d012a" + "1400" + "0a00");
        assertThat(ImageSize.read(ImageType.WEBP, vp8)).contains(new ImageSize.Size(20, 10));
        // VP8L (무손실): 서명 2f, (가로-1) 14비트, (세로-1) 14비트
        int bits = 19 | (9 << 14);
        byte[] vp8l = webp("VP8L", "2f" + HexFormat.of().formatHex(new byte[] {(byte) bits, (byte) (bits >> 8),
                (byte) (bits >> 16), (byte) (bits >> 24)}) + "0000000000");
        assertThat(ImageSize.read(ImageType.WEBP, vp8l)).contains(new ImageSize.Size(20, 10));
        // VP8X (확장): 캔버스 (가로-1), (세로-1) 24비트
        byte[] vp8x = webp("VP8X", "00000000" + "2f7500" + "2f7500");
        assertThat(ImageSize.read(ImageType.WEBP, vp8x)).contains(new ImageSize.Size(30000, 30000));
    }

    @Test
    @DisplayName("머리가 잘린 파일은 빈 값")
    void truncated() {
        assertThat(ImageSize.read(ImageType.PNG, new byte[10])).isEmpty();
        assertThat(ImageSize.read(ImageType.JPEG, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})).isEmpty();
        assertThat(ImageSize.read(ImageType.WEBP, new byte[12])).isEmpty();
    }

    private static byte[] image(String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    private static byte[] webp(String chunk, String dataHex) {
        String header = HexFormat.of().formatHex("RIFF".getBytes()) + "00000000"
                + HexFormat.of().formatHex("WEBP".getBytes()) + HexFormat.of().formatHex(chunk.getBytes()) + "00000000";
        return HexFormat.of().parseHex(header + dataHex);
    }
}
