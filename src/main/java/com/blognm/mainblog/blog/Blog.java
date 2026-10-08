package com.blognm.mainblog.blog;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/** 블로그 (BLG-01). 주소(slug)로 구분하고 이름은 중복을 허용한다. */
@Entity
@Table(name = "blogs")
public class Blog {

    /** 공개: 목록·검색에 보임. 일부 공개: 공유 링크로만. 비공개: 멤버만 (D-49). */
    public enum Visibility { PUBLIC, LINK_ONLY, PRIVATE }

    public enum JoinPolicy { FREE, APPROVAL }

    public enum Status { ACTIVE, CLOSING, CLOSED }

    /** 테마 틀 7가지 (D-89). 화면 배치만 다르고 기능은 같다. */
    public enum Theme { ODYSSEY, POSTER, WHATEVER, LETTER, BOOK_CLUB, MAGAZINE, PORTFOLIO }

    public enum Font { SANS, SERIF, ROUND, MONO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String slug;

    private String name;

    private String description;

    @Column(name = "owner_id")
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    private Visibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "join_policy")
    private JoinPolicy joinPolicy;

    @Column(name = "share_token")
    private String shareToken;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "is_hidden")
    private boolean hidden;

    @Column(name = "close_scheduled_at")
    private LocalDateTime closeScheduledAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "cover_image_id")
    private Long coverImageId;

    @Enumerated(EnumType.STRING)
    private Theme theme = Theme.ODYSSEY;

    @Column(name = "theme_accent")
    private String themeAccent = BlogRules.DEFAULT_ACCENT;

    @Enumerated(EnumType.STRING)
    @Column(name = "theme_font")
    private Font themeFont = Font.SANS;

    @Column(name = "hero_title")
    private String heroTitle;

    @Column(name = "hero_subtitle")
    private String heroSubtitle;

    @ManyToMany
    @OrderBy("name")
    @JoinTable(name = "blog_tags", joinColumns = @JoinColumn(name = "blog_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new LinkedHashSet<>();

    protected Blog() {
    }

    public static Blog create(String slug, String name, String description, long ownerId, Visibility visibility,
            JoinPolicy joinPolicy, LocalDateTime now) {
        Blog b = new Blog();
        b.slug = slug;
        b.name = name;
        b.description = description;
        b.ownerId = ownerId;
        b.joinPolicy = joinPolicy;
        b.status = Status.ACTIVE;
        b.createdAt = now;
        b.changeVisibility(visibility);
        return b;
    }

    /** 일부 공개로 바꾸면 공유 링크 값을 만들고, 다른 범위로 바꾸면 링크를 없앤다. */
    public void changeVisibility(Visibility visibility) {
        this.visibility = visibility;
        if (visibility == Visibility.LINK_ONLY) {
            if (shareToken == null) {
                regenerateShareToken();
            }
        } else {
            shareToken = null;
        }
    }

    /** 새로 만들면 이전 링크로는 들어올 수 없다 (US1-4). */
    public void regenerateShareToken() {
        this.shareToken = BlogRules.newShareToken();
    }

    public void changeInfo(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public void changeJoinPolicy(JoinPolicy joinPolicy) {
        this.joinPolicy = joinPolicy;
    }

    public void replaceTags(Set<Tag> newTags) {
        tags.clear();
        tags.addAll(newTags);
    }

    public void changeTheme(Theme theme, String accent, Font font, String heroTitle, String heroSubtitle) {
        this.theme = theme;
        this.themeAccent = accent;
        this.themeFont = font;
        this.heroTitle = heroTitle;
        this.heroSubtitle = heroSubtitle;
    }

    public void changeCover(Long imageId) {
        this.coverImageId = imageId;
    }

    public boolean isClosed() {
        return status == Status.CLOSED;
    }

    /** 공개·일부 공개는 같은 개수 제한을 쓴다 (BLG-10). */
    public boolean countsAsPublic() {
        return visibility != Visibility.PRIVATE;
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public JoinPolicy getJoinPolicy() {
        return joinPolicy;
    }

    public String getShareToken() {
        return shareToken;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isHidden() {
        return hidden;
    }

    public LocalDateTime getCloseScheduledAt() {
        return closeScheduledAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Set<Tag> getTags() {
        return tags;
    }

    public Long getCoverImageId() {
        return coverImageId;
    }

    public Theme getTheme() {
        return theme;
    }

    public String getThemeAccent() {
        return themeAccent;
    }

    public Font getThemeFont() {
        return themeFont;
    }

    public String getHeroTitle() {
        return heroTitle;
    }

    public String getHeroSubtitle() {
        return heroSubtitle;
    }
}
