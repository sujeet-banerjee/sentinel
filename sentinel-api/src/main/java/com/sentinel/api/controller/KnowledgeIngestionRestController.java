package com.sentinel.api.controller;

import com.sentinel.api.service.KnowledgeIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeIngestionRestController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionRestController.class);
    private final KnowledgeIngestionService ingestionService;

    public KnowledgeIngestionRestController(KnowledgeIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Void>> ingestDocument(
            @RequestHeader("X-Sentinel-Tenant-ID") String tenantId,
            @RequestPart("file") FilePart filePart) {

        log.info("[TENANT: {}] Received ingestion request for file: {}", tenantId, filePart.filename());

        try {
            // 1. We must buffer the stream to a temporary file before returning the HTTP response.
            // If the HTTP connection closes before we read the stream, we lose the file data.
            Path tempFile = Files.createTempFile("sentinel-ingest-", "-" + filePart.filename());

            return filePart.transferTo(tempFile)
                    .doOnSuccess(v -> {
                        // 2. Fire and Forget: Offload the heavy embedding process to a background thread
                        Mono.fromRunnable(() -> processFileAsync(tempFile, filePart.filename(), tenantId))
                            .subscribeOn(Schedulers.boundedElastic()) // CRITICAL: Run on background thread
                            .subscribe(); // Detach from the main HTTP reactive chain
                    })
                    // 3. Return 202 Accepted immediately
                    .thenReturn(ResponseEntity.accepted().build());

        } catch (Exception e) {
            log.error("[TENANT: {}] Failed to process upload request", tenantId, e);
            return Mono.just(ResponseEntity.internalServerError().build());
        }
    }

    private void processFileAsync(Path tempFilePath, String originalFilename, String tenantId) {
        try {
            log.debug("[TENANT: {}] Starting background ETL job for: {}", tenantId, originalFilename);
            Resource resource = new FileSystemResource(tempFilePath.toFile());
            
            // Inject tenant metadata so the VectorStore partitions it correctly
            Map<String, Object> metadata = Map.of(
                    "tenantId", tenantId,
                    "filename", originalFilename,
                    "source", "api-upload"
            );

            ingestionService.ingestDocument(resource, metadata);
            log.info("[TENANT: {}] Background ETL job completed successfully for: {}", tenantId, originalFilename);

        } catch (Exception e) {
            log.error("[TENANT: {}] Error during background ingestion of file: {}", tenantId, originalFilename, e);
        } finally {
            // ALWAYS clean up the temporary file to prevent disk exhaustion
            try {
                Files.deleteIfExists(tempFilePath);
                log.debug("[TENANT: {}] Cleaned up temp file: {}", tenantId, tempFilePath);
            } catch (Exception e) {
                log.warn("Failed to delete temp file: {}", tempFilePath, e);
            }
        }
    }
}