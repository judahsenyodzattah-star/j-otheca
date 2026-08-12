package com.libranet.catalog.web;

import com.libranet.catalog.dto.CopyDto;
import com.libranet.catalog.dto.CopyRequests.CreateCopyRequest;
import com.libranet.catalog.dto.CopyRequests.UpdateCopyRequest;
import com.libranet.catalog.dto.CopyRequests.UpdateCopyStatusRequest;
import com.libranet.catalog.service.CatalogService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Staff copy management: register copies (barcode/QR identity) and track their physical state. */
@RestController
@RequestMapping("/api/v1/tenant/catalog")
public class TenantCopyController {

    private final CatalogService catalog;

    public TenantCopyController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/books/{bookId}/copies")
    public List<CopyDto> list(@PathVariable String bookId) {
        return catalog.listCopies(bookId);
    }

    @PostMapping("/books/{bookId}/copies")
    @ResponseStatus(HttpStatus.CREATED)
    public CopyDto add(@PathVariable String bookId, @Valid @RequestBody CreateCopyRequest req) {
        return catalog.addCopy(bookId, req);
    }

    @GetMapping("/copies/{copyId}")
    public CopyDto get(@PathVariable String copyId) {
        return catalog.getCopy(copyId);
    }

    @PutMapping("/copies/{copyId}")
    public CopyDto update(@PathVariable String copyId, @Valid @RequestBody UpdateCopyRequest req) {
        return catalog.updateCopy(copyId, req);
    }

    @PatchMapping("/copies/{copyId}/status")
    public CopyDto changeStatus(@PathVariable String copyId, @Valid @RequestBody UpdateCopyStatusRequest req) {
        return catalog.changeCopyStatus(copyId, req);
    }

    @DeleteMapping("/copies/{copyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String copyId) {
        catalog.deleteCopy(copyId);
    }
}
