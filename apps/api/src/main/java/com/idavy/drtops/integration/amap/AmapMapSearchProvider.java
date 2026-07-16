package com.idavy.drtops.integration.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.idavy.drtops.domain.map.AddressSuggestion;
import com.idavy.drtops.domain.map.Coordinate;
import com.idavy.drtops.domain.map.GeocodeResult;
import com.idavy.drtops.domain.map.MapSearchProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class AmapMapSearchProvider implements MapSearchProvider {

    private final AmapWebServiceClient client;

    public AmapMapSearchProvider(
            @Qualifier("amapWebClient") WebClient webClient,
            AmapProperties properties,
            AmapProviderMetrics metrics) {
        this.client = new AmapWebServiceClient(webClient, properties, metrics);
    }

    @Override
    public List<AddressSuggestion> suggest(String keyword, String city) {
        return client.get("suggest", "/v3/assistant/inputtips", query -> query
                        .queryParam("keywords", keyword)
                        .queryParam("city", city)
                        .queryParam("citylimit", true),
                this::suggestions);
    }

    @Override
    public GeocodeResult geocode(String address, String city) {
        return client.get("geocode", "/v3/geocode/geo", query -> query
                        .queryParam("address", address)
                        .queryParam("city", city),
                this::geocodeResult);
    }

    private List<AddressSuggestion> suggestions(JsonNode root) {
        List<AddressSuggestion> suggestions = new ArrayList<>();
        for (JsonNode tip : root.path("tips")) {
            parseCoordinate(tip.path("location").asText()).ifPresent(location -> suggestions.add(new AddressSuggestion(
                    tip.path("id").asText(),
                    tip.path("name").asText(),
                    tip.path("address").asText(),
                    tip.path("district").asText(),
                    location)));
        }
        return List.copyOf(suggestions);
    }

    private GeocodeResult geocodeResult(JsonNode root) {
        JsonNode geocode = root.path("geocodes").path(0);
        if (geocode.isMissingNode()) {
            throw invalidResponse();
        }
        Coordinate location = parseCoordinate(geocode.path("location").asText()).orElseThrow(this::invalidResponse);
        return new GeocodeResult(
                geocode.path("formatted_address").asText(),
                geocode.path("province").asText(),
                geocode.path("city").asText(),
                geocode.path("district").asText(),
                location);
    }

    private java.util.Optional<Coordinate> parseCoordinate(String value) {
        if (value == null || value.isBlank() || "[]".equals(value)) {
            return java.util.Optional.empty();
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 2) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new Coordinate(parts[0].trim(), parts[1].trim()));
        } catch (NumberFormatException exception) {
            return java.util.Optional.empty();
        }
    }

    private IllegalArgumentException invalidResponse() {
        return new IllegalArgumentException();
    }
}
