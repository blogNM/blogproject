package com.blognm.mainblog.post;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByBlogIdOrderBySortOrderAscIdAsc(long blogId);

    Optional<Category> findByIdAndBlogId(long id, long blogId);

    boolean existsByBlogIdAndName(long blogId, String name);

    @Query("select coalesce(max(c.sortOrder), 0) from Category c where c.blogId = :blogId")
    int maxSortOrder(long blogId);

    /** 카테고리를 지우면 그 글들은 "분류 없음"이 된다. */
    @Modifying
    @Query(value = "UPDATE posts SET category_id = NULL WHERE category_id = :categoryId", nativeQuery = true)
    int detachPosts(long categoryId);
}
