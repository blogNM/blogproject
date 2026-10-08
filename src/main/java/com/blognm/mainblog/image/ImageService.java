package com.blognm.mainblog.image;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.blognm.mainblog.common.ApiException;

/**
 * 이미지 올리기 (BRD-05, SEC-08). 확장자·MIME·파일 시그니처가 모두 같은 허용 형식이어야 받는다.
 * 위치 정보를 지운 뒤 UUID 이름으로 저장하고, 원래 이름은 DB에만 남긴다.
 */
@Service
public class ImageService {

    private final ImageRepository images;
    private final FileStorage storage;
    private final UploadProperties props;
    private final Clock clock;

    public ImageService(ImageRepository images, FileStorage storage, UploadProperties props, Clock clock) {
        this.images = images;
        this.storage = storage;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Image upload(long uploaderId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("NO_FILE", "올릴 사진을 골라 주세요.");
        }
        if (file.getSize() > props.maxFileSize().toBytes()) {
            throw tooLarge(props);
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ImageType byExtension = ImageType.fromExtension(file.getOriginalFilename()).orElseThrow(ImageService::notAllowed);
        ImageType byMime = ImageType.fromMime(file.getContentType()).orElseThrow(ImageService::notAllowed);
        ImageType bySignature = ImageType.fromSignature(data).orElseThrow(ImageService::notAllowed);
        if (byExtension != byMime || byMime != bySignature) {
            throw notAllowed();
        }
        byte[] clean;
        try {
            clean = MetadataStripper.strip(bySignature, data);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw ApiException.badRequest("BROKEN_IMAGE", "사진 파일이 손상되어 올릴 수 없습니다.");
        }
        String storedName = UUID.randomUUID().toString();
        storage.save(storedName, clean);
        return images.save(Image.uploaded(uploaderId, storedName, originalName(file.getOriginalFilename()),
                bySignature.mime(), clean.length, LocalDateTime.now(clock)));
    }

    @Transactional(readOnly = true)
    public Optional<Served> read(String storedName) {
        return images.findByStoredName(storedName)
                .flatMap(img -> storage.read(storedName).map(bytes -> new Served(img.getMimeType(), bytes)));
    }

    /** 경로를 빼고 이름만, 255자까지. */
    private static String originalName(String raw) {
        String name = raw == null ? "image" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "");
        if (name.isBlank()) {
            name = "image";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    static ApiException notAllowed() {
        return ApiException.badRequest("FILE_TYPE_NOT_ALLOWED", "jpg·jpeg·png·gif·webp 사진만 올릴 수 있습니다.");
    }

    static ApiException tooLarge(UploadProperties props) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                "사진은 한 장에 %dMB까지 올릴 수 있습니다.".formatted(props.maxFileSize().toMegabytes()));
    }

    public record Served(String mimeType, byte[] bytes) {
    }
}
