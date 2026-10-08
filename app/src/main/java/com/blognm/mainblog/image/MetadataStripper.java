package com.blognm.mainblog.image;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 사진 속 위치(GPS)·기기 정보를 지운다 (6.3). 다시 압축하지 않고 메타데이터 덩어리만 빼서 화질이 그대로다.
 * - JPEG: EXIF·XMP(APP1), IPTC(APP13), 주석 등을 빼고, 사진 방향(Orientation)만 새 EXIF로 다시 넣는다.
 * - PNG: eXIf, 글자(tEXt·zTXt·iTXt), 시각(tIME) 덩어리를 뺀다.
 * - WebP: EXIF, XMP 덩어리를 빼고 VP8X 표시를 고친다.
 * - GIF: 위치 정보를 담는 표준 칸이 없어 그대로 둔다.
 * 구조가 깨진 파일은 IllegalArgumentException.
 */
final class MetadataStripper {

    private MetadataStripper() {
    }

    static byte[] strip(ImageType type, byte[] data) {
        return switch (type) {
            case JPEG -> jpeg(data);
            case PNG -> png(data);
            case WEBP -> webp(data);
            case GIF -> data;
        };
    }

    // ----- JPEG -----

    private static byte[] jpeg(byte[] d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(0xFF);
        out.write(0xD8);
        int orientation = 0;
        int i = 2;
        while (true) {
            if (i + 1 >= d.length || (d[i] & 0xFF) != 0xFF) {
                throw new IllegalArgumentException("JPEG 구조가 올바르지 않습니다.");
            }
            int marker = d[i + 1] & 0xFF;
            if (marker == 0xFF) { // 채움 바이트
                i++;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) { // 사진 데이터 시작: 나머지는 그대로
                if (orientation > 1) {
                    out.writeBytes(orientationExif(orientation));
                }
                out.write(d, i, d.length - i);
                return out.toByteArray();
            }
            if (i + 3 >= d.length) {
                throw new IllegalArgumentException("JPEG 구조가 올바르지 않습니다.");
            }
            int length = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            int end = i + 2 + length;
            if (length < 2 || end > d.length) {
                throw new IllegalArgumentException("JPEG 구조가 올바르지 않습니다.");
            }
            if (marker == 0xE1) {
                orientation = Math.max(orientation, readOrientation(d, i + 4, end));
            }
            boolean metadata = (marker >= 0xE1 && marker <= 0xEF && marker != 0xE2 && marker != 0xEE) || marker == 0xFE;
            if (!metadata) { // APP0(JFIF), APP2(색 정보), APP14(Adobe 색 변환), 그 밖의 표는 남긴다
                out.write(d, i, end - i);
            }
            i = end;
        }
    }

    /** APP1 안의 EXIF에서 IFD0의 Orientation(0x0112)만 읽는다. 없거나 읽을 수 없으면 0. */
    private static int readOrientation(byte[] d, int start, int end) {
        byte[] exif = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
        if (end - start < exif.length + 8) {
            return 0;
        }
        for (int k = 0; k < exif.length; k++) {
            if (d[start + k] != exif[k]) {
                return 0;
            }
        }
        int tiff = start + exif.length;
        ByteBuffer b = ByteBuffer.wrap(d, 0, end);
        if (d[tiff] == 'I' && d[tiff + 1] == 'I') {
            b.order(ByteOrder.LITTLE_ENDIAN);
        } else if (d[tiff] == 'M' && d[tiff + 1] == 'M') {
            b.order(ByteOrder.BIG_ENDIAN);
        } else {
            return 0;
        }
        try {
            int ifd = tiff + b.getInt(tiff + 4);
            int count = Short.toUnsignedInt(b.getShort(ifd));
            for (int n = 0; n < count; n++) {
                int entry = ifd + 2 + n * 12;
                if (Short.toUnsignedInt(b.getShort(entry)) == 0x0112) {
                    int value = Short.toUnsignedInt(b.getShort(entry + 8));
                    return value >= 1 && value <= 8 ? value : 0;
                }
            }
        } catch (IndexOutOfBoundsException e) {
            return 0;
        }
        return 0;
    }

    /** Orientation 하나만 든 최소 EXIF(APP1). 휴대폰 사진이 옆으로 눕지 않게 한다. */
    private static byte[] orientationExif(int orientation) {
        ByteBuffer b = ByteBuffer.allocate(2 + 2 + 6 + 8 + 2 + 12 + 4).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) 0xFF).put((byte) 0xE1);
        b.putShort((short) (b.capacity() - 2));
        b.put("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
        b.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        b.putShort((short) 1);
        b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        b.putInt(0);
        return b.array();
    }

    // ----- PNG -----

    private static byte[] png(byte[] d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(d, 0, 8);
        int i = 8;
        while (i < d.length) {
            if (i + 12 > d.length) {
                throw new IllegalArgumentException("PNG 구조가 올바르지 않습니다.");
            }
            long length = ByteBuffer.wrap(d, i, 4).order(ByteOrder.BIG_ENDIAN).getInt() & 0xFFFFFFFFL;
            String type = new String(d, i + 4, 4, StandardCharsets.ISO_8859_1);
            long end = i + 12 + length;
            if (end > d.length) {
                throw new IllegalArgumentException("PNG 구조가 올바르지 않습니다.");
            }
            boolean metadata = type.equals("eXIf") || type.equals("tEXt") || type.equals("zTXt")
                    || type.equals("iTXt") || type.equals("tIME");
            if (!metadata) {
                out.write(d, i, (int) (end - i));
            }
            i = (int) end;
            if (type.equals("IEND")) {
                break;
            }
        }
        return out.toByteArray();
    }

    // ----- WebP -----

    private static byte[] webp(byte[] d) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(d.length);
        int i = 12;
        int riffEnd = Math.min(d.length, 8 + (int) (ByteBuffer.wrap(d, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                & 0xFFFFFFFFL));
        int vp8xFlagsAt = -1;
        while (i + 8 <= riffEnd) {
            String type = new String(d, i, 4, StandardCharsets.ISO_8859_1);
            long size = ByteBuffer.wrap(d, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            long end = i + 8 + size + (size & 1);
            if (i + 8 + size > riffEnd) {
                throw new IllegalArgumentException("WebP 구조가 올바르지 않습니다.");
            }
            end = Math.min(end, riffEnd);
            if (!type.equals("EXIF") && !type.equals("XMP ")) {
                if (type.equals("VP8X")) {
                    vp8xFlagsAt = body.size() + 8;
                }
                body.write(d, i, (int) (end - i));
            }
            i = (int) end;
        }
        byte[] chunks = body.toByteArray();
        if (vp8xFlagsAt >= 0 && vp8xFlagsAt < chunks.length) {
            chunks[vp8xFlagsAt] &= (byte) ~(0x08 | 0x04); // EXIF·XMP 있음 표시를 끈다
        }
        ByteBuffer out = ByteBuffer.allocate(12 + chunks.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(4 + chunks.length)
                .put("WEBP".getBytes(StandardCharsets.ISO_8859_1)).put(chunks);
        return out.array();
    }
}
