package com.idavy.drtops.auth.dto;

import java.util.Set;

public record CurrentUserResponse(String id, String username, Set<String> roles, boolean mustChangePassword) { }
