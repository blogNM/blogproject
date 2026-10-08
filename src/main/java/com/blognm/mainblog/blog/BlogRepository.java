package com.blognm.mainblog.blog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BlogRepository extends JpaRepository<Blog, Long> {

    Optional<Blog> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** 운영 중(폐쇄 예정 포함)인 내 블로그. 개수 제한과 회원탈퇴 확인에 쓴다 (BLG-10, USR-05). */
    @Query("select b from Blog b where b.ownerId = :ownerId and b.status in :statuses order by b.id")
    List<Blog> findOwned(long ownerId, Collection<Blog.Status> statuses);
}
