package com.blognm.mainblog.image;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.config.AppProperties;
import com.blognm.mainblog.ratelimit.RateLimiter;

/**
 * 이미지 올리기 (BRD-05, SEC-08). 확장자·MIME·파일 시그니처가 모두 같은 허용 형식이어야 받는다.
 * 위치 정보를 지운 뒤 UUID 이름으로 저장하고, 원래 이름은 DB에만 남긴다.
 */
@Service
public class ImageService {

    private final ImageRepository images;
    private final FileStorage storage;
    private final UploadProperties props;
    private final RateLimiter rateLimiter;
    private final AppProperties app;
    private final Clock clock;

    public ImageService(ImageRepository images, FileStorage storage, UploadProperties props, RateLimiter rateLimiter,
            AppProperties app, Clock clock) {
        this.images = images;
        this.storage = storage;
        this.props = props;
        this.rateLimiter = rateLimiter;
        this.app = app;
        this.clock = clock;
    }

    @Transactional
    public Image upload(long uploaderId, MultipartFile file) {
        // 사진을 쉬지 않고 올려 서버 디스크를 채우지 못하게 회원마다 1분에 정해 둔 장수까지 (D-127)
        if (!rateLimiter.tryAcquire("upload:" + uploaderId, app.rateLimit().uploadPerMinute(), Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "사진을 너무 많이 올렸습니다. 잠시 후 다시 시도해 주세요.");
        }
        Checked c = check(file);
        String storedName = UUID.randomUUID().toString();
        storage.save(storedName, c.data());
        return images.save(Image.uploaded(uploaderId, storedName, originalName(file.getOriginalFilename()),
                c.type().mime(), c.data().length, LocalDateTime.now(clock)));
    }

    /** 관리자가 공지에 넣는 사진. 검사는 회원 사진과 같다 (SEC-08, D-102). */
    @Transactional
    public Image uploadByAdmin(long adminId, MultipartFile file) {
        Checked c = check(file);
        String storedName = UUID.randomUUID().toString();
        storage.save(storedName, c.data());
        return images.save(Image.uploadedByAdmin(adminId, storedName, originalName(file.getOriginalFilename()),
                c.type().mime(), c.data().length, LocalDateTime.now(clock)));
    }

    /** 확장자·MIME·파일 시그니처가 같은 허용 형식인지 보고, 위치 정보를 지운 내용을 돌려준다. */
    private Checked check(MultipartFile file) {
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
        return new Checked(bySignature, clean);
    }

    private record Checked(ImageType type, byte[] data) {
    }

    /**
     * 서버가 바깥에서 받아 온 사진(링크 미리보기 썸네일, D-92)을 저장한다. 형식은 파일 시그니처로만 보고,
     * 허용 형식이 아니거나 너무 크거나 깨졌으면 저장하지 않는다.
     */
    @Transactional
    public Optional<Image> storeFetched(long uploaderId, byte[] data) {
        if (data.length == 0 || data.length > props.maxFileSize().toBytes()) {
            return Optional.empty();
        }
        Optional<ImageType> type = ImageType.fromSignature(data);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        byte[] clean;
        try {
            clean = MetadataStripper.strip(type.get(), data);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return Optional.empty();
        }
        String storedName = UUID.randomUUID().toString();
        storage.save(storedName, clean);
        return Optional.of(images.save(Image.uploaded(uploaderId, storedName, "link-preview",
                type.get().mime(), clean.length, LocalDateTime.now(clock))));
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
