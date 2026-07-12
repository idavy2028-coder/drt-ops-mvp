package com.idavy.drtops.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class JwtTokenService {

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final AuthConfiguration configuration;

    public JwtTokenService(AuthConfiguration configuration) {
        this.configuration = configuration;
        if (!StringUtils.hasText(configuration.getJwtSecret())
                || configuration.getJwtSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("drt.auth.jwt-secret must contain at least 32 bytes");
        }
        SecretKey key = new SecretKeySpec(
                configuration.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    public IssuedToken issue(UserAccount user) {
        OffsetDateTime issuedAt = OffsetDateTime.now();
        OffsetDateTime expiresAt = issuedAt.plusMinutes(configuration.getAccessTokenMinutes());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuedAt(issuedAt.toInstant())
                .expiresAt(expiresAt.toInstant())
                .claims(values -> values.putAll(Map.of(
                        "username", user.getUsername(),
                        "roles", user.getRoles().stream().map(Enum::name).toList(),
                        "tokenVersion", user.getTokenVersion())))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public Jwt decode(String token) {
        return decoder.decode(token);
    }

    public record IssuedToken(String value, OffsetDateTime expiresAt) {
    }
}
