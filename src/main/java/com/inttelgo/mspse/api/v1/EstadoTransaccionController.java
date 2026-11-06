package com.inttelgo.mspse.api.v1;

import com.inttelgo.mspse.api.ApiResponse;
import com.inttelgo.mspse.api.v1.dto.CatalogDtos.Page;
import com.inttelgo.mspse.api.v1.dto.CatalogDtos.SelectItem;
import com.inttelgo.mspse.api.v1.dto.CatalogDtos.SelectList;
import com.inttelgo.mspse.api.v1.dto.CatalogDtos.StatusItem;
import com.inttelgo.mspse.api.v1.dto.CatalogDtos.StatusRequest;
import com.inttelgo.mspse.api.v1.dto.SearchRequest;
import com.inttelgo.mspse.common.Filter;
import com.inttelgo.mspse.service.EstadoTransaccionService;
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
@RequestMapping("/api/v1/pse/transaction-statuses")
public class EstadoTransaccionController {

    private final EstadoTransaccionService service;

    public EstadoTransaccionController(EstadoTransaccionService service) {
        this.service = service;
    }

    @GetMapping("/select")
    public ApiResponse<SelectList<SelectItem>> select() {
        return ApiResponse.ok(new SelectList<>(service.select()));
    }

    @GetMapping
    public ApiResponse<Page<StatusItem>> list(
            @RequestParam(defaultValue = "0") @Min(0) int skip,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", defaultValue = "asc") String sortOrder) {
        return ApiResponse.ok(service.list(skip, limit, sortBy, sortOrder, null));
    }

    @PostMapping("/search")
    public ApiResponse<Page<StatusItem>> search(
            @RequestParam(defaultValue = "0") @Min(0) int skip,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", defaultValue = "asc") String sortOrder,
            @RequestBody(required = false) SearchRequest body) {
        List<Filter> filters = body == null ? null : body.filters();
        return ApiResponse.ok(service.list(skip, limit, sortBy, sortOrder, filters));
    }

    @GetMapping("/{id}")
    public ApiResponse<StatusItem> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<StatusItem>> create(@Valid @RequestBody StatusRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(request)));
    }

    @PutMapping("/{id}")
    public ApiResponse<StatusItem> update(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<ApiResponse.Message> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(new ApiResponse.Message("Estado de transacción eliminado exitosamente"));
    }
}
