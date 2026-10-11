package com.blognm.mainblog.blog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BlogRepository extends JpaRepository<Blog, Long> {

    Optional<Blog> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** 운영 중(폐쇄 예정 포함)인 내 블로그. 개수 제한과 회원탈퇴 확인에 쓴다 (BLG-10, USR-05). */
    @Query("select b from Blog b where b.ownerId = :ownerId and b.status in :statuses order by b.id")
    List<Blog> findOwned(long ownerId, Collection<Blog.Status> statuses);

    /** 블로그를 나간 멤버의 글은 작성자를 "탈퇴한 계정"으로 보인다 (D-33). */
    @Modifying
    @Query(value = "UPDATE posts SET author_left = TRUE WHERE blog_id = :blogId AND author_id = :memberId",
            nativeQuery = true)
    int markPostsAuthorLeft(long blogId, long memberId);

    /** 블로그 대표 이미지 파일 이름. 글에 사진이 없을 때 미리보기로 쓴다 (D-59). */
    @Query(value = "SELECT i.stored_name FROM blogs b JOIN images i ON i.id = b.cover_image_id WHERE b.id = :blogId",
            nativeQuery = true)
    Optional<String> coverImageName(long blogId);
}
