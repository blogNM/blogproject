package com.blognm.mainblog.testdata;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import org.springframework.web.multipart.MultipartFile;

/** 저장소에 넣어 둔 테스트 그림을 회원이 올린 파일처럼 넘긴다. 검사(SEC-08)와 저장은 실제 올리기와 같다. */
final class SeedFile implements MultipartFile {

    private final String name;
    private final String contentType;
    private final byte[] data;

    SeedFile(String name, String contentType, byte[] data) {
        this.name = name;
        this.contentType = contentType;
        this.data = data;
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return name;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return data.length == 0;
    }

    @Override
    public long getSize() {
        return data.length;
    }

    @Override
    public byte[] getBytes() {
        return data.clone();
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(data);
    }

    @Override
    public void transferTo(File dest) throws IOException {
        Files.write(dest.toPath(), data);
    }
}
