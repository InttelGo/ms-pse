package com.inttelgo.mspse.api.v1;

import com.inttelgo.mspse.api.ApiResponse;
import com.inttelgo.mspse.api.v1.dto.CatalogDtos.Page;
import com.inttelgo.mspse.api.v1.dto.SearchRequest;
import com.inttelgo.mspse.api.v1.dto.TransactionDtos;
import com.inttelgo.mspse.common.Filter;
import com.inttelgo.mspse.service.PseService;
import com.inttelgo.mspse.service.TransaccionService;
import com.inttelgo.mspse.service.TransactionSettlement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consulta administrativa de transacciones. */
@Validated
@RestController
@RequestMapping("/api/v1/pse/transactions")
public class TransaccionController {

    private final TransaccionService transactionService;
    private final PseService pseService;

    public TransaccionController(TransaccionService transactionService, PseService pseService) {
        this.transactionService = transactionService;
        this.pseService = pseService;
    }

    @GetMapping
    public ApiResponse<Page<TransactionDtos.Response>> list(
            @RequestParam(defaultValue = "0") @Min(0) int skip,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", defaultValue = "desc") String sortOrder) {
        return ApiResponse.ok(transactionService.list(skip, limit, sortBy, sortOrder, null));
    }

    @PostMapping("/search")
    public ApiResponse<Page<TransactionDtos.Response>> search(
            @RequestParam(defaultValue = "0") @Min(0) int skip,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", defaultValue = "desc") String sortOrder,
            @RequestBody(required = false) SearchRequest body) {
        List<Filter> filters = body == null ? null : body.filters();
        return ApiResponse.ok(transactionService.list(skip, limit, sortBy, sortOrder, filters));
    }

    @GetMapping("/{id}")
    public ApiResponse<TransactionDtos.Response> get(@PathVariable Long id) {
        return ApiResponse.ok(transactionService.get(id));
    }

    /** Consulta ya el estado en PSE (sin esperar al job). */
    @PostMapping("/{id}/verify")
    public ApiResponse<TransactionDtos.Response> verify(@PathVariable Long id) {
        pseService.refresh(id);
        return ApiResponse.ok(transactionService.get(id));
    }

    /** Reintenta crear en ms-build la factura o los pagos que falten de una transacción aprobada. */
    @PostMapping("/{id}/settle")
    public ApiResponse<TransactionSettlement.Result> settle(@PathVariable Long id) {
        return ApiResponse.ok(pseService.settle(id));
    }
}
