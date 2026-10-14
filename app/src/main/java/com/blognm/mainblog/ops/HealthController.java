package com.blognm.mainblog.ops;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배포 스크립트가 새 컨테이너가 손님을 받을 준비가 됐는지 확인하는 주소 (D-111).
 * DB에 SELECT 1 한 번만 묻는다. DB가 안 되면 503을 돌려주어 배포가 다음 서버로 넘어가지 않게 한다.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;
    private final String instanceId;

    public HealthController(JdbcTemplate jdbc, @Value("${app.instance-id:}") String instanceId) {
        this.jdbc = jdbc;
        this.instanceId = instanceId == null ? "" : instanceId.trim();
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean db;
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            db = one != null && one == 1;
        } catch (RuntimeException e) {
            db = false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", db ? "UP" : "DOWN");
        body.put("instance", instanceId);
        return ResponseEntity.status(db ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
