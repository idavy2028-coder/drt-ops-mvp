package com.idavy.drtops.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    private OffsetDateTime revokedAt;

    @Column(nullable = false, length = 120)
    private String createdFrom;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected RefreshToken() {
    }

    private RefreshToken(UserAccount user, String tokenHash, OffsetDateTime expiresAt, String createdFrom) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdFrom = createdFrom;
        this.createdAt = OffsetDateTime.now();
    }

    public static RefreshToken issue(UserAccount user, String tokenHash, OffsetDateTime expiresAt) {
        return new RefreshToken(user, tokenHash, expiresAt, "unknown");
    }

    public UUID getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }
}
