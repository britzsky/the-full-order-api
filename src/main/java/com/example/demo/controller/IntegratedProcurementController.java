package com.example.demo.controller;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.ProcurementBatchService;
import com.example.demo.service.OurhomeCatalogSyncService;
import com.fasterxml.jackson.databind.JsonNode;
@RestController
public class IntegratedProcurementController {
    private final ProcurementBatchService batches;
    private final OurhomeCatalogSyncService catalog;
    public IntegratedProcurementController(ProcurementBatchService batches,OurhomeCatalogSyncService catalog){this.batches=batches;this.catalog=catalog;}
    @PostMapping("/Procurement/OrdersBatch")
    public ResponseEntity<?> order(@RequestBody JsonNode input){return execute(()->batches.submit(input));}
    @PostMapping("/v2/supplier-integration/ourhome/sync-catalog")
    public ResponseEntity<?> sync(@RequestBody Map<String,Object> input){return execute(()->catalog.sync(input));}
    private ResponseEntity<?> execute(Supplier<Map<String,Object>> action){
        try{return ResponseEntity.ok(action.get());}
        catch(ProcurementBatchService.NotSubmittedException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage(),"not_submitted",true));}
        catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
        catch(IllegalStateException e){return ResponseEntity.status(502).body(Map.of("message",e.getMessage()));}
    }
}
