package com.blognm.mainblog.blog;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByName(String name);

    /** 같은 태그를 동시에 만들어도 한 행만 생긴다. */
    @Modifying
    @Query(value = "INSERT IGNORE INTO tags (name) VALUES (:name)", nativeQuery = true)
    void insertIfAbsent(String name);
}
