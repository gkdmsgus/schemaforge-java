package com.schemaforge.plan;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @PostMapping("/plan")
    public ResponseEntity<?> plan(@RequestBody Map<String, String> body) {
        String description = body.getOrDefault("description", "").trim();
        if (description.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "description required"));
        try {
            return ResponseEntity.ok(planService.plan(description));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
