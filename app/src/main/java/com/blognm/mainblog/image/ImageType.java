package com.blognm.mainblog.image;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 올릴 수 있는 형식은 jpg·jpeg·png·gif·webp뿐이다 (SEC-08). svg·동영상·문서·압축 파일은 모두 거절. */
public enum ImageType {
    JPEG("image/jpeg", Set.of("jpg", "jpeg")),
    PNG("image/png", Set.of("png")),
    GIF("image/gif", Set.of("gif")),
    WEBP("image/webp", Set.of("webp"));

    private final String mime;
    private final Set<String> extensions;

    ImageType(String mime, Set<String> extensions) {
        this.mime = mime;
        this.extensions = extensions;
    }

    public String mime() {
        return mime;
    }

    public static Optional<ImageType> fromExtension(String filename) {
        if (filename == null) {
            return Optional.empty();
        }
        int dot = filename.lastIndexOf('.');
        String ext = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.extensions.contains(ext)).findFirst();
    }

    public static Optional<ImageType> fromMime(String mime) {
        if (mime == null) {
            return Optional.empty();
        }
        String m = mime.toLowerCase(Locale.ROOT).split(";")[0].strip();
        // 일부 브라우저는 jpg를 image/jpg로 보낸다.
        String normalized = m.equals("image/jpg") || m.equals("image/pjpeg") ? "image/jpeg" : m;
        return Arrays.stream(values()).filter(t -> t.mime.equals(normalized)).findFirst();
    }

    /** 파일 앞부분(시그니처)으로 실제 형식을 본다. */
    public static Optional<ImageType> fromSignature(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A) {
            return Optional.of(PNG);
        }
        if (d.length >= 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8'
                && (d[4] == '7' || d[4] == '9') && d[5] == 'a') {
            return Optional.of(GIF);
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }
}
