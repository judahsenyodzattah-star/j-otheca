package com.jotheca.issues.web;

import com.jotheca.issues.dto.IssueDtos.AssignRequest;
import com.jotheca.issues.dto.IssueDtos.CreateIssueRequest;
import com.jotheca.issues.dto.IssueDtos.IssueDetailDto;
import com.jotheca.issues.dto.IssueDtos.IssueDto;
import com.jotheca.issues.dto.IssueDtos.IssueMessageDto;
import com.jotheca.issues.dto.IssueDtos.IssueMessageRequest;
import com.jotheca.issues.dto.IssueDtos.NoteRequest;
import com.jotheca.issues.dto.IssueDtos.PriorityRequest;
import com.jotheca.issues.service.IssueService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tenant support lane: reporter + staff ends of the same issues (audit spec §17–§23). */
@RestController
@RequestMapping("/api/v1/tenant/issues")
public class TenantIssueController {

    private final IssueService issueService;

    public TenantIssueController(IssueService issueService) {
        this.issueService = issueService;
    }

    @Operation(summary = "Open a support issue")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssueDto create(@Valid @RequestBody CreateIssueRequest req) {
        return issueService.create(req);
    }

    @Operation(summary = "My issues")
    @GetMapping("/me")
    public Page<IssueDto> mine(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return issueService.mine(pageRequest(page, size));
    }

    @Operation(summary = "Staff support inbox")
    @GetMapping("/inbox")
    public Page<IssueDto> inbox(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String category,
                                @RequestParam(required = false) String assignee,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return issueService.inbox(status, category, assignee, pageRequest(page, size));
    }

    @Operation(summary = "Issue detail + thread")
    @GetMapping("/{id}")
    public IssueDetailDto detail(@PathVariable String id) {
        return issueService.detail(id);
    }

    @Operation(summary = "Add a message to an issue")
    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueMessageDto message(@PathVariable String id, @Valid @RequestBody IssueMessageRequest req) {
        return issueService.addMessage(id, req);
    }

    @Operation(summary = "Assign an issue (staff)")
    @PostMapping("/{id}/assign")
    public IssueDto assign(@PathVariable String id, @RequestBody AssignRequest req) {
        return issueService.assign(id, req);
    }

    @Operation(summary = "Set issue priority (staff)")
    @PostMapping("/{id}/priority")
    public IssueDto priority(@PathVariable String id, @Valid @RequestBody PriorityRequest req) {
        return issueService.setPriority(id, req);
    }

    @Operation(summary = "Request more info from the reporter (staff)")
    @PostMapping("/{id}/request-info")
    public IssueDto requestInfo(@PathVariable String id, @Valid @RequestBody NoteRequest req) {
        return issueService.requestInfo(id, req);
    }

    @Operation(summary = "Escalate an issue (staff)")
    @PostMapping("/{id}/escalate")
    public IssueDto escalate(@PathVariable String id, @RequestBody(required = false) NoteRequest req) {
        return issueService.escalate(id, req);
    }

    @Operation(summary = "Resolve an issue (staff)")
    @PostMapping("/{id}/resolve")
    public IssueDto resolve(@PathVariable String id, @RequestBody(required = false) NoteRequest req) {
        return issueService.resolve(id, req);
    }

    @Operation(summary = "Reopen a resolved issue (staff)")
    @PostMapping("/{id}/reopen")
    public IssueDto reopen(@PathVariable String id) {
        return issueService.reopen(id);
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.min(page, 1000), Math.min(Math.max(size, 1), 100),
                Sort.by("createdAt").descending());
    }
}
