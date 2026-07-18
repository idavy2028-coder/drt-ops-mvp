package com.idavy.drtops.domain.area;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.integration.amap.AmapProperties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ServiceAreaBoundaryImportService {

    private final WebClient webClient;
    private final AmapProperties amapProperties;
    private final ServiceAreaCommandService commandService;
    private final ObjectMapper objectMapper;

    public ServiceAreaBoundaryImportService(
            @Qualifier("amapWebClient") WebClient webClient,
            AmapProperties amapProperties,
            ServiceAreaCommandService commandService,
            ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.amapProperties = amapProperties;
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    public ServiceAreaView importDistrictBoundary(String keyword, UUID actorId) {
        if (!amapProperties.isAvailable()) {
            throw unavailable();
        }
        try {
            String response = webClient.get()
                    .uri(builder -> builder.path("/v3/config/district")
                            .queryParam("key", amapProperties.getWebServiceKey())
                            .queryParam("keywords", keyword)
                            .queryParam("extensions", "all")
                            .queryParam("subdistrict", 0)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            JsonNode root = objectMapper.readTree(response == null ? "" : response);
            String polyline = root.path("districts").path(0).path("polyline").asText();
            if (!"1".equals(root.path("status").asText()) || polyline.isBlank() || polyline.contains("|")) {
                throw unavailable();
            }
            return commandService.importDistrictDraft(keyword, toPolygonWkt(polyline), actorId);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private String toPolygonWkt(String polyline) {
        String[] points = polyline.split(";");
        StringBuilder wkt = new StringBuilder("POLYGON((");
        for (int index = 0; index < points.length; index++) {
            String[] coordinate = points[index].split(",", -1);
            if (coordinate.length != 2) {
                throw unavailable();
            }
            if (index > 0) {
                wkt.append(',');
            }
            wkt.append(coordinate[0].trim()).append(' ').append(coordinate[1].trim());
        }
        if (points.length > 0 && !points[0].trim().equals(points[points.length - 1].trim())) {
            wkt.append(',').append(points[0].replace(',', ' ').trim());
        }
        return wkt.append("))").toString();
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "行政区边界导入暂不可用，请稍后重试");
    }
}
