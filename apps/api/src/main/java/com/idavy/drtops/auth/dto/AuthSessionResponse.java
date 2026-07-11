package com.idavy.drtops.auth.dto;

import java.time.OffsetDateTime;

public record AuthSessionResponse(String accessToken, OffsetDateTime expiresAt, CurrentUserResponse user) { }
