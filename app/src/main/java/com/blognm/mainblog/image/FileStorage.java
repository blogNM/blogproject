package com.blognm.mainblog.image;

import java.util.Optional;

/**
 * 올린 파일을 두는 곳. 1차는 서버 디스크(LocalFileStorage), 이중화하면 S3 구현으로 바꾼다 (D-75, SCL-02).
 * 이름은 항상 UUID라 경로 문자가 들어오지 않는다.
 */
public interface FileStorage {

    void save(String name, byte[] data);

    Optional<byte[]> read(String name);

    void delete(String name);
}
