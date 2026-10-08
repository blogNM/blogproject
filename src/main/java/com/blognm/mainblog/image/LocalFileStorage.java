package com.blognm.mainblog.image;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** 서버 디스크에 저장한다 (D-75). 폴더는 UPLOAD_DIR 환경변수로 바꾼다. */
@Component
public class LocalFileStorage implements FileStorage {

    private static final Pattern SAFE_NAME = Pattern.compile("^[0-9a-f-]{36}$");

    private final Path root;

    public LocalFileStorage(UploadProperties props) {
        this.root = Path.of(props.dir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 폴더를 만들 수 없습니다: " + root, e);
        }
    }

    @Override
    public void save(String name, byte[] data) {
        try {
            Path tmp = Files.createTempFile(root, ".upload-", ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, path(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> read(String name) {
        try {
            return Optional.of(Files.readAllBytes(path(name)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String name) {
        try {
            Files.deleteIfExists(path(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path path(String name) {
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("파일 이름이 올바르지 않습니다.");
        }
        return root.resolve(name);
    }
}
