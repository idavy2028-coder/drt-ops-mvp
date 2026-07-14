package com.idavy.drtops.config;

import com.idavy.drtops.auth.AuthAuditService;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.context.SecurityContextHolder;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AuthenticationFailureHandler authenticationFailureHandler,
            AuthAuditService authAuditService) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationFailureHandler)
                        .accessDeniedHandler((request, response, exception) -> {
                            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
                            if (principal instanceof UUID actorId) {
                                authAuditService.recordAuthorizationDenied(
                                        actorId, authorizationEntityId(request.getRequestURI(), actorId));
                            }
                            response.sendError(403);
                        }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/orders").hasAuthority("ORDER_CREATE")
                        .requestMatchers(HttpMethod.GET, "/api/orders/**").hasAuthority("ORDER_READ")
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/dispatch").hasAuthority("DISPATCH_EXECUTE")
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/cancel", "/api/orders/*/no-show")
                        .hasAuthority("DISPATCH_EXECUTE")
                        .requestMatchers(HttpMethod.GET, "/api/dispatch-decisions/manual-review").hasAuthority("MANUAL_REVIEW")
                        .requestMatchers(HttpMethod.GET, "/api/dispatch-decisions").hasAuthority("DECISION_READ")
                        .requestMatchers(HttpMethod.POST, "/api/dispatch-decisions/*/approve", "/api/dispatch-decisions/*/reject")
                        .hasAuthority("MANUAL_REVIEW")
                        .requestMatchers("/api/audit-logs/**").hasAuthority("AUDIT_READ")
                        .requestMatchers("/api/metrics/**").hasAuthority("METRICS_READ")
                        .requestMatchers("/api/users/**").hasAuthority("USER_MANAGE")
                        .requestMatchers(HttpMethod.POST, "/api/vehicles/*/location-reports").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/vehicles/locations/latest").hasAuthority("LOCATION_READ")
                        .requestMatchers(HttpMethod.GET, "/api/vehicles/*/location-events").hasAuthority("LOCATION_READ")
                        .requestMatchers(HttpMethod.GET, "/api/vehicle-tasks/*/location-events").hasAuthority("LOCATION_READ")
                        .requestMatchers(HttpMethod.GET, "/api/vehicle-locations/export.csv").hasAuthority("LOCATION_EXPORT")
                        .requestMatchers(HttpMethod.GET, "/api/vehicle-tasks/**").hasAuthority("TASK_READ")
                        .requestMatchers(HttpMethod.POST, "/api/vehicle-tasks/**").hasAuthority("TASK_EXECUTE")
                        .requestMatchers("/api/dispatch-rule-sets/**").hasAuthority("RULE_MANAGE")
                        .requestMatchers(
                                "/api/vehicles/**",
                                "/api/drivers/**",
                                "/api/service-areas/**",
                                "/api/virtual-stops/**")
                        .hasAuthority("RESOURCE_MANAGE")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private UUID authorizationEntityId(String requestUri, UUID actorId) {
        String[] path = requestUri.split("/");
        if (path.length > 3 && "api".equals(path[1]) && "users".equals(path[2])) {
            try {
                return UUID.fromString(path[3]);
            } catch (IllegalArgumentException ignored) {
                // Fall through to preserve the actor as the entity for non-user resource denials.
            }
        }
        return actorId;
    }
}
