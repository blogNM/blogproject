package com.blognm.mainblog.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** application.yml의 upload.* 값 (6.3, SEC-08). 개수·크기 제한은 환경변수로 바꾼다. */
@ConfigurationProperties(prefix = "upload")
public record UploadProperties(String dir, DataSize maxFileSize, int maxImagesPerPost) {
}
