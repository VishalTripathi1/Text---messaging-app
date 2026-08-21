package com.messaging.presentation.controller;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AttachmentController - Uploads and serves media attachments (images, video, voice notes, documents)
 */
@RestController
@RequestMapping("/v1/attachments")
public class AttachmentController {

    private final Path fileStorageLocation;

    public AttachmentController() {
        this.fileStorageLocation = Paths.get("uploads").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.fileStorageLocation);
        } catch (IOException e) {
            throw new RuntimeException("Could not create uploads directory", e);
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        String rawFileName = StringUtils.cleanPath(file.getOriginalFilename());
        String safeFileName = rawFileName.replaceAll("[^a-zA-Z0-9\\.\\-_]", "_");
        String uniqueFileName = UUID.randomUUID().toString() + "_" + safeFileName;
        
        try {
            Path targetLocation = this.fileStorageLocation.resolve(uniqueFileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            String downloadUri = "/v1/attachments/download/" + uniqueFileName;

            Map<String, Object> response = new HashMap<>();
            response.put("filePath", downloadUri);
            response.put("fileName", rawFileName);
            response.put("fileType", file.getContentType());
            response.put("fileSize", file.getSize());

            return ResponseEntity.ok(response);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/download/{fileName:.+}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileName) {
        try {
            String decodedName = java.net.URLDecoder.decode(fileName, java.nio.charset.StandardCharsets.UTF_8);
            Path filePath = this.fileStorageLocation.resolve(fileName).normalize();
            if (!Files.exists(filePath)) {
                filePath = this.fileStorageLocation.resolve(decodedName).normalize();
            }

            // Path-traversal guard: resolve()+normalize() alone does NOT stop a crafted name
            // like "../../../../etc/passwd" (or its URL-encoded form) from escaping the uploads
            // directory - normalize() only collapses the "../" segments, it doesn't forbid the
            // result from landing outside fileStorageLocation. Without this check, this endpoint
            // could be used to read arbitrary files readable by the server process.
            if (!filePath.startsWith(this.fileStorageLocation)) {
                return ResponseEntity.badRequest().build();
            }

            Resource resource = new UrlResource(filePath.toUri());
            
            if (resource.exists() && resource.isReadable()) {
                String contentType = Files.probeContentType(filePath);
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }

                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                        .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (MalformedURLException e) {
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }
}
