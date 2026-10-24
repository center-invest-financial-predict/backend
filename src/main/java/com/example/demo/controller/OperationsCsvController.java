package com.example.demo.controller;

import com.example.demo.dto.operation.ImportResult;
import com.example.demo.service.CsvImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/operations")
@RequiredArgsConstructor
public class OperationsCsvController {

    private final CsvImportService csvImportService;

    /**
     * Upload a CSV that may contain many companies.
     * Only rows for INNs linked to the current user are imported.
     */
    @PostMapping(value = "/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResult> uploadCsv(
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) throws IOException {
        return ResponseEntity.ok(csvImportService.importForUser(authentication.getName(), file));
    }
}
