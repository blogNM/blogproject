package com.blognm.mainblog.image;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageRepository extends JpaRepository<Image, Long> {

    Optional<Image> findByStoredName(String storedName);

    List<Image> findByStoredNameIn(Collection<String> storedNames);

    List<Image> findByPostId(long postId);

    /** 글의 첫 사진 = 미리보기 사진 (D-59). */
    Optional<Image> findFirstByPostIdOrderBySortOrderAscIdAsc(long postId);
}
