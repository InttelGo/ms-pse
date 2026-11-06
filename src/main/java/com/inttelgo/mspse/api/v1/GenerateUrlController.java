package com.inttelgo.mspse.api.v1;

import com.inttelgo.mspse.api.ApiResponse;
import com.inttelgo.mspse.api.v1.dto.CatalogDtos.Page;
import com.inttelgo.mspse.api.v1.dto.GenerateUrlDtos;
import com.inttelgo.mspse.api.v1.dto.SearchRequest;
import com.inttelgo.mspse.common.Filter;
import com.inttelgo.mspse.service.GenerateUrlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/pse/urls")
public class GenerateUrlController {

    private final GenerateUrlService urlService;

    public GenerateUrlController(GenerateUrlService urlService) {
        this.urlService = urlService;
    }

    @GetMapping
    public ApiResponse<Page<GenerateUrlDtos.Response>> list(
            @RequestParam(defaultValue = "0") @Min(0) int skip,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", defaultValue = "desc") String sortOrder) {
        return ApiResponse.ok(urlService.list(skip, limit, sortBy, sortOrder, null));
    }

    @PostMapping("/search")
    public ApiResponse<Page<GenerateUrlDtos.Response>> search(
            @RequestParam(defaultValue = "0") @Min(0) int skip,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", defaultValue = "desc") String sortOrder,
            @RequestBody(required = false) SearchRequest body) {
        List<Filter> filters = body == null ? null : body.filters();
        return ApiResponse.ok(urlService.list(skip, limit, sortBy, sortOrder, filters));
    }

    @GetMapping("/{id}")
    public ApiResponse<GenerateUrlDtos.Response> get(@PathVariable Long id) {
        return ApiResponse.ok(urlService.get(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GenerateUrlDtos.Response>> create(@Valid @RequestBody GenerateUrlDtos.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(urlService.create(request)));
    }

    @PutMapping("/{id}")
    public ApiResponse<GenerateUrlDtos.Response> update(@PathVariable Long id, @Valid @RequestBody GenerateUrlDtos.Update request) {
        return ApiResponse.ok(urlService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<ApiResponse.Message> delete(@PathVariable Long id) {
        urlService.delete(id);
        return ApiResponse.ok(new ApiResponse.Message("URL eliminada exitosamente"));
    }
}
