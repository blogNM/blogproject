package com.blognm.mainblog.blog;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.blognm.mainblog.support.IntegrationTest;

/** 블로그 테마 틀과 꾸미기 (D-89) */
class ThemeTest extends IntegrationTest {

    private Cookie[] owner;
    private Cookie[] member;

    @BeforeEach
    void setUp() throws Exception {
        owner = member("owner@example.com", "블로그장");
        member = member("member@example.com", "멤버");
        postAs("/api/blogs", """
                {"slug":"trip","name":"여행","visibility":"PUBLIC","joinPolicy":"FREE"}""", owner)
                .andExpect(status().isOk());
        postAs("/api/blogs/trip/join", null, member).andExpect(status().isOk());
    }

    @Test
    @DisplayName("새 블로그는 Odyssey 틀과 기본 보라색. 블로그장이 틀·색·글꼴·문구를 바꾼다")
    void changeTheme() throws Exception {
        getAs("/api/blogs/trip")
                .andExpect(jsonPath("$.theme.theme").value("ODYSSEY"))
                .andExpect(jsonPath("$.theme.accent").value("#7C0CB4"));
        sendAs(HttpMethod.PUT, "/api/blogs/trip/theme", """
                {"theme":"PORTFOLIO","accent":"#ff5a7a","font":"SERIF","heroTitle":"사진으로 보는 세상","heroSubtitle":"주말마다 떠납니다"}""",
                owner).andExpect(status().isOk());
        getAs("/api/blogs/trip")
                .andExpect(jsonPath("$.theme.theme").value("PORTFOLIO"))
                .andExpect(jsonPath("$.theme.accent").value("#FF5A7A"))
                .andExpect(jsonPath("$.theme.font").value("SERIF"))
                .andExpect(jsonPath("$.theme.heroTitle").value("사진으로 보는 세상"));
    }

    @Test
    @DisplayName("블로그를 만들 때 테마를 함께 고른다. 잘못된 색이면 블로그를 만들지 않는다 (D-101)")
    void themeOnCreate() throws Exception {
        postAs("/api/blogs", """
                {"slug":"film","name":"영화","visibility":"PUBLIC","joinPolicy":"FREE",
                 "theme":{"theme":"MAGAZINE","accent":"#d93a49","font":"MONO","heroTitle":"이번 주 영화"}}""", owner)
                .andExpect(status().isOk());
        getAs("/api/blogs/film")
                .andExpect(jsonPath("$.theme.theme").value("MAGAZINE"))
                .andExpect(jsonPath("$.theme.accent").value("#D93A49"))
                .andExpect(jsonPath("$.theme.font").value("MONO"))
                .andExpect(jsonPath("$.theme.heroTitle").value("이번 주 영화"));
        postAs("/api/blogs", """
                {"slug":"bad-color","name":"색","visibility":"PUBLIC","joinPolicy":"FREE",
                 "theme":{"theme":"LETTER","accent":"red"}}""", owner)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ACCENT"));
        getAs("/api/blogs/bad-color").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("색은 #RRGGBB만, 없는 틀은 거절. 일반 멤버는 테마를 못 바꾼다")
    void validation() throws Exception {
        sendAs(HttpMethod.PUT, "/api/blogs/trip/theme", """
                {"theme":"LETTER","accent":"red;background:url(x)"}""", owner)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ACCENT"));
        sendAs(HttpMethod.PUT, "/api/blogs/trip/theme", """
                {"theme":"NOPE"}""", owner).andExpect(status().isBadRequest());
        sendAs(HttpMethod.PUT, "/api/blogs/trip/theme", """
                {"theme":"LETTER","heroTitle":"%s"}""".formatted("가".repeat(41)), owner)
                .andExpect(status().isBadRequest());
        sendAs(HttpMethod.PUT, "/api/blogs/trip/theme", """
                {"theme":"LETTER"}""", member).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("머리 사진을 올리면 블로그 정보와 블로그 목록에 나온다. 멤버는 못 올린다")
    void cover() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "png", png);
        MockMultipartFile file = new MockMultipartFile("file", "cover.png", "image/png", png.toByteArray());
        mvc.perform(MockMvcRequestBuilders.multipart("/api/blogs/trip/cover").file(file).with(csrf().asHeader())
                .cookie(member)).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.multipart("/api/blogs/trip/cover").file(file).with(csrf().asHeader())
                .cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.coverUrl").exists());
        getAs("/api/blogs/trip").andExpect(jsonPath("$.theme.coverUrl").exists());
        getAs("/api/blogs").andExpect(jsonPath("$.items[0].coverUrl").exists());
        sendAs(HttpMethod.DELETE, "/api/blogs/trip/cover", null, owner).andExpect(status().isOk());
        getAs("/api/blogs/trip").andExpect(jsonPath("$.theme.coverUrl").doesNotExist());
    }
}
