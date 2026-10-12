package com.blognm.mainblog.blog;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.DetailedApiException;
import com.blognm.mainblog.common.Hashing;
import com.blognm.mainblog.member.Member;
import com.blognm.mainblog.member.MemberRepository;
import com.blognm.mainblog.notification.NotificationWriter;
import com.blognm.mainblog.notification.NotificationWriter.Type;
import com.blognm.mainblog.social.BlockService;

/** 블로그 만들기·정보 수정·참여 신청·승인·탈퇴 (BLG-01, 04~07, 10). */
@Service
public class BlogService {

    private static final List<Blog.Status> OPERATING = List.of(Blog.Status.ACTIVE, Blog.Status.CLOSING);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final BlogRepository blogs;
    private final BlogMembershipRepository memberships;
    private final JoinRequestRepository joinRequests;
    private final TagRepository tags;
    private final MemberRepository members;
    private final NotificationWriter notifications;
    private final BlogProperties props;
    private final BlockService blocks;
    private final SanctionService sanctions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BlogService(BlogRepository blogs, BlogMembershipRepository memberships, JoinRequestRepository joinRequests,
            TagRepository tags, MemberRepository members, NotificationWriter notifications, BlogProperties props,
            BlockService blocks, SanctionService sanctions, JdbcTemplate jdbc, Clock clock) {
        this.sanctions = sanctions;
        this.blogs = blogs;
        this.memberships = memberships;
        this.joinRequests = joinRequests;
        this.tags = tags;
        this.members = members;
        this.notifications = notifications;
        this.props = props;
        this.blocks = blocks;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ----- 만들기 (BLG-01, BLG-10) -----

    @Transactional
    public String create(long memberId, CreateRequest req) {
        String slug = BlogRules.normalizeSlug(req.slug());
        String name = BlogRules.normalizeName(req.name());
        String description = BlogRules.normalizeDescription(req.description());
        List<String> tagNames = BlogRules.normalizeTags(req.tags());
        Blog.Visibility visibility = required(req.visibility(), "공개 범위를 골라 주세요.");
        Blog.JoinPolicy joinPolicy = required(req.joinPolicy(), "참여 방식을 골라 주세요.");
        LocalDateTime now = LocalDateTime.now(clock);

        // 같은 회원의 만들기 요청을 줄 세운 뒤 개수를 센다. 동시에 눌러도 제한을 넘지 않는다 (D-68).
        members.findForUpdate(memberId).filter(Member::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "다시 로그인해 주세요."));
        checkLimit(memberId, visibility, null);

        if (blogs.existsBySlug(slug)) {
            throw slugTaken();
        }
        Blog blog = Blog.create(slug, name, description, memberId, visibility, joinPolicy, now);
        // 만들 때 테마도 고를 수 있다. 고르지 않으면 기본 틀(Odyssey) (D-101)
        if (req.theme() != null) {
            applyTheme(blog, req.theme());
        }
        blog.replaceTags(findOrCreateTags(tagNames));
        try {
            blogs.saveAndFlush(blog);
        } catch (DataIntegrityViolationException e) {
            throw slugTaken();
        }
        memberships.save(BlogMembership.owner(blog.getId(), memberId, now));
        return slug;
    }

    public boolean isSlugAvailable(String rawSlug) {
        return !blogs.existsBySlug(BlogRules.normalizeSlug(rawSlug));
    }

    /** 공개(일부 공개 포함)와 비공개를 따로 센다. 폐쇄 예정 블로그도 센다 (BLG-10). */
    private void checkLimit(long ownerId, Blog.Visibility visibility, Long exceptBlogId) {
        boolean wantPublic = visibility != Blog.Visibility.PRIVATE;
        long count = blogs.findOwned(ownerId, OPERATING).stream()
                .filter(b -> !b.getId().equals(exceptBlogId))
                .filter(b -> b.countsAsPublic() == wantPublic)
                .count();
        int limit = wantPublic ? props.limit().publicCount() : props.limit().privateCount();
        if (count >= limit) {
            throw new ApiException(HttpStatus.CONFLICT, "BLOG_LIMIT",
                    wantPublic
                            ? "공개·일부 공개 블로그는 %d개까지 만들 수 있습니다.".formatted(limit)
                            : "비공개 블로그는 %d개까지 만들 수 있습니다.".formatted(limit));
        }
    }

    /** 위임을 받는 사람도 블로그 개수 제한을 넘을 수 없다 (BLG-10). 받는 사람 행을 잠그고 센다. */
    @Transactional
    public void checkOwnerLimit(long memberId, long blogId) {
        Blog blog = blogs.findById(blogId).orElseThrow(BlogService::notFound);
        members.findForUpdate(memberId);
        checkLimit(memberId, blog.getVisibility(), blogId);
    }

    private Set<Tag> findOrCreateTags(List<String> names) {
        Set<Tag> result = new LinkedHashSet<>();
        for (String name : names) {
            tags.insertIfAbsent(name);
            result.add(tags.findByName(name).orElseThrow());
        }
        return result;
    }

    // ----- 블로그 정보 (BLG-01) -----

    /**
     * 블로그 화면에 필요한 정보. 볼 수 없는 블로그는 있는지조차 알리지 않고 404로 둔다.
     * 일부 공개는 공유 링크 값(key)이 맞거나 멤버일 때만 보인다 (D-49, D-50).
     */
    @Transactional(readOnly = true)
    public BlogView view(String slug, String key, Long viewerId) {
        Blog blog = visibleBlog(slug, key, viewerId);
        Optional<BlogMembership> mine = viewerId == null ? Optional.empty()
                : memberships.findByBlogIdAndMemberId(blog.getId(), viewerId).filter(BlogMembership::isActive);
        LocalDateTime now = LocalDateTime.now(clock);
        String requestStatus = null;
        LocalDateTime canRequestAt = null;
        if (viewerId != null && mine.isEmpty()) {
            Optional<JoinRequest> last = joinRequests.findFirstByBlogIdAndMemberIdOrderByIdDesc(blog.getId(), viewerId);
            if (last.isPresent() && last.get().isPending()) {
                requestStatus = "PENDING";
            } else if (last.isPresent() && last.get().getStatus() == JoinRequest.Status.REJECTED) {
                LocalDateTime again = last.get().getProcessedAt().plus(props.rejoinAfterReject());
                if (again.isAfter(now)) {
                    requestStatus = "REJECTED";
                    canRequestAt = again;
                }
            }
        }
        String role = mine.map(m -> m.getRole().name()).orElse(null);
        boolean owner = mine.filter(m -> m.getRole() == BlogMembership.Role.OWNER).isPresent();
        boolean managePosts = mine.filter(BlogMembership::canManagePosts).isPresent();
        boolean editInfo = mine.filter(BlogMembership::canEditInfo).isPresent();
        Member ownerMember = members.findById(blog.getOwnerId()).orElseThrow();
        // 기다리는 위임 요청: 받는 사람에게는 [수락]·[거절], 블로그장에게는 누구에게 보냈는지 (BLG-08)
        TransferView transfer = null;
        if (mine.isPresent()) {
            transfer = jdbc.query("""
                    SELECT t.to_member_id, f.nickname AS from_name, m.nickname AS to_name
                    FROM ownership_transfers t JOIN members m ON m.id = t.to_member_id JOIN members f ON f.id = t.from_member_id
                    WHERE t.blog_id = ? AND t.status = 'PENDING' ORDER BY t.id DESC LIMIT 1
                    """, (rs, i) -> new TransferView(rs.getString("from_name"), rs.getString("to_name"),
                    rs.getLong("to_member_id") == viewerId), blog.getId()).stream()
                    .filter(t -> t.toMe() || owner).findFirst().orElse(null);
        }
        String closeBy = jdbc.queryForObject("SELECT close_requested_by FROM blogs WHERE id = ?", String.class,
                blog.getId());
        return new BlogView(blog.getSlug(), blog.getName(), blog.getDescription(), blog.getVisibility().name(),
                blog.getJoinPolicy().name(), blog.getStatus().name(), blog.getCloseScheduledAt(),
                ownerMember.getNickname(), memberships.countActive(blog.getId()), tagNames(blog),
                blog.getCreatedAt().format(DATE), role, requestStatus,
                canRequestAt == null ? null : canRequestAt.format(DATE),
                owner ? blog.getShareToken() : null, managePosts, editInfo, themeView(blog), blog.getOwnerId(),
                count("SELECT COUNT(*) FROM subscriptions WHERE blog_id = ?", blog.getId()),
                viewerId != null && count("SELECT COUNT(*) FROM subscriptions WHERE blog_id = ? AND member_id = ?",
                        blog.getId(), viewerId) > 0, closeBy, transfer, blog.isHidden());
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private ThemeView themeView(Blog blog) {
        return new ThemeView(blog.getTheme().name(), blog.getThemeAccent(), blog.getThemeFont().name(),
                blog.getHeroTitle(), blog.getHeroSubtitle(),
                blogs.coverImageName(blog.getId()).map(name -> "/uploads/" + name).orElse(null));
    }

    // ----- 테마 (D-89) -----

    /** 테마 틀·강조 색·글꼴·머리 문구. 정보 수정 권한이 있으면 바꾼다 (권한은 컨트롤러가 먼저 확인). */
    @Transactional
    public void updateTheme(String slug, ThemeRequest req) {
        Blog blog = blogs.findBySlug(slug).filter(b -> !b.isClosed()).orElseThrow(BlogService::notFound);
        applyTheme(blog, req);
    }

    private static void applyTheme(Blog blog, ThemeRequest req) {
        if (req.theme() == null) {
            throw ApiException.badRequest("INVALID_THEME", "테마 틀을 골라 주세요.");
        }
        blog.changeTheme(req.theme(), BlogRules.normalizeAccent(req.accent()),
                req.font() == null ? Blog.Font.SANS : req.font(),
                BlogRules.normalizeHeroText(req.heroTitle(), BlogRules.HERO_TITLE_MAX, "큰 문구"),
                BlogRules.normalizeHeroText(req.heroSubtitle(), BlogRules.HERO_SUBTITLE_MAX, "작은 문구"));
    }

    /** 머리 사진(블로그 대표 이미지)을 바꾼다. 미리보기 사진이 없는 글의 공유 이미지로도 쓴다 (D-59). */
    @Transactional
    public String changeCover(String slug, Long imageId) {
        Blog blog = blogs.findBySlug(slug).filter(b -> !b.isClosed()).orElseThrow(BlogService::notFound);
        blog.changeCover(imageId);
        blogs.flush();
        return blogs.coverImageName(blog.getId()).map(name -> "/uploads/" + name).orElse(null);
    }

    /**
     * 이 사람이 볼 수 있는 블로그. 글 화면(004)도 이것으로 확인한다.
     * 볼 수 없으면 404, 그 블로그에서 정지 중인 멤버는 403 SUSPENDED.
     */
    @Transactional(readOnly = true)
    public Blog visibleBlog(String slug, String key, Long viewerId) {
        Blog blog = findVisible(slug, key, viewerId);
        if (viewerId != null) {
            LocalDateTime now = LocalDateTime.now(clock);
            Optional<BlogMembership> mine = memberships.findByBlogIdAndMemberId(blog.getId(), viewerId)
                    .filter(BlogMembership::isActive);
            if (mine.isPresent() && mine.get().getSuspendedUntil() != null
                    && mine.get().getSuspendedUntil().isAfter(now)) {
                Map<String, Object> details = new java.util.HashMap<>();
                details.put("suspendedUntil", SanctionService.untilText(mine.get().getSuspendedUntil()));
                sanctions.lastSuspendReason(blog.getId(), viewerId).ifPresent(r -> details.put("reason", r));
                throw new DetailedApiException(HttpStatus.FORBIDDEN, "SUSPENDED", "이 블로그에서 활동이 정지되었습니다.",
                        details);
            }
        }
        return blog;
    }

    private Blog findVisible(String slug, String key, Long viewerId) {
        Blog blog = blogs.findBySlug(slug == null ? "" : slug.toLowerCase()).orElseThrow(BlogService::notFound);
        if (blog.isClosed()) {
            throw notFound();
        }
        boolean member = viewerId != null && memberships.findByBlogIdAndMemberId(blog.getId(), viewerId)
                .filter(BlogMembership::isActive).isPresent();
        if (member) {
            return blog;
        }
        if (blog.isHidden()) {
            throw notFound();
        }
        return switch (blog.getVisibility()) {
            case PUBLIC -> blog;
            case LINK_ONLY -> {
                if (key != null && blog.getShareToken() != null
                        && Hashing.constantTimeEquals(blog.getShareToken(), key)) {
                    yield blog;
                }
                throw notFound();
            }
            case PRIVATE -> throw notFound();
        };
    }

    // ----- 정보 수정 (BLG-01) -----

    /** 이름·소개·태그는 정보 수정 권한이 있으면, 공개 범위·참여 방식은 블로그장만 바꾼다. */
    @Transactional
    public void update(String slug, long actorId, UpdateRequest req) {
        Blog blog = blogs.findBySlug(slug).filter(b -> !b.isClosed()).orElseThrow(BlogService::notFound);
        blog.changeInfo(BlogRules.normalizeName(req.name()), BlogRules.normalizeDescription(req.description()));
        blog.replaceTags(findOrCreateTags(BlogRules.normalizeTags(req.tags())));

        boolean policyChange = (req.visibility() != null && req.visibility() != blog.getVisibility())
                || (req.joinPolicy() != null && req.joinPolicy() != blog.getJoinPolicy());
        if (!policyChange) {
            return;
        }
        if (!blog.getOwnerId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "공개 범위와 참여 방식은 블로그장만 바꿀 수 있습니다.");
        }
        if (req.visibility() != null && req.visibility() != blog.getVisibility()) {
            boolean toPublic = req.visibility() != Blog.Visibility.PRIVATE;
            if (toPublic != blog.countsAsPublic()) {
                members.findForUpdate(actorId);
                checkLimit(actorId, req.visibility(), blog.getId());
            }
            boolean toPrivate = req.visibility() == Blog.Visibility.PRIVATE;
            blog.changeVisibility(req.visibility());
            if (toPrivate) {
                notifyPrivate(blog.getId(), actorId);
            }
        }
        if (req.joinPolicy() != null) {
            blog.changeJoinPolicy(req.joinPolicy());
        }
    }

    /** 비공개로 바뀌면 멤버가 아닌 구독자에게 알린다. 구독은 그대로지만 글은 안 보인다 (D-37). */
    private void notifyPrivate(long blogId, long actorId) {
        jdbc.queryForList("""
                SELECT s.member_id FROM subscriptions s JOIN members m ON m.id = s.member_id
                WHERE s.blog_id = ? AND m.status = 'ACTIVE' AND NOT EXISTS (
                  SELECT 1 FROM blog_memberships bm WHERE bm.blog_id = s.blog_id AND bm.member_id = s.member_id
                    AND bm.status = 'ACTIVE')
                """, Long.class, blogId).forEach(id -> notifications.blog(id, Type.BLOG_PRIVATE, blogId, actorId));
    }

    @Transactional
    public String regenerateShareLink(String slug) {
        Blog blog = blogs.findBySlug(slug).filter(b -> !b.isClosed()).orElseThrow(BlogService::notFound);
        if (blog.getVisibility() != Blog.Visibility.LINK_ONLY) {
            throw ApiException.badRequest("NOT_LINK_ONLY", "일부 공개 블로그만 공유 링크가 있습니다.");
        }
        blog.regenerateShareToken();
        return blog.getShareToken();
    }

    // ----- 참여 신청 (BLG-04) -----

    /**
     * 자유 참여는 바로 멤버, 승인제는 신청 대기. 대기 중이면 다시 신청할 수 없고, 거절되면 7일 뒤에 다시 신청한다.
     * 스스로 나간 멤버는 바로 다시 신청할 수 있다 (추천안 #8).
     *
     * @return true면 바로 멤버가 됨, false면 승인 대기
     */
    @Transactional
    public boolean join(String slug, String key, long memberId) {
        Blog blog = findVisible(slug, key, memberId);
        if (blog.getVisibility() == Blog.Visibility.PRIVATE) {
            throw notFound();
        }
        if (blog.getStatus() == Blog.Status.CLOSING) {
            throw new ApiException(HttpStatus.CONFLICT, "BLOG_CLOSING", "폐쇄 예정인 블로그에는 참여할 수 없습니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 두 번 눌러도 신청이 두 개 생기지 않게 신청자 행을 잠근다.
        members.findForUpdate(memberId);

        Optional<BlogMembership> existing = memberships.findByBlogIdAndMemberId(blog.getId(), memberId);
        if (existing.isPresent() && existing.get().isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_MEMBER", "이미 이 블로그의 멤버입니다.");
        }
        // 강제 퇴장된 사람의 이메일·전화번호로는 다시 가입해도 신청할 수 없다 (BLG-11)
        sanctions.requireNotBlacklisted(blog.getId(), memberId);
        if (existing.isPresent() && existing.get().getStatus() == BlogMembership.Status.KICKED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "JOIN_BLOCKED", "이 블로그에는 참여할 수 없습니다.");
        }
        // 블로그장과 서로 차단한 사이면 참여할 수 없다 (D-36, 추천안 #10).
        if (blocks.between(blog.getOwnerId(), memberId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "JOIN_BLOCKED", "이 블로그에는 참여할 수 없습니다.");
        }
        Optional<JoinRequest> last = joinRequests.findFirstByBlogIdAndMemberIdOrderByIdDesc(blog.getId(), memberId);
        if (last.isPresent() && last.get().isPending()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REQUESTED", "이미 참여 신청을 했습니다. 승인을 기다려 주세요.");
        }
        if (last.isPresent() && last.get().getStatus() == JoinRequest.Status.REJECTED) {
            LocalDateTime again = last.get().getProcessedAt().plus(props.rejoinAfterReject());
            if (again.isAfter(now)) {
                throw new ApiException(HttpStatus.CONFLICT, "REJECTED_RECENTLY",
                        "참여 신청이 거절되어 %s부터 다시 신청할 수 있습니다.".formatted(again.format(DATE)));
            }
        }

        if (blog.getJoinPolicy() == Blog.JoinPolicy.FREE) {
            addMember(blog.getId(), memberId, existing, now);
            return true;
        }
        joinRequests.save(JoinRequest.pending(blog.getId(), memberId, now));
        managerIds(blog).forEach(id -> notifications.blog(id, Type.JOIN_REQUESTED, blog.getId(), memberId));
        return false;
    }

    @Transactional
    public void cancelRequest(String slug, long memberId) {
        Blog blog = blogs.findBySlug(slug).orElseThrow(BlogService::notFound);
        joinRequests.findFirstByBlogIdAndMemberIdOrderByIdDesc(blog.getId(), memberId)
                .filter(JoinRequest::isPending)
                .ifPresentOrElse(joinRequests::delete, () -> {
                    throw ApiException.badRequest("NO_PENDING_REQUEST", "대기 중인 참여 신청이 없습니다.");
                });
    }

    // ----- 참여 승인·거절 (BLG-05). 권한은 컨트롤러의 @PreAuthorize가 먼저 확인한다. -----

    @Transactional(readOnly = true)
    public List<PendingRequest> pendingRequests(String slug) {
        Blog blog = blogs.findBySlug(slug).orElseThrow(BlogService::notFound);
        return joinRequests.findPending(blog.getId()).stream().map(r -> {
            Member m = members.findById(r.getMemberId()).orElseThrow();
            return new PendingRequest(r.getId(), m.getNickname(), r.getRequestedAt());
        }).toList();
    }

    @Transactional
    public void decide(String slug, long requestId, long actorId, boolean approve) {
        Blog blog = blogs.findBySlug(slug).orElseThrow(BlogService::notFound);
        JoinRequest request = joinRequests.findForUpdate(requestId)
                // 다른 블로그의 신청 번호를 넣어도 처리되지 않는다 (SEC-07)
                .filter(r -> r.getBlogId().equals(blog.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "참여 신청을 찾을 수 없습니다."));
        if (!request.isPending()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PROCESSED", "이미 처리된 신청입니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (approve) {
            request.approve(actorId, now);
            Optional<BlogMembership> existing = memberships.findByBlogIdAndMemberId(blog.getId(), request.getMemberId());
            if (existing.isEmpty() || !existing.get().isActive()) {
                addMember(blog.getId(), request.getMemberId(), existing, now);
            }
        } else {
            request.reject(actorId, now);
        }
        notifications.blog(request.getMemberId(), approve ? Type.JOIN_APPROVED : Type.JOIN_REJECTED, blog.getId(),
                actorId);
    }

    private void addMember(long blogId, long memberId, Optional<BlogMembership> existing, LocalDateTime now) {
        if (existing.isPresent()) {
            existing.get().rejoin(now);
        } else {
            memberships.save(BlogMembership.member(blogId, memberId, now));
        }
    }

    /** 참여 신청 알림을 받을 사람: 블로그장과 멤버 관리 권한이 있는 부블로그장. */
    private List<Long> managerIds(Blog blog) {
        return memberships.findActiveByBlogId(blog.getId()).stream()
                .filter(BlogMembership::canManageMembers)
                .map(BlogMembership::getMemberId)
                .toList();
    }

    // ----- 블로그 탈퇴 (BLG-07) -----

    @Transactional
    public void leave(String slug, long memberId) {
        Blog blog = blogs.findBySlug(slug).filter(b -> !b.isClosed()).orElseThrow(BlogService::notFound);
        BlogMembership membership = memberships.findByBlogIdAndMemberId(blog.getId(), memberId)
                .filter(BlogMembership::isActive)
                .orElseThrow(() -> ApiException.badRequest("NOT_MEMBER", "이 블로그의 멤버가 아닙니다."));
        if (membership.getRole() == BlogMembership.Role.OWNER) {
            throw new ApiException(HttpStatus.CONFLICT, "OWNER_CANNOT_LEAVE",
                    "블로그장은 블로그장을 넘기거나 블로그를 닫은 뒤에만 나갈 수 있습니다.");
        }
        membership.leave(LocalDateTime.now(clock));
        // 나간 멤버의 글은 남기고 작성자를 "탈퇴한 계정"으로 보인다 (D-33)
        blogs.markPostsAuthorLeft(blog.getId(), memberId);
    }

    // ----- 공통 -----

    static List<String> tagNames(Blog blog) {
        return blog.getTags().stream().map(Tag::getName).toList();
    }

    private static <T> T required(T value, String message) {
        if (value == null) {
            throw ApiException.badRequest("INVALID_INPUT", message);
        }
        return value;
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "BLOG_NOT_FOUND", "블로그를 찾을 수 없습니다.");
    }

    private static ApiException slugTaken() {
        return new ApiException(HttpStatus.CONFLICT, "SLUG_TAKEN", "이미 쓰이는 주소입니다. 다른 주소를 정해 주세요.");
    }

    /** theme은 없어도 된다 (만들기 화면에서 테마를 고르면 함께 온다, D-101). */
    public record CreateRequest(String slug, String name, String description, Blog.Visibility visibility,
            Blog.JoinPolicy joinPolicy, List<String> tags, ThemeRequest theme) {
    }

    public record UpdateRequest(String name, String description, Blog.Visibility visibility,
            Blog.JoinPolicy joinPolicy, List<String> tags) {
    }

    public record BlogView(String slug, String name, String description, String visibility, String joinPolicy,
            String status, LocalDateTime closeScheduledAt, String ownerNickname, long memberCount, List<String> tags,
            String createdAt, String myRole, String myRequest, String canRequestAt, String shareToken,
            boolean canManagePosts, boolean canEditInfo, ThemeView theme, long ownerId, long subscribers,
            boolean subscribed, String closeRequestedBy, TransferView transfer, boolean hidden) {
    }

    /** toMe면 내가 받은 요청. 아니면 블로그장이 보낸 요청. */
    public record TransferView(String fromNickname, String toNickname, boolean toMe) {
    }

    public record ThemeView(String theme, String accent, String font, String heroTitle, String heroSubtitle,
            String coverUrl) {
    }

    public record ThemeRequest(Blog.Theme theme, String accent, Blog.Font font, String heroTitle,
            String heroSubtitle) {
    }

    public record PendingRequest(long id, String nickname, LocalDateTime requestedAt) {
    }
}
