package com.blognm.mainblog.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.auth.AdminTokenCheck;
import com.blognm.mainblog.auth.JwtProvider;
import com.blognm.mainblog.auth.LoginGuard;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.Hashing;
import com.blognm.mainblog.config.AppProperties;
import com.blognm.mainblog.member.MemberRules;
import com.blognm.mainblog.ratelimit.RateLimiter;

/**
 * 관리자 계정·로그인 (SEC-09, 추천안 #16 확인 대기). 회원가입으로는 만들 수 없고,
 * 첫 관리자는 환경변수로, 그 다음부터는 관리자가 관리자 페이지에서 만든다(활동 기록에 남음).
 * 임시 비밀번호로 들어오면 비밀번호를 바꾸기 전에는 다른 기능을 쓸 수 없다.
 */
@Service
public class AdminAccountService implements AdminTokenCheck, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);
    private static final String LOGIN_ID_PATTERN = "^[a-z0-9._-]{4,30}$";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtProvider jwt;
    private final RateLimiter rateLimiter;
    private final LoginGuard guard;
    private final AdminProperties props;
    private final AdminLog adminLog;
    private final AppProperties appProps;
    private final Clock clock;
    /** 없는 아이디에도 같은 bcrypt 비교를 해서, 응답 시간으로 아이디가 있는지 알아내지 못하게 한다 (D-127). */
    private final String dummyHash;

    public AdminAccountService(JdbcTemplate jdbc, PasswordEncoder encoder, JwtProvider jwt, RateLimiter rateLimiter,
            LoginGuard guard, AdminProperties props, AdminLog adminLog, AppProperties appProps, Clock clock) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
        this.rateLimiter = rateLimiter;
        this.guard = guard;
        this.props = props;
        this.adminLog = adminLog;
        this.appProps = appProps;
        this.clock = clock;
        this.dummyHash = encoder.encode(Hashing.randomToken());
    }

    /** 관리자가 한 명도 없고 환경변수가 있으면 첫 관리자를 만든다. 이미 있으면 아무것도 하지 않는다. */
    @Override
    public void run(ApplicationArguments args) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM admins", Integer.class);
        if (n != null && n > 0) {
            return;
        }
        String id = props.initialLoginId() == null ? "" : props.initialLoginId().strip().toLowerCase();
        String pw = props.initialPassword() == null ? "" : props.initialPassword();
        if (id.isEmpty() || pw.isEmpty()) {
            log.info("관리자 계정이 없습니다. ADMIN_INITIAL_LOGIN_ID와 ADMIN_INITIAL_PASSWORD를 넣고 서버를 다시 띄우면 첫 관리자가 만들어집니다.");
            return;
        }
        jdbc.update("""
                INSERT INTO admins (login_id, password_hash, name, must_change_password, created_at) VALUES (?, ?, ?, TRUE, ?)
                """, id, encoder.encode(pw), "관리자", LocalDateTime.now(clock));
        log.info("첫 관리자 '{}'를 만들었습니다. /admin/login.html 에서 로그인하면 비밀번호를 바꾸게 됩니다.", id);
    }

    /**
     * 로그인. 틀리면 어느 쪽이 틀렸는지 알리지 않고, IP마다 1분에 정해 둔 횟수까지만 받는다.
     * 회원 로그인처럼 아이디마다 3번 틀리면 사람 확인, 5번이면 5분 잠금 (SEC-03, SEC-13, D-115).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Login login(String rawId, String password, String captchaToken, String clientIp) {
        if (!rateLimiter.tryAcquire("admin-login:" + clientIp, props.loginPerMinute(), Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "로그인을 너무 자주 시도했습니다. 잠시 뒤에 다시 해 주세요.");
        }
        String id = rawId == null ? "" : rawId.strip().toLowerCase();
        // 아이디는 30자까지라 그보다 긴 값은 잘라서 센다 (실패 표의 열 길이 안에 두기)
        LoginGuard.Attempt attempt = guard.begin(LoginGuard.adminKey(id.length() > 60 ? id.substring(0, 60) : id),
                clientIp, captchaToken);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, password_hash, must_change_password FROM admins WHERE login_id = ?", id);
        String hash = rows.isEmpty() ? dummyHash : (String) rows.get(0).get("password_hash");
        boolean matches = password != null && encoder.matches(password, hash);
        if (rows.isEmpty() || !matches) {
            throw guard.fail(attempt, "아이디 또는 비밀번호가 맞지 않습니다.");
        }
        guard.succeed(attempt);
        long adminId = ((Number) rows.get(0).get("id")).longValue();
        jdbc.update("UPDATE admins SET last_login_at = ? WHERE id = ?", LocalDateTime.now(clock), adminId);
        return new Login(jwt.createAdminToken(adminId, props.sessionTtl()), Boolean.TRUE.equals(rows.get(0).get("must_change_password")));
    }

    @Transactional(readOnly = true)
    public Me me(long adminId) {
        return jdbc.query("SELECT id, login_id, name, must_change_password FROM admins WHERE id = ?",
                (rs, i) -> new Me(rs.getLong("id"), rs.getString("login_id"), rs.getString("name"),
                        rs.getBoolean("must_change_password")), adminId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "다시 로그인해 주세요."));
    }

    /** 비밀번호 바꾸기. 바꾸면 그 전에 받은 로그인은 모두 무효가 되고 새 토큰을 준다. */
    @Transactional
    public String changePassword(long adminId, String current, String next) {
        // 지금 비밀번호 맞혀 보기는 관리자마다 1시간에 정해 둔 횟수까지 (D-127)
        if (!rateLimiter.tryAcquire("admin-password-check:" + adminId, appProps.rateLimit().passwordCheckPerHour(),
                Duration.ofHours(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "비밀번호 확인을 너무 많이 시도했습니다. 1시간 뒤에 다시 시도해 주세요.");
        }
        String hash = jdbc.queryForObject("SELECT password_hash FROM admins WHERE id = ?", String.class, adminId);
        if (current == null || !encoder.matches(current, hash)) {
            throw ApiException.badRequest("WRONG_PASSWORD", "지금 비밀번호가 맞지 않습니다.");
        }
        MemberRules.checkPassword(next);
        if (encoder.matches(next, hash)) {
            throw ApiException.badRequest("SAME_PASSWORD", "지금과 다른 비밀번호를 써 주세요.");
        }
        // 토큰 발급 시각은 초 단위라, 바꾼 시각도 초 단위로 내려 둔다
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("UPDATE admins SET password_hash = ?, must_change_password = FALSE, password_changed_at = ? WHERE id = ?",
                encoder.encode(next), now, adminId);
        return jwt.createAdminToken(adminId, props.sessionTtl());
    }

    /** 새 관리자. 임시 비밀번호를 받은 사람은 첫 로그인에서 바꾼다. */
    @Transactional
    public long create(long creatorId, String rawId, String name, String tempPassword) {
        String id = rawId == null ? "" : rawId.strip().toLowerCase();
        if (!id.matches(LOGIN_ID_PATTERN)) {
            throw ApiException.badRequest("INVALID_LOGIN_ID", "아이디는 영문 소문자·숫자·. _ - 로 4~30자입니다.");
        }
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 50) {
            throw ApiException.badRequest("INVALID_NAME", "이름을 1~50자로 써 주세요.");
        }
        MemberRules.checkPassword(tempPassword);
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM admins WHERE login_id = ?", Integer.class, id);
        if (dup != null && dup > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "LOGIN_ID_TAKEN", "이미 쓰이는 아이디입니다.");
        }
        jdbc.update("""
                INSERT INTO admins (login_id, password_hash, name, must_change_password, created_by, created_at)
                VALUES (?, ?, ?, TRUE, ?, ?)
                """, id, encoder.encode(tempPassword), n, creatorId, LocalDateTime.now(clock));
        long newId = jdbc.queryForObject("SELECT id FROM admins WHERE login_id = ?", Long.class, id);
        adminLog.write(creatorId, AdminLog.Action.CREATE_ADMIN, "ADMIN", newId, id);
        return newId;
    }

    @Transactional(readOnly = true)
    public List<AdminRow> list() {
        return jdbc.query("SELECT id, login_id, name, created_at, last_login_at FROM admins ORDER BY id",
                (rs, i) -> new AdminRow(rs.getLong("id"), rs.getString("login_id"), rs.getString("name"),
                        rs.getObject("created_at", LocalDateTime.class).toString().replace('T', ' ').substring(0, 16),
                        rs.getObject("last_login_at", LocalDateTime.class) == null ? null
                                : rs.getObject("last_login_at", LocalDateTime.class).toString().replace('T', ' ').substring(0, 16)));
    }

    /** 비밀번호를 바꾸기 전에는 관리 기능을 쓰지 못한다. */
    @Transactional(readOnly = true)
    public void requireReady(long adminId) {
        if (me(adminId).mustChangePassword()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED", "임시 비밀번호를 먼저 바꿔 주세요.");
        }
    }

    @Override
    public boolean isValid(JwtProvider.MemberToken token) {
        List<LocalDateTime> changed = jdbc.query("SELECT password_changed_at FROM admins WHERE id = ?",
                (rs, i) -> rs.getObject("password_changed_at", LocalDateTime.class), token.memberId());
        if (changed.isEmpty()) {
            return false;
        }
        LocalDateTime at = changed.get(0);
        return at == null || !token.issuedAt().isBefore(at.atZone(clock.getZone() == null ? ZoneId.systemDefault()
                : clock.getZone()).toInstant());
    }

    public record Login(String token, boolean mustChangePassword) {
    }

    public record Me(long id, String loginId, String name, boolean mustChangePassword) {
    }

    public record AdminRow(long id, String loginId, String name, String createdAt, String lastLoginAt) {
    }
}
