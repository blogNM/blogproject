package com.blognm.mainblog.blog;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 머리 사진이 없는 블로그의 공유 미리보기 그림. 블로그 홈 머리 영역의 배경(테마 틀·강조 색 그라데이션)을
 * 1200×630 PNG로 그린다. 색은 themes.css의 .blog-hero 배경과 같게 맞춘다 (D-100).
 * 글자는 넣지 않는다. 블로그 이름은 og:title로 미리보기에 함께 보인다.
 */
@Component
public class BlogOgImage {

    static final int WIDTH = 1200;
    static final int HEIGHT = 630;
    private static final Color SAND = new Color(0xDFB79F);

    private final BlogRepository blogs;

    public BlogOgImage(BlogRepository blogs) {
        this.blogs = blogs;
    }

    /**
     * 블로그 공유 미리보기 그림의 주소(사이트 주소 빼고). 머리 사진이 있으면 그 사진, 없으면 머리 배경 그림.
     * 글 상세 미리보기도 글에 그림이 없으면 이것을 쓴다.
     */
    public String path(Blog blog, String key) {
        return blogs.coverImageName(blog.getId()).map(name -> "/uploads/" + name).orElseGet(() -> {
            UriComponentsBuilder url = UriComponentsBuilder.fromPath("/blog/{slug}/og-image.png");
            if (key != null) {
                url.queryParam("key", "{key}");
            }
            return (key == null ? url.buildAndExpand(blog.getSlug()) : url.buildAndExpand(blog.getSlug(), key))
                    .encode().toUriString();
        });
    }

    public byte[] render(Blog blog) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setPaint(paint(blog.getTheme(), accent(blog.getThemeAccent())));
            g.fillRect(0, 0, WIDTH, HEIGHT);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static LinearGradientPaint paint(Blog.Theme theme, Color accent) {
        return switch (theme) {
            case POSTER -> gradient(160, new float[] {0f, 0.55f, 0.552f, 1f},
                    new Color(0x111111), new Color(0x2A2A2A), new Color(0xD9D9D9), new Color(0xF2F2F2));
            case WHATEVER -> gradient(135, new float[] {0f, 0.6f, 1f},
                    new Color(0x9FB0A0), new Color(0xB9C6B6), new Color(0xCFD7C9));
            case BOOK_CLUB -> gradient(120, new float[] {0f, 0.5f, 1f},
                    new Color(0x6B7A8F), new Color(0x8D9DB3), new Color(0xC9B8A8));
            case PORTFOLIO -> gradient(160, new float[] {0f, 0.45f, 1f},
                    new Color(0x1D3B4F), new Color(0x2D6A7F), new Color(0xE9B99A));
            // 기본 머리 배경: 어두운 강조 색 → 강조 색(45%) → 모래색(120%, 끝에서는 그 사이 73% 지점)
            default -> gradient(135, new float[] {0f, 0.45f, 1f},
                    mix(accent, Color.BLACK, 0.88), accent, mix(accent, SAND, 1 - 0.55 / 0.75));
        };
    }

    /** CSS linear-gradient(각도)와 같은 방향·길이로 그라데이션 선을 잡는다. */
    private static LinearGradientPaint gradient(double cssDegrees, float[] fractions, Color... colors) {
        double rad = Math.toRadians(cssDegrees);
        double dx = Math.sin(rad);
        double dy = -Math.cos(rad);
        double half = (Math.abs(WIDTH * dx) + Math.abs(HEIGHT * dy)) / 2;
        double cx = WIDTH / 2.0;
        double cy = HEIGHT / 2.0;
        return new LinearGradientPaint(new Point2D.Double(cx - dx * half, cy - dy * half),
                new Point2D.Double(cx + dx * half, cy + dy * half), fractions, colors);
    }

    /** a를 weight만큼, b를 나머지만큼 섞는다 (CSS color-mix와 같은 뜻). */
    private static Color mix(Color a, Color b, double weight) {
        return new Color(channel(a.getRed(), b.getRed(), weight), channel(a.getGreen(), b.getGreen(), weight),
                channel(a.getBlue(), b.getBlue(), weight));
    }

    private static int channel(int a, int b, double weight) {
        return (int) Math.round(a * weight + b * (1 - weight));
    }

    private static Color accent(String hex) {
        try {
            return Color.decode(hex);
        } catch (RuntimeException e) {
            return new Color(0x7C0CB4);
        }
    }
}
