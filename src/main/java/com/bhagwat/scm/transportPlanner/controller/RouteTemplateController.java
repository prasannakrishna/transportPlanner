package com.bhagwat.scm.transportPlanner.controller;

import com.bhagwat.scm.transportPlanner.dto.*;
import com.bhagwat.scm.transportPlanner.enums.ShipmentType;
import com.bhagwat.scm.transportPlanner.service.RouteTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transport/route-templates")
@RequiredArgsConstructor
@Tag(name = "Route Template", description = "Manage standard route templates for logistics")
public class RouteTemplateController {

    private final RouteTemplateService routeTemplateService;

    @PostMapping
    @Operation(summary = "Create a route template")
    public ResponseEntity<RouteTemplateResponse> createTemplate(@Valid @RequestBody RouteTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(routeTemplateService.createTemplate(request));
    }

    @GetMapping("/{templateId}")
    @Operation(summary = "Get route template by ID")
    public ResponseEntity<RouteTemplateResponse> getTemplate(@PathVariable String templateId) {
        return ResponseEntity.ok(routeTemplateService.getTemplate(templateId));
    }

    @GetMapping
    @Operation(summary = "List active route templates (optionally filter by shipment type)")
    public ResponseEntity<List<RouteTemplateResponse>> listTemplates(
            @RequestParam(required = false) ShipmentType shipmentType) {
        return ResponseEntity.ok(routeTemplateService.listTemplates(shipmentType));
    }

    @PutMapping("/{templateId}")
    @Operation(summary = "Update a route template")
    public ResponseEntity<RouteTemplateResponse> updateTemplate(@PathVariable String templateId,
                                                                 @Valid @RequestBody RouteTemplateRequest request) {
        return ResponseEntity.ok(routeTemplateService.updateTemplate(templateId, request));
    }

    @PatchMapping("/{templateId}/deactivate")
    @Operation(summary = "Deactivate a route template")
    public ResponseEntity<Void> deactivateTemplate(@PathVariable String templateId) {
        routeTemplateService.deactivateTemplate(templateId);
        return ResponseEntity.noContent().build();
    }
}
