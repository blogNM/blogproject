package com.blognm.mainblog.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    Optional<SocialAccount> findByProviderAndProviderUserId(String provider, String providerUserId);

    boolean existsByMemberIdAndProvider(long memberId, String provider);

    /** 탈퇴하면 연결을 끊어 같은 구글 계정으로 새로 가입할 수 있게 한다 (USR-05). */
    @Modifying
    @Query("delete from SocialAccount a where a.memberId = :memberId")
    int deleteAllByMemberId(long memberId);
}
