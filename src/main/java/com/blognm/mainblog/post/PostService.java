package com.blognm.mainblog.post;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.blog.Blog;
import com.blognm.mainblog.blog.BlogMembership;
import com.blognm.mainblog.blog.BlogMembershipRepository;
import com.blognm.mainblog.blog.BlogRepository;
import com.blognm.mainblog.blog.BlogService;
import com.blognm.mainblog.blog.Tag;
import com.blognm.mainblog.blog.TagRepository;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.image.Image;
import com.blognm.mainblog.image.ImageRepository;
import com.blognm.mainblog.image.UploadProperties;
import com.blognm.mainblog.linkpreview.LinkPreviewService;
import com.blognm.mainblog.member.Member;
import com.blognm.mainblog.member.MemberRepository;
import com.blognm.mainblog.notification.NotificationWriter;

/**
 * 글 쓰기·읽기·수정·삭제 (BRD-01, 004 US1·US2).
 * 수정은 작성자만, 삭제는 작성자와 블로그장(글 관리 권한이 있는 부블로그장 포함). 블로그장도 남의 글은 못 고친다 (D-07, D-58).
 * 역할은 매 요청 DB로 다시 본다 (헌법 II, SEC-07).
 */
@Service
public class PostService {

    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
    private static final Pattern IMAGE_REF = Pattern.compile("/uploads/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    private final PostRepository posts;
    private final CategoryRepository categories;
    private final BlogRepository blogs;
    private final BlogService blogService;
    private final BlogMembershipRepository memberships;
    private final TagRepository tags;
    private final ImageRepository images;
    private final MemberRepository members;
    private final MarkdownRenderer markdown;
    private final LinkPreviewService linkPreviews;
    private final NotificationWriter notifications;
    private final UploadProperties uploadProps;
    private final Clock clock;

    public PostService(PostRepository posts, CategoryRepository categories, BlogRepository blogs,
            BlogService blogService, BlogMembershipRepository memberships, TagRepository tags, ImageRepository images,
            MemberRepository members, MarkdownRenderer markdown, NotificationWriter notifications,
            UploadProperties uploadProps, LinkPreviewService linkPreviews, Clock clock) {
        this.linkPreviews = linkPreviews;
        this.posts = posts;
        this.categories = categories;
        this.blogs = blogs;
        this.blogService = blogService;
        this.memberships = memberships;
        this.tags = tags;
        this.images = images;
        this.members = members;
        this.markdown = markdown;
        this.notifications = notifications;
        this.uploadProps = uploadProps;
        this.clock = clock;
    }

    // ----- 쓰기 (US1) -----

    /** 그 블로그의 멤버만 쓴다. 폐쇄 예정 7일 동안에도 쓸 수 있다 (D-67). 공지는 글 관리 권한이 있어야 한다. */
    @Transactional
    public long write(String slug, long actorId, WriteRequest req) {
        Blog blog = blogService.visibleBlog(slug, null, actorId);
        BlogMembership me = activeMembership(blog, actorId)
                .orElseThrow(() -> forbidden("NOT_MEMBER", "이 블로그의 멤버만 글을 쓸 수 있습니다."));
        if (req.wantsNotice() && !me.canManagePosts()) {
            throw forbidden("FORBIDDEN", "공지는 블로그장과 글 관리 권한이 있는 부블로그장만 쓸 수 있습니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Post post = Post.write(blog.getId(), actorId, now);
        apply(post, blog, req, now);
        post.markNotice(req.wantsNotice());
        posts.save(post);
        attachImages(post, actorId);
        return post.getId();
    }

    /** 작성자만 고친다. 블로그장·관리자도 남의 글은 못 고친다 (US2-1, US2-3). */
    @Transactional
    public void update(long postId, long actorId, WriteRequest req) {
        Post post = findNormal(postId);
        Blog blog = blogs.findById(post.getBlogId()).orElseThrow(PostService::notFound);
        blogService.visibleBlog(blog.getSlug(), null, actorId);
        BlogMembership me = activeMembership(blog, actorId).orElse(null);
        if (me == null || !isAuthor(post, actorId)) {
            throw forbidden("NOT_AUTHOR", "글은 쓴 사람만 고칠 수 있습니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        apply(post, blog, req, now);
        if (me.canManagePosts()) {
            post.markNotice(req.wantsNotice());
        }
        attachImages(post, actorId);
    }

    /**
     * 작성자 또는 블로그장(글 관리 권한이 있는 부블로그장 포함)이 지운다. 30일 동안은 화면에서만 사라진다 (US2-4).
     * 블로그장이 남의 글을 지우면 작성자에게 알린다 (D-58). 관리자 삭제는 6단계.
     */
    @Transactional
    public void delete(long postId, long actorId) {
        Post post = findNormal(postId);
        Blog blog = blogs.findById(post.getBlogId()).orElseThrow(PostService::notFound);
        blogService.visibleBlog(blog.getSlug(), null, actorId);
        BlogMembership me = activeMembership(blog, actorId)
                .orElseThrow(() -> forbidden("FORBIDDEN", "이 글을 지울 권한이 없습니다."));
        LocalDateTime now = LocalDateTime.now(clock);
        if (isAuthor(post, actorId)) {
            post.delete(Post.DeletedBy.AUTHOR, now);
        } else if (me.canManagePosts()) {
            post.delete(Post.DeletedBy.OWNER, now);
            notifications.post(post.getAuthorId(), NotificationWriter.Type.POST_DELETED, blog.getId(), actorId,
                    post.getId());
        } else {
            throw forbidden("FORBIDDEN", "이 글을 지울 권한이 없습니다.");
        }
    }

    // ----- 읽기 -----

    /** 글 상세. 블로그를 볼 수 없거나 지워진 글이면 404. 본문은 걸러낸 HTML로 내려 보낸다. */
    @Transactional(readOnly = true)
    public PostView view(long postId, String key, Long viewerId) {
        Post post = findNormal(postId);
        Blog blog = blogs.findById(post.getBlogId()).orElseThrow(PostService::notFound);
        blogService.visibleBlog(blog.getSlug(), key, viewerId);
        Optional<BlogMembership> me = viewerId == null ? Optional.empty() : activeMembership(blog, viewerId);
        boolean author = me.isPresent() && isAuthor(post, viewerId);
        String category = post.getCategoryId() == null ? null
                : categories.findById(post.getCategoryId()).map(Category::getName).orElse(null);
        return new PostView(post.getId(), blog.getSlug(), blog.getName(), post.getTitle(),
                linkPreviews.decorate(markdown.toSafeHtml(post.getContentMd())), author ? post.getContentMd() : null, authorName(post),
                post.getCategoryId(), category, tagNames(post), post.isNotice(),
                post.getCreatedAt().format(DATE_TIME),
                post.getUpdatedAt().isAfter(post.getCreatedAt()) ? post.getUpdatedAt().format(DATE_TIME) : null,
                new Counts(post.getViewCount(), post.getLikeCount(), post.getCommentCount()), author, author || me.filter(BlogMembership::canManagePosts).isPresent(),
                me.filter(BlogMembership::canManagePosts).isPresent(), false, viewerId != null, authorLink(post));
    }

    /** 프로필로 갈 작성자 번호. 블로그를 나간 작성자(탈퇴한 계정)나 탈퇴한 회원이면 null. */
    private Long authorLink(Post post) {
        if (post.isAuthorLeft()) {
            return null;
        }
        return members.findById(post.getAuthorId()).filter(Member::isActive).map(Member::getId).orElse(null);
    }

    /**
     * 카톡·X 미리보기(og 태그)용. 비회원이 볼 수 있는 글만 채운다 (D-66).
     * 볼 수 없는 블로그면 예외 대신 빈 값이라, 감싸는 트랜잭션을 두지 않는다(예외가 트랜잭션을 롤백 표시하지 않게).
     */
    public Optional<OgInfo> og(String slug, long postId, String key) {
        Optional<Post> found = posts.findById(postId).filter(Post::isNormal);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Post post = found.get();
        Blog blog;
        try {
            blog = blogService.visibleBlog(slug, key, null);
        } catch (ApiException e) {
            return Optional.empty();
        }
        if (!blog.getId().equals(post.getBlogId())) {
            return Optional.empty();
        }
        String image = images.findFirstByPostIdOrderBySortOrderAscIdAsc(post.getId()).map(Image::url)
                .orElse(coverImage(blog).orElse(null));
        return Optional.of(new OgInfo(post.getTitle(), blog.getName(), markdown.toPlainText(post.getContentMd(), 100),
                image));
    }

    /**
     * 이 사람이 읽을 수 있는 글. 댓글·좋아요·조회수(4단계)도 이것으로 확인한다.
     * 지워졌거나 블로그를 볼 수 없으면 404, 그 블로그에서 정지 중이면 403.
     */
    @Transactional(readOnly = true)
    public Readable readable(long postId, String key, Long viewerId) {
        Post post = findNormal(postId);
        Blog blog = blogs.findById(post.getBlogId()).orElseThrow(PostService::notFound);
        blogService.visibleBlog(blog.getSlug(), key, viewerId);
        Optional<BlogMembership> me = viewerId == null ? Optional.empty() : activeMembership(blog, viewerId);
        return new Readable(post, blog, me.orElse(null));
    }

    // ----- 공통 -----

    private void apply(Post post, Blog blog, WriteRequest req, LocalDateTime now) {
        String title = PostRules.normalizeTitle(req.title());
        String content = PostRules.checkContent(req.content());
        Long categoryId = req.categoryId();
        if (categoryId != null) {
            categories.findByIdAndBlogId(categoryId, blog.getId())
                    .orElseThrow(() -> ApiException.badRequest("INVALID_CATEGORY", "이 블로그의 카테고리가 아닙니다."));
        }
        Set<Tag> tagSet = new LinkedHashSet<>();
        for (String name : PostRules.normalizeTags(req.tags())) {
            tags.insertIfAbsent(name);
            tagSet.add(tags.findByName(name).orElseThrow());
        }
        post.change(title, content, categoryId, tagSet, now);
    }

    /**
     * 본문에 든 /uploads/{UUID} 사진을 순서대로 이 글에 붙인다. 첫 사진이 미리보기 사진이다 (D-59).
     * 내가 올렸거나 이미 이 글에 붙은 사진만 붙고, 본문에서 빠진 사진은 떼어 둔다(하루 뒤 정리).
     */
    private void attachImages(Post post, long actorId) {
        List<String> refs = new ArrayList<>();
        Matcher m = IMAGE_REF.matcher(post.getContentMd());
        while (m.find()) {
            if (!refs.contains(m.group(1))) {
                refs.add(m.group(1));
            }
        }
        Map<String, Image> found = images.findByStoredNameIn(refs.isEmpty() ? List.of("") : refs).stream()
                .filter(img -> post.getId().equals(img.getPostId())
                        || (img.getPostId() == null && img.getUploaderId() == actorId))
                .collect(Collectors.toMap(Image::getStoredName, Function.identity()));
        List<Image> ordered = refs.stream().map(found::get).filter(i -> i != null).toList();
        if (ordered.size() > uploadProps.maxImagesPerPost()) {
            throw ApiException.badRequest("TOO_MANY_IMAGES",
                    "사진은 글 하나에 %d장까지 넣을 수 있습니다.".formatted(uploadProps.maxImagesPerPost()));
        }
        for (Image img : images.findByPostId(post.getId())) {
            if (!ordered.contains(img)) {
                img.detach();
            }
        }
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).attachTo(post.getId(), i);
        }
    }

    private Optional<String> coverImage(Blog blog) {
        return blogs.coverImageName(blog.getId()).map(name -> "/uploads/" + name);
    }

    private Optional<BlogMembership> activeMembership(Blog blog, long memberId) {
        return memberships.findByBlogIdAndMemberId(blog.getId(), memberId).filter(BlogMembership::isActive);
    }

    /** 블로그를 나갔다 돌아온 회원은 예전 글의 작성자가 아니다 ("탈퇴한 계정", D-33). */
    private static boolean isAuthor(Post post, long memberId) {
        return post.getAuthorId() == memberId && !post.isAuthorLeft();
    }

    /** 블로그를 나가거나 강퇴되면 "탈퇴한 계정", 사이트를 탈퇴하면 "탈퇴한 회원" (004 Edge Cases). */
    String authorName(Post post) {
        if (post.isAuthorLeft()) {
            return "탈퇴한 계정";
        }
        return members.findById(post.getAuthorId()).filter(Member::isActive).map(Member::getNickname)
                .orElse("탈퇴한 회원");
    }

    private static List<String> tagNames(Post post) {
        return post.getTags().stream().map(Tag::getName).toList();
    }

    private Post findNormal(long postId) {
        return posts.findById(postId).filter(Post::isNormal).orElseThrow(PostService::notFound);
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "POST_NOT_FOUND", "글을 찾을 수 없습니다. 지워졌거나 볼 수 없는 글입니다.");
    }

    private static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public record WriteRequest(String title, String content, Long categoryId, List<String> tags, Boolean notice) {

        boolean wantsNotice() {
            return Boolean.TRUE.equals(notice);
        }
    }

    public record Counts(long views, long likes, long comments) {
    }

    public record PostView(long id, String blogSlug, String blogName, String title, String contentHtml,
            String contentMarkdown, String authorName, Long categoryId, String categoryName, List<String> tags,
            boolean notice, String createdAt, String updatedAt, Counts counts, boolean canEdit, boolean canDelete,
            boolean canManagePosts, boolean liked, boolean loggedIn, Long authorId) {

        PostView withLiked(boolean value) {
            return new PostView(id, blogSlug, blogName, title, contentHtml, contentMarkdown, authorName, categoryId,
                    categoryName, tags, notice, createdAt, updatedAt, counts, canEdit, canDelete, canManagePosts, value,
                    loggedIn, authorId);
        }
    }

    /** 읽을 수 있는 글과 그 블로그, 보는 사람의 멤버십(없으면 null). */
    public record Readable(Post post, Blog blog, BlogMembership membership) {

        public boolean canManagePosts() {
            return membership != null && membership.canManagePosts();
        }
    }

    public record OgInfo(String title, String blogName, String description, String imagePath) {
    }
}
