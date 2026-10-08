package com.blognm.mainblog.post;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.blog.Blog;
import com.blognm.mainblog.blog.BlogRepository;
import com.blognm.mainblog.common.ApiException;

/** 카테고리 관리 (BRD-03). 권한(블로그장·글 관리 부블로그장)은 컨트롤러의 @PreAuthorize가 먼저 확인한다. */
@Service
public class CategoryService {

    private final CategoryRepository categories;
    private final BlogRepository blogs;

    public CategoryService(CategoryRepository categories, BlogRepository blogs) {
        this.categories = categories;
        this.blogs = blogs;
    }

    @Transactional
    public long add(String slug, String rawName) {
        Blog blog = blog(slug);
        String name = PostRules.normalizeCategoryName(rawName);
        if (categories.existsByBlogIdAndName(blog.getId(), name)) {
            throw duplicate();
        }
        return categories.save(Category.create(blog.getId(), name, categories.maxSortOrder(blog.getId()) + 1)).getId();
    }

    @Transactional
    public void rename(String slug, long categoryId, String rawName) {
        Blog blog = blog(slug);
        Category category = find(blog, categoryId);
        String name = PostRules.normalizeCategoryName(rawName);
        if (!name.equals(category.getName()) && categories.existsByBlogIdAndName(blog.getId(), name)) {
            throw duplicate();
        }
        category.rename(name);
    }

    /** 지우면 그 카테고리의 글은 "분류 없음"이 된다. 글은 지우지 않는다. */
    @Transactional
    public void delete(String slug, long categoryId) {
        Blog blog = blog(slug);
        Category category = find(blog, categoryId);
        categories.detachPosts(category.getId());
        categories.delete(category);
    }

    private Blog blog(String slug) {
        return blogs.findBySlug(slug).filter(b -> !b.isClosed())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BLOG_NOT_FOUND", "블로그를 찾을 수 없습니다."));
    }

    private Category find(Blog blog, long id) {
        return categories.findByIdAndBlogId(id, blog.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "카테고리를 찾을 수 없습니다."));
    }

    private static ApiException duplicate() {
        return new ApiException(HttpStatus.CONFLICT, "CATEGORY_EXISTS", "같은 이름의 카테고리가 있습니다.");
    }
}
