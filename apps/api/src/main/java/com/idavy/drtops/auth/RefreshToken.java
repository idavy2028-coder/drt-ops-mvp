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

    @Column(nullable = false)
    private long tokenVersion;

    private OffsetDateTime revokedAt;

    @Column(nullable = false, length = 120)
    private String createdFrom;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected RefreshToken() {
    }

    private RefreshToken(
            UserAccount user, String tokenHash, OffsetDateTime expiresAt, long tokenVersion, String createdFrom) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.tokenVersion = tokenVersion;
        this.createdFrom = createdFrom;
        this.createdAt = OffsetDateTime.now();
    }

    public static RefreshToken issue(UserAccount user, String tokenHash, OffsetDateTime expiresAt) {
        return issue(user, tokenHash, expiresAt, user.getTokenVersion());
    }

    public static RefreshToken issue(UserAccount user, String tokenHash, OffsetDateTime expiresAt, long tokenVersion) {
        return new RefreshToken(user, tokenHash, expiresAt, tokenVersion, "unknown");
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

    public UserAccount getUser() { return user; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }

    public long getTokenVersion() { return tokenVersion; }

    public void revoke() { revokedAt = OffsetDateTime.now(); }
}
