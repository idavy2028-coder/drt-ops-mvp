package com.idavy.drtops.auth;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    @Modifying
    @Transactional
    @Query("update RefreshToken token set token.revokedAt = :revokedAt "
            + "where token.user.id = :userId and token.revokedAt is null")
    int revokeAllActiveByUserId(@Param("userId") UUID userId, @Param("revokedAt") OffsetDateTime revokedAt);
}
