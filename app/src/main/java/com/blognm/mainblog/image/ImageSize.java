package com.blognm.mainblog.image;

import java.util.Optional;

/**
 * 사진의 가로·세로를 파일 머리에서만 읽는다 (그림을 풀지 않음).
 * 3MB짜리라도 30000×30000처럼 크게 적힌 사진은 읽는 사람 브라우저가 풀다가 멈추므로 올리기 전에 거른다.
 * 읽지 못하면 빈 값: 형식 검사는 따로 하므로 여기서는 막지 않는다.
 */
final class ImageSize {

    private ImageSize() {
    }

    record Size(int width, int height) {

        long pixels() {
            return (long) width * height;
        }
    }

    static Optional<Size> read(ImageType type, byte[] d) {
        try {
            return switch (type) {
                case PNG -> d.length >= 24 ? Optional.of(new Size(be32(d, 16), be32(d, 20))) : Optional.empty();
                case GIF -> d.length >= 10 ? Optional.of(new Size(le16(d, 6), le16(d, 8))) : Optional.empty();
                case JPEG -> jpeg(d);
                case WEBP -> webp(d);
            };
        } catch (IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    /** SOFn(C0~CF 가운데 C4·C8·CC 빼고) 머리의 높이·너비 */
    private static Optional<Size> jpeg(byte[] d) {
        int i = 2;
        while (i + 9 < d.length) {
            if ((d[i] & 0xFF) != 0xFF) {
                return Optional.empty();
            }
            int marker = d[i + 1] & 0xFF;
            if (marker == 0xFF) {
                i++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            int length = be16(d, i + 2);
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return Optional.of(new Size(be16(d, i + 7), be16(d, i + 5)));
            }
            if (marker == 0xDA || length < 2) {
                return Optional.empty();
            }
            i += 2 + length;
        }
        return Optional.empty();
    }

    private static Optional<Size> webp(byte[] d) {
        if (d.length < 30) {
            return Optional.empty();
        }
        String chunk = new String(d, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return switch (chunk) {
            case "VP8X" -> Optional.of(new Size(le24(d, 24) + 1, le24(d, 27) + 1));
            case "VP8L" -> {
                int b0 = d[21] & 0xFF, b1 = d[22] & 0xFF, b2 = d[23] & 0xFF, b3 = d[24] & 0xFF;
                int w = 1 + (((b1 & 0x3F) << 8) | b0);
                int h = 1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6));
                yield Optional.of(new Size(w, h));
            }
            case "VP8 " -> Optional.of(new Size(le16(d, 26) & 0x3FFF, le16(d, 28) & 0x3FFF));
            default -> Optional.empty();
        };
    }

    private static int be32(byte[] d, int i) {
        return ((d[i] & 0xFF) << 24) | ((d[i + 1] & 0xFF) << 16) | ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
    }

    private static int be16(byte[] d, int i) {
        return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
    }

    private static int le16(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }

    private static int le24(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) | ((d[i + 2] & 0xFF) << 16);
    }
}
