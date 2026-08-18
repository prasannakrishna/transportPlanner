package com.bhagwat.scm.transportPlanner.service;

import com.bhagwat.scm.transportPlanner.dto.*;
import com.bhagwat.scm.transportPlanner.entity.RouteTemplate;
import com.bhagwat.scm.transportPlanner.enums.ShipmentType;
import com.bhagwat.scm.transportPlanner.repository.RouteTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RouteTemplateService {

    private final RouteTemplateRepository routeTemplateRepository;

    @Transactional
    public RouteTemplateResponse createTemplate(RouteTemplateRequest request) {
        RouteTemplate template = RouteTemplate.builder()
                .templateName(request.getTemplateName())
                .shipmentType(request.getShipmentType())
                .transportMode(request.getTransportMode())
                .originPincode(request.getOriginPincode())
                .destinationPincode(request.getDestinationPincode())
                .originCity(request.getOriginCity())
                .destinationCity(request.getDestinationCity())
                .estimatedTransitHours(request.getEstimatedTransitHours())
                .distanceKm(request.getDistanceKm())
                .viaHubs(request.getViaHubs())
                .legCount(request.getLegCount())
                .build();
        return toResponse(routeTemplateRepository.save(template));
    }

    @Transactional(readOnly = true)
    public RouteTemplateResponse getTemplate(String templateId) {
        return toResponse(routeTemplateRepository.findById(templateId)
                .orElseThrow(() -> new RuntimeException("Route template not found: " + templateId)));
    }

    @Transactional(readOnly = true)
    public List<RouteTemplateResponse> listTemplates(ShipmentType shipmentType) {
        List<RouteTemplate> templates = shipmentType != null
                ? routeTemplateRepository.findByShipmentType(shipmentType)
                : routeTemplateRepository.findByIsActiveTrue();
        return templates.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public RouteTemplateResponse updateTemplate(String templateId, RouteTemplateRequest request) {
        RouteTemplate template = routeTemplateRepository.findById(templateId)
                .orElseThrow(() -> new RuntimeException("Route template not found: " + templateId));
        template.setTemplateName(request.getTemplateName());
        template.setShipmentType(request.getShipmentType());
        template.setTransportMode(request.getTransportMode());
        template.setOriginPincode(request.getOriginPincode());
        template.setDestinationPincode(request.getDestinationPincode());
        template.setOriginCity(request.getOriginCity());
        template.setDestinationCity(request.getDestinationCity());
        template.setEstimatedTransitHours(request.getEstimatedTransitHours());
        template.setDistanceKm(request.getDistanceKm());
        template.setViaHubs(request.getViaHubs());
        template.setLegCount(request.getLegCount());
        return toResponse(routeTemplateRepository.save(template));
    }

    @Transactional
    public void deactivateTemplate(String templateId) {
        RouteTemplate template = routeTemplateRepository.findById(templateId)
                .orElseThrow(() -> new RuntimeException("Route template not found: " + templateId));
        template.setIsActive(false);
        routeTemplateRepository.save(template);
    }

    private RouteTemplateResponse toResponse(RouteTemplate t) {
        return RouteTemplateResponse.builder()
                .templateId(t.getTemplateId())
                .templateName(t.getTemplateName())
                .shipmentType(t.getShipmentType())
                .transportMode(t.getTransportMode())
                .originPincode(t.getOriginPincode())
                .destinationPincode(t.getDestinationPincode())
                .originCity(t.getOriginCity())
                .destinationCity(t.getDestinationCity())
                .estimatedTransitHours(t.getEstimatedTransitHours())
                .distanceKm(t.getDistanceKm())
                .viaHubs(t.getViaHubs())
                .legCount(t.getLegCount())
                .isActive(t.getIsActive())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
