package com.blognm.mainblog.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import com.blognm.mainblog.support.IntegrationTest;

/**
 * 서버 두 대 (D-111): 배포 스크립트가 묻는 /api/health, 어느 서버가 답했는지 보이는 X-Instance, 새벽 정리 작업.
 * INSTANCE_ID를 바꾼 컨텍스트를 따로 띄우면 그 컨텍스트가 DB를 비우고 다시 만들어(flyway clean) 다른 테스트의 ShedLock 기록이
 * 어긋나므로, app-b 값은 객체를 직접 만들어 확인한다.
 */
class HealthTest extends IntegrationTest {

    @Autowired
    ScheduledTaskHolder scheduled;

    @Test
    @DisplayName("/api/health는 로그인 없이 UP을 돌려주고 캐시하지 않는다. INSTANCE_ID가 비어 있으면 X-Instance를 붙이지 않는다")
    void health() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.instance").value(""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("X-Instance"));
    }

    @Test
    @DisplayName("app-b로 띄우면 /api/health에 이름이 보이고, DB가 안 되면 503 DOWN")
    void healthOfApp2() {
        var ok = new HealthController(jdbc, "app-b").health();
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("status", "UP").containsEntry("instance", "app-b");

        JdbcTemplate broken = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType) {
                throw new org.springframework.dao.DataAccessResourceFailureException("DB 없음");
            }
        };
        var down = new HealthController(broken, "app-b").health();
        assertThat(down.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(down.getBody()).containsEntry("status", "DOWN");
    }

    @Test
    @DisplayName("INSTANCE_ID가 있으면 모든 응답(401·오류 포함)에 X-Instance가 붙는다")
    void instanceHeader() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                    jakarta.servlet.http.HttpServletResponse resp) {
                resp.setStatus(401);
            }
        });
        new InstanceHeaderFilter(" app-b ").doFilter(new MockHttpServletRequest("GET", "/api/me"), res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("X-Instance")).isEqualTo("app-b");

        MockHttpServletResponse none = new MockHttpServletResponse();
        new InstanceHeaderFilter("").doFilter(new MockHttpServletRequest("GET", "/"), none, new MockFilterChain());
        assertThat(none.getHeader("X-Instance")).isNull();
    }

    @Test
    @DisplayName("APP_JOBS_ENABLED 기본값(true)이면 정리 작업 5개(글·인증·알림·블로그·방문 기록)가 등록된다")
    void jobsEnabledByDefault() {
        assertThat(scheduled.getScheduledTasks()).hasSize(5);
    }
}
