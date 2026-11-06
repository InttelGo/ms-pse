package com.inttelgo.mspse.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.inttelgo.mspse.api.ApiResponse;
import com.inttelgo.mspse.api.v1.dto.GenerateUrlDtos;
import com.inttelgo.mspse.api.v1.dto.TransactionDtos;
import com.inttelgo.mspse.service.GenerateUrlService;
import com.inttelgo.mspse.service.PseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints públicos que usa el landing para pagar. No piden X-User-Id. */
@Validated
@RestController
@RequestMapping("/api/v1/pse")
public class PseController {

    private final PseService pseService;
    private final GenerateUrlService urlService;

    public PseController(PseService pseService, GenerateUrlService urlService) {
        this.pseService = pseService;
        this.urlService = urlService;
    }

    public record Banks(JsonNode banks) {
    }

    @GetMapping("/banks")
    public ApiResponse<Banks> banks() {
        return ApiResponse.ok(new Banks(pseService.banks()));
    }

    @PostMapping("/transactions")
    public ResponseEntity<ApiResponse<TransactionDtos.Started>> start(@Valid @RequestBody TransactionDtos.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(pseService.start(request)));
    }

    /** {@code hash} es el ticket cifrado que PSE devuelve en la URL de retorno. */
    @GetMapping("/transactions/verify/{hash}")
    public ApiResponse<TransactionDtos.Response> verify(@PathVariable String hash) {
        return ApiResponse.ok(pseService.verify(hash));
    }

    @GetMapping("/urls/public/{hash}")
    public ApiResponse<GenerateUrlDtos.Public> publicUrl(@PathVariable String hash) {
        return ApiResponse.ok(urlService.publicView(hash));
    }
}
