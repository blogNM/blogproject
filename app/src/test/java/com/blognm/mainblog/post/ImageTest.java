package com.blognm.mainblog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 004 User Story 4 - 사진 올리기 (BRD-05, SEC-08) */
class ImageTest extends IntegrationTest {

    private Cookie[] owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = member("owner@example.com", "블로그장");
        postAs("/api/blogs", """
                {"slug":"food","name":"맛집","visibility":"PUBLIC","joinPolicy":"FREE"}""", owner)
                .andExpect(status().isOk());
    }

    private ResultActions upload(Cookie[] who, String name, String mime, byte[] data) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.multipart("/api/blogs/food/images")
                .file(new MockMultipartFile("file", name, mime, data)).with(csrf().asHeader()).cookie(who));
    }

    private String uploadedUrl(byte[] data) throws Exception {
        String body = upload(owner, "photo.png", "image/png", data).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.url");
    }

    private static byte[] image(String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    /** SOI 뒤에 GPS 표시와 Orientation=6이 든 EXIF(APP1)를 끼워 넣은 JPEG. */
    private static byte[] jpegWithGps() throws Exception {
        byte[] jpeg = image("jpg");
        ByteArrayOutputStream exif = new ByteArrayOutputStream();
        exif.writeBytes("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
        exif.writeBytes(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8}); // TIFF 머리, IFD0은 8번째 바이트
        exif.writeBytes(new byte[] {0, 2}); // 항목 2개
        exif.writeBytes(new byte[] {0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0}); // Orientation = 6
        exif.writeBytes(new byte[] {(byte) 0x88, 0x25, 0, 4, 0, 0, 0, 1, 0, 0, 0, 38}); // GPS IFD 위치
        exif.writeBytes(new byte[] {0, 0, 0, 0});
        exif.writeBytes("GPS-37.5665N-126.9780E".getBytes(StandardCharsets.ISO_8859_1));
        byte[] payload = exif.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(0xE1);
        out.write((payload.length + 2) >> 8);
        out.write((payload.length + 2) & 0xFF);
        out.writeBytes(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    @Test
    @DisplayName("한 회원이 1분에 올릴 수 있는 사진 수를 넘으면 막는다 (D-127)")
    void uploadRateLimited() throws Exception {
        byte[] png = image("png");
        for (int i = 0; i < 30; i++) {
            upload(owner, "photo.png", "image/png", png).andExpect(status().isOk());
        }
        upload(owner, "photo.png", "image/png", png).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("정상 사진은 UUID 이름으로 저장되고, 원래 이름은 DB에만 남는다")
    void uploadAndServe() throws Exception {
        byte[] png = image("png");
        String url = uploadedUrl(png);
        assertThat(url).matches("/uploads/[0-9a-f-]{36}");
        assertThat(jdbc.queryForObject("SELECT original_name FROM images", String.class)).isEqualTo("photo.png");
        mvc.perform(MockMvcRequestBuilders.get(url)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(MockMvcRequestBuilders.get("/uploads/../../etc/passwd")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("JPEG의 위치 정보(EXIF)를 지우고 사진 방향만 남긴다 (US4-4)")
    void stripsGps() throws Exception {
        byte[] original = jpegWithGps();
        assertThat(new String(original, StandardCharsets.ISO_8859_1)).contains("GPS-37.5665N");
        String body = upload(owner, "gps.jpg", "image/jpeg", original).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        byte[] saved = mvc.perform(MockMvcRequestBuilders.get((String) JsonPath.read(body, "$.url")))
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(saved, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("GPS-37.5665N");
        assertThat(text).contains("Exif");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(saved))).isNotNull();
    }

    @Test
    @DisplayName("확장자·MIME·시그니처 중 하나라도 맞지 않으면 거절한다 (US4-1·2, SC-003)")
    void rejectsBadFiles() throws Exception {
        byte[] exe = new byte[100];
        exe[0] = 'M';
        exe[1] = 'Z';
        upload(owner, "virus.jpg", "image/jpeg", exe).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
        upload(owner, "x.svg", "image/svg+xml", "<svg onload=alert(1)/>".getBytes())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
        upload(owner, "fake.png", "image/png", image("jpg")).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
        upload(owner, "photo.png", "image/jpeg", image("png")).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
        upload(owner, "doc.pdf", "application/pdf", "%PDF-1.4".getBytes())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM images", Integer.class)).isZero();
    }

    @Test
    @DisplayName("3MB가 넘는 사진, 멤버가 아닌 회원의 업로드는 거절한다")
    void sizeAndPermission() throws Exception {
        byte[] big = new byte[3 * 1024 * 1024 + 1];
        System.arraycopy(image("png"), 0, big, 0, 8);
        upload(owner, "big.png", "image/png", big).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        Cookie[] outsider = member("out@example.com", "바깥");
        upload(outsider, "photo.png", "image/png", image("png")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("글을 저장하면 본문의 사진이 그 글에 붙고 첫 사진이 목록 미리보기가 된다. 11장은 거절")
    void attachToPost() throws Exception {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            urls.add(uploadedUrl(image("png")));
        }
        StringBuilder eleven = new StringBuilder("본문");
        urls.forEach(u -> eleven.append("\\n![](").append(u).append(")"));
        postAs("/api/blogs/food/posts", """
                {"title":"사진 많음","content":"%s"}""".formatted(eleven), owner)
                .andExpect(jsonPath("$.code").value("TOO_MANY_IMAGES"));

        postAs("/api/blogs/food/posts", """
                {"title":"사진 글","content":"![](%s)\\n\\n![](%s)"}""".formatted(urls.get(1), urls.get(0)), owner)
                .andExpect(status().isOk());
        getAs("/api/blogs/food/posts").andExpect(jsonPath("$.items[0].imageUrl").value(urls.get(1)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM images WHERE post_id IS NOT NULL", Integer.class))
                .isEqualTo(2);

        // 남이 올린 사진 주소를 넣어도 내 글에 붙지 않는다
        Cookie[] member = member("m@example.com", "멤버");
        postAs("/api/blogs/food/join", null, member).andExpect(status().isOk());
        postAs("/api/blogs/food/posts", """
                {"title":"남의 사진","content":"![](%s)"}""".formatted(urls.get(5)), member)
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM images WHERE post_id IS NOT NULL", Integer.class))
                .isEqualTo(2);
    }
}
