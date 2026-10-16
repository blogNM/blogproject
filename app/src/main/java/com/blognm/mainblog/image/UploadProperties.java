package com.blognm.mainblog.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * application.yml의 upload.* 값 (6.3, SEC-08). 개수·크기 제한은 환경변수로 바꾼다.
 *
 * @param maxPixels 사진 한 장의 가로×세로 상한. 파일은 작아도 크게 적힌 사진이 읽는 사람 브라우저를 멈추게 하지 않도록
 */
@ConfigurationProperties(prefix = "upload")
public record UploadProperties(String dir, DataSize maxFileSize, int maxImagesPerPost, long maxPixels) {
}
