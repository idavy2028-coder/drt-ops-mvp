package com.idavy.drtops.auth;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "user_accounts")
public class UserAccount {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 80)
    private String username;

    @Column(nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled;

    @Column(length = 80)
    private String organizationCode;

    @Column(nullable = false)
    private long tokenVersion;

    @Column(nullable = false)
    private boolean mustChangePassword;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @ElementCollection
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role_code", nullable = false, length = 40)
    private Set<RoleCode> roles = EnumSet.noneOf(RoleCode.class);

    protected UserAccount() {
    }

    private UserAccount(String username, String displayName, String passwordHash) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.enabled = true;
        this.tokenVersion = 0;
        this.mustChangePassword = true;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public static UserAccount create(String username, String displayName, String passwordHash) {
        return new UserAccount(username, displayName, passwordHash);
    }

    public void assignRoles(Set<RoleCode> roles) {
        this.roles = roles.isEmpty() ? EnumSet.noneOf(RoleCode.class) : EnumSet.copyOf(roles);
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public Set<RoleCode> getRoles() {
        return Set.copyOf(roles);
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void disable() {
        enabled = false;
        tokenVersion++;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        mustChangePassword = false;
        tokenVersion++;
    }
}
