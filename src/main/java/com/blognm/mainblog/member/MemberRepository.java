package com.blognm.mainblog.member;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    /** 블로그 생성 개수를 셀 때 같은 회원의 동시 요청을 줄 세운다 (BLG-10, D-68). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findForUpdate(long id);

    /** 이메일 찾기 (USR-08). 탈퇴한 계정은 뺀다. */
    @Query("select m from Member m where m.name = :name and m.phone = :phone and m.status = com.blognm.mainblog.member.Member.Status.ACTIVE order by m.id")
    List<Member> findActiveByNameAndPhone(String name, String phone);

    /** 탈퇴 30일이 지난 회원의 개인정보를 지운다 (4.5). 닉네임은 글에 "탈퇴한 회원"으로 남기기 위해 둔다. */
    @Modifying
    @Query(value = """
            UPDATE members SET password_hash = NULL, name = NULL, phone = NULL, bio = NULL
            WHERE status = 'WITHDRAWN' AND withdrawn_at <= :before AND (name IS NOT NULL OR password_hash IS NOT NULL)
            """, nativeQuery = true)
    int erasePersonalData(LocalDateTime before);
}
