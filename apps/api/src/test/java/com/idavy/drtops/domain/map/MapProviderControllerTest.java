package com.idavy.drtops.domain.map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import com.idavy.drtops.config.JwtAuthenticationFilter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

@WebMvcTest(value = MapProviderController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@Import(MapProviderControllerTest.SecurityTestConfiguration.class)
class MapProviderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(authorities = "ORDER_READ")
    void deniesAuthenticatedUsersWithoutMapResourcePermission() throws Exception {
        mockMvc.perform(get("/api/map/address-suggestions").param("keyword", "人民政府").param("city", "通渭县"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deniesAnonymousUsers() throws Exception {
        mockMvc.perform(get("/api/map/address-suggestions").param("keyword", "人民政府").param("city", "通渭县"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "RESOURCE_MANAGE")
    void allowsAuthorizedOperationsUsersAndReturnsInternalDto() throws Exception {
        mockMvc.perform(get("/api/map/address-suggestions").param("keyword", "人民政府").param("city", "通渭县"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("通渭县人民政府"))
                .andExpect(jsonPath("$.data[0].location.coordinateSystem").value("GCJ-02"));
    }

    @Test
    @WithMockUser(authorities = "RESOURCE_MANAGE")
    void returnsChineseValidationMessageForBlankRequestParameter() throws Exception {
        mockMvc.perform(get("/api/map/address-suggestions").param("keyword", "").param("city", "通渭县"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("keyword不能为空"));
    }

    @Test
    @WithMockUser(authorities = "RESOURCE_MANAGE")
    void returnsChineseValidationMessageForBlankCityParameter() throws Exception {
        mockMvc.perform(get("/api/map/address-suggestions").param("keyword", "人民政府").param("city", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("city不能为空"));
    }

    @Test
    @WithMockUser(authorities = "RESOURCE_MANAGE")
    void returnsChineseValidationMessageWhenDistanceDestinationIsMissing() throws Exception {
        mockMvc.perform(post("/api/map/distance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origin\":{\"longitude\":105.24,\"latitude\":35.21}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("destination不能为空"));
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityTestConfiguration {

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                    .build();
        }

        @Bean
        MapSearchProvider mapSearchProvider() {
            return new MapSearchProvider() {
                @Override
                public List<AddressSuggestion> suggest(String keyword, String city) {
                    return List.of(new AddressSuggestion("id", "通渭县人民政府", "文化街", "通渭县",
                            new Coordinate("105.242100", "35.210300")));
                }

                @Override
                public GeocodeResult geocode(String address, String city) {
                    return new GeocodeResult(address, "甘肃省", "定西市", city,
                            new Coordinate("105.242100", "35.210300"));
                }
            };
        }

        @Bean
        RoutePlanningProvider routePlanningProvider() {
            return new RoutePlanningProvider() {
                @Override
                public RoutePlanResult drivingRoute(Coordinate origin, Coordinate destination, List<Coordinate> waypoints) {
                    return new RoutePlanResult(100, 60, List.of(origin, destination));
                }

                @Override
                public DistanceResult distance(Coordinate origin, Coordinate destination) {
                    return new DistanceResult(100, 60);
                }
            };
        }
    }
}
