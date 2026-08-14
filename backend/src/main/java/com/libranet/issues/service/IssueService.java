package com.libranet.issues.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.circulation.service.NotificationService;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.common.web.SecurityUsers;
import com.libranet.identity.repo.UserRepository;
import com.libranet.issues.dto.IssueDtos.AssignRequest;
import com.libranet.issues.dto.IssueDtos.CreateIssueRequest;
import com.libranet.issues.dto.IssueDtos.IssueDetailDto;
import com.libranet.issues.dto.IssueDtos.IssueDto;
import com.libranet.issues.dto.IssueDtos.IssueMessageDto;
import com.libranet.issues.dto.IssueDtos.IssueMessageRequest;
import com.libranet.issues.dto.IssueDtos.NoteRequest;
import com.libranet.issues.dto.IssueDtos.PriorityRequest;
import com.libranet.issues.model.Issue;
import com.libranet.issues.model.IssueMessage;
import com.libranet.issues.repo.IssueMessageRepository;
import com.libranet.issues.repo.IssueRepository;
import com.libranet.membership.model.LibraryRole;
import com.libranet.membership.model.Membership;
import com.libranet.membership.model.MembershipStatus;
import com.libranet.membership.repo.MembershipRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.libranet.permissions.model.LibraryPerm;
import com.libranet.permissions.service.PermissionService;

/**
 * The support lane (audit P2 — spec §17–§23). Rules enforced HERE, never in the UI:
 * <ul>
 *   <li>Reporter identity comes from the caller's membership — never re-asked (§19).</li>
 *   <li>10 issues/day/user/tenant and 10k characters per field (§21 known limits).</li>
 *   <li>{@code clientKey} dedupe makes creates idempotent; retried submits replay the
 *       original issue instead of opening a twin.</li>
 *   <li>Internal notes are stripped for non-staff readers — hidden UI is not security.</li>
 *   <li>Cross-tenant reads answer 404; the existence of another tenant's issue is secret.</li>
 * </ul>
 * All audit entries carry ids, categories and lengths only — message text can name people.
 */
@Service
public class IssueService {

    /** Spec §21: at most 10 new issues per user per tenant per day. */
    static final int DAILY_LIMIT = 10;

    private static final Set<String> STAFF = Set.of(
            LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());

    private final IssueRepository issues;
    private final IssueMessageRepository messages;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AuditService audit;

        private final PermissionService permissions;

    public IssueService(IssueRepository issues, IssueMessageRepository messages,
                        MembershipRepository memberships, UserRepository users,
                        NotificationService notifications, AuditService audit,
                            PermissionService permissions) {
        this.permissions = permissions;
        this.issues = issues;
        this.messages = messages;
        this.memberships = memberships;
        this.users = users;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ---------- reporter side ----------

    @Transactional
    public IssueDto create(CreateIssueRequest req) {
        TenantContext.Current ctx = TenantContext.require();
        String tenantId = ctx.libraryId();
        String userId = SecurityUsers.current().id();

        Issue.Category category = parse(Issue.Category.class, req.category(), "ISSUE_CATEGORY_INVALID");
        Issue.Source source = req.source() == null ? Issue.Source.APP
                : parse(Issue.Source.class, req.source(), "ISSUE_SOURCE_INVALID");

        // Idempotent create (spec §21): a retried submit replays the original.
        if (req.clientKey() != null && !req.clientKey().isBlank()) {
            var existing = issues.findByTenantIdAndReporterUserIdAndClientKey(tenantId, userId,
                    req.clientKey());
            if (existing.isPresent()) {
                return toDto(existing.get());
            }
        }

        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        if (issues.countByTenantIdAndReporterUserIdAndCreatedAtAfter(tenantId, userId, dayStart)
                >= DAILY_LIMIT) {
            throw ApiException.tooMany("ISSUE_RATE_LIMIT",
                    "You can open up to " + DAILY_LIMIT + " issues per day — the library team "
                            + "replies on existing ones too, so add a message there instead.");
        }

        Issue issue = new Issue();
        issue.setTenantId(tenantId);
        issue.setIssueNumber(issues.maxIssueNumber(tenantId) + 1);
        issue.setReporterMembershipId(ctx.membershipId());
        issue.setReporterUserId(userId);
        issue.setCategory(category.name());
        issue.setSubject(req.subject().trim());
        issue.setDescription(req.description().trim());
        issue.setSource(source.name());
        issue.setClientKey(req.clientKey());
        issues.save(issue);

        notifyStaff(tenantId, "ISSUE_OPENED",
                "New issue " + issue.ref() + " - " + category.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                issue.getSubject());
        audit.log(AuditAction.ISSUE_CREATED, "Tenant", tenantId, Map.of(
                "ref", issue.ref(), "category", issue.getCategory(), "source", issue.getSource(),
                "subjectChars", issue.getSubject().length(), "descriptionChars", issue.getDescription().length()));
        return toDto(issue);
    }

    @Transactional(readOnly = true)
    public Page<IssueDto> mine(Pageable pageable) {
        TenantContext.Current ctx = TenantContext.require();
        return issues.findByTenantIdAndReporterUserId(ctx.libraryId(), SecurityUsers.current().id(), pageable)
                .map(this::toDto);
    }

    // ---------- shared (reporter on own issue; staff on any) ----------

    @Transactional(readOnly = true)
    public IssueDetailDto detail(String issueId) {
        TenantContext.Current ctx = TenantContext.require();
        Issue issue = loadVisible(issueId, ctx);
        boolean staff = ctx.hasAnyRole(STAFF.toArray(String[]::new));
        var thread = messages.findByTenantIdAndIssueIdOrderByCreatedAtAsc(ctx.libraryId(), issue.getId())
                .stream()
                .filter(m -> staff || !m.isInternal())               // enforcement, not UI hiding
                .map(m -> toMessageDto(m, ctx.libraryId()))
                .toList();
        return new IssueDetailDto(toDto(issue), issue.getDescription(), thread);
    }

    @Transactional
    public IssueMessageDto addMessage(String issueId, IssueMessageRequest req) {
        TenantContext.Current ctx = TenantContext.require();
        Issue issue = loadVisible(issueId, ctx);
        String tenantId = ctx.libraryId();
        String userId = SecurityUsers.current().id();
        boolean staff = ctx.hasAnyRole(STAFF.toArray(String[]::new));
        boolean reporter = issue.getReporterUserId().equals(userId);
        boolean internal = Boolean.TRUE.equals(req.internal());
        if (internal && !staff) {
            throw ApiException.forbidden("INTERNAL_NOTE_FORBIDDEN", "Only library staff can write internal notes.");
        }

        IssueMessage message = new IssueMessage();
        message.setTenantId(tenantId);
        message.setIssueId(issue.getId());
        message.setAuthorMembershipId(ctx.membershipId());
        message.setAuthorUserId(userId);
        message.setBody(req.body().trim());
        message.setInternal(internal);
        messages.save(message);

        // Conversation-driven status moves (spec §23): reporter info on a NEEDS_INFO
        // issue hands it back to the team; replying on RESOLVED reopens it.
        String from = issue.getStatus();
        if (reporter && !internal) {
            if (Issue.Status.NEEDS_INFO.name().equals(from)) {
                transition(issue, Issue.Status.IN_PROGRESS);
            } else if (Issue.Status.RESOLVED.name().equals(from)) {
                transition(issue, Issue.Status.OPEN);
                issue.setResolvedAt(null);
            }
        } else if (staff && !internal && Issue.Status.OPEN.name().equals(from)) {
            transition(issue, Issue.Status.IN_PROGRESS);
        }
        issues.save(issue);

        if (!internal) {
            notifyCounterparty(issue, staff,
                    "Activity on issue " + issue.ref(),
                    staff ? "The library team replied on your issue." : "The reporter added information.");
        }
        audit.log(AuditAction.ISSUE_MESSAGE_ADDED, "Tenant", tenantId, Map.of(
                "ref", issue.ref(), "internal", internal, "chars", message.getBody().length()));
        return toMessageDto(message, tenantId);
    }

    // ---------- staff side ----------

    @Transactional(readOnly = true)
    public Page<IssueDto> inbox(String status, String category, String assignee, Pageable pageable) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.ISSUES_HANDLE);
        if (status != null) parse(Issue.Status.class, status, "ISSUE_STATUS_INVALID");
        if (category != null) parse(Issue.Category.class, category, "ISSUE_CATEGORY_INVALID");
        if ("me".equals(assignee)) assignee = ctx.membershipId();
        if ("none".equals(assignee)) {
            return issues.inboxUnassigned(ctx.libraryId(), status, category, pageable).map(this::toDto);
        }
        return issues.inbox(ctx.libraryId(), status, category, assignee, pageable).map(this::toDto);
    }

    @Transactional
    public IssueDto assign(String issueId, AssignRequest req) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.ISSUES_HANDLE);
        Issue issue = loadStaff(issueId, ctx);
        String assignee = req.assigneeMembershipId();
        if ("me".equals(assignee)) assignee = ctx.membershipId();
        if (assignee != null) {
            Membership m = memberships.findById(assignee)
                    .orElseThrow(() -> ApiException.badRequest("ASSIGNEE_NOT_FOUND", "No such membership."));
            TenantGuard.requireBelongsToCurrentTenant(m.getTenantId());
            if (!STAFF.contains(m.getRole().name()) || m.getStatus() != MembershipStatus.APPROVED) {
                throw ApiException.badRequest("ASSIGNEE_NOT_STAFF", "Issues can only be assigned to active library staff.");
            }
        }
        issue.setAssigneeMembershipId(assignee);
        issues.save(issue);
        if (assignee != null) {
            memberships.findById(assignee).ifPresent(m -> notifications.notify(
                    ctx.libraryId(), m.getUserId(), "ISSUE_ASSIGNED",
                    "Issue " + issue.ref() + " assigned to you", issue.getSubject()));
        }
        audit.log(AuditAction.ISSUE_ASSIGNED, "Tenant", ctx.libraryId(), Map.of(
                "ref", issue.ref(), "assigned", assignee != null));
        return toDto(issue);
    }

    @Transactional
    public IssueDto setPriority(String issueId, PriorityRequest req) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.ISSUES_HANDLE);
        Issue issue = loadStaff(issueId, ctx);
        issue.setPriority(parse(Issue.Priority.class, req.priority(), "ISSUE_PRIORITY_INVALID").name());
        issues.save(issue);
        return toDto(issue);
    }

    @Transactional
    public IssueDto requestInfo(String issueId, NoteRequest req) {
        TenantContext.Current ctx = staffTransition(issueId, Issue.Status.NEEDS_INFO);
        Issue issue = issues.findById(issueId).orElseThrow();
        postStaffNote(ctx, issue, "Info requested: " + req.note().trim(), false);
        notifications.notify(ctx.libraryId(), issue.getReporterUserId(), "ISSUE_NEEDS_INFO",
                "The library needs a bit more on issue " + issue.ref(), req.note().trim());
        return toDto(issue);
    }

    @Transactional
    public IssueDto escalate(String issueId, NoteRequest req) {
        TenantContext.Current ctx = staffTransition(issueId, Issue.Status.ESCALATED);
        Issue issue = issues.findById(issueId).orElseThrow();
        if (req != null && req.note() != null && !req.note().isBlank()) {
            postStaffNote(ctx, issue, "Escalated: " + req.note().trim(), true); // internal
        }
        notifyStaff(ctx.libraryId(), "ISSUE_ESCALATED",
                "Issue " + issue.ref() + " was escalated", issue.getSubject());
        return toDto(issue);
    }

    @Transactional
    public IssueDto resolve(String issueId, NoteRequest req) {
        TenantContext.Current ctx = staffTransition(issueId, Issue.Status.RESOLVED);
        Issue issue = issues.findById(issueId).orElseThrow();
        issue.setResolvedAt(Instant.now());
        issues.save(issue);
        String resolution = req == null || req.note() == null || req.note().isBlank()
                ? "Resolved by the library team." : req.note().trim();
        postStaffNote(ctx, issue, resolution, false);
        notifications.notify(ctx.libraryId(), issue.getReporterUserId(), "ISSUE_RESOLVED",
                "Issue " + issue.ref() + " resolved", resolution);
        return toDto(issue);
    }

    @Transactional
    public IssueDto reopen(String issueId) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.ISSUES_HANDLE);
        Issue issue = loadStaff(issueId, ctx);
        if (!Issue.Status.RESOLVED.name().equals(issue.getStatus())) {
            throw ApiException.conflict("ISSUE_NOT_RESOLVED", "Only resolved issues can be reopened.");
        }
        transition(issue, Issue.Status.OPEN);
        issue.setResolvedAt(null);
        issues.save(issue);
        notifications.notify(ctx.libraryId(), issue.getReporterUserId(), "ISSUE_REOPENED",
                "Issue " + issue.ref() + " was reopened", issue.getSubject());
        return toDto(issue);
    }

    // ---------- internals ----------

    private TenantContext.Current staffTransition(String issueId, Issue.Status to) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.ISSUES_HANDLE);
        Issue issue = loadStaff(issueId, ctx);
        if (Issue.Status.RESOLVED.name().equals(issue.getStatus())) {
            throw ApiException.conflict("ISSUE_CLOSED",
                    "This issue is resolved — reopen it before changing its state.");
        }
        transition(issue, to);
        issues.save(issue);
        return ctx;
    }

    private void transition(Issue issue, Issue.Status to) {
        if (!issue.getStatus().equals(to.name())) {
            String tenantId = issue.getTenantId();
            String from = issue.getStatus();
            issue.setStatus(to.name());
            audit.log(AuditAction.ISSUE_STATUS_CHANGED, "Tenant", tenantId, Map.of(
                    "ref", issue.ref(), "from", from, "to", to.name()));
        }
    }

    private Issue loadVisible(String issueId, TenantContext.Current ctx) {
        Issue issue = issues.findById(issueId)
                .orElseThrow(() -> ApiException.notFound("ISSUE_NOT_FOUND", "Issue not found"));
        TenantGuard.requireBelongsToCurrentTenant(issue.getTenantId());
        boolean staff = ctx.hasAnyRole(STAFF.toArray(String[]::new));
        if (!staff && !issue.getReporterUserId().equals(SecurityUsers.current().id())) {
            throw ApiException.notFound("ISSUE_NOT_FOUND", "Issue not found"); // others' issues hidden
        }
        return issue;
    }

    private Issue loadStaff(String issueId, TenantContext.Current ctx) {
        Issue issue = issues.findById(issueId)
                .orElseThrow(() -> ApiException.notFound("ISSUE_NOT_FOUND", "Issue not found"));
        TenantGuard.requireBelongsToCurrentTenant(issue.getTenantId());
        return issue;
    }

    private void postStaffNote(TenantContext.Current ctx, Issue issue, String body, boolean internal) {
        IssueMessage m = new IssueMessage();
        m.setTenantId(ctx.libraryId());
        m.setIssueId(issue.getId());
        m.setAuthorMembershipId(ctx.membershipId());
        m.setAuthorUserId(SecurityUsers.current().id());
        m.setBody(body.length() > 10000 ? body.substring(0, 10000) : body);
        m.setInternal(internal);
        messages.save(m);
    }

    private void notifyStaff(String tenantId, String type, String title, String body) {
        for (Membership staff : memberships.findByTenantIdAndRoleInAndStatus(tenantId,
                List.of(LibraryRole.LIBRARY_ADMIN, LibraryRole.LIBRARIAN), MembershipStatus.APPROVED)) {
            if (staff.getUserId().equals(SecurityUsers.current().id())) continue; // don't ping yourself
            notifications.notify(tenantId, staff.getUserId(), type, title, body);
        }
    }

    /** Tell the other side of the conversation: staff reply → reporter; reporter reply → assignee or staff. */
    private void notifyCounterparty(Issue issue, boolean authorIsStaff, String title, String body) {
        if (authorIsStaff) {
            notifications.notify(issue.getTenantId(), issue.getReporterUserId(), "ISSUE_MESSAGE",
                    title + ": " + issue.ref(), body);
        } else if (issue.getAssigneeMembershipId() != null) {
            memberships.findById(issue.getAssigneeMembershipId()).ifPresent(m -> notifications.notify(
                    issue.getTenantId(), m.getUserId(), "ISSUE_MESSAGE", title, body));
        } else {
            notifyStaff(issue.getTenantId(), "ISSUE_MESSAGE", title, body);
        }
    }

    private IssueDto toDto(Issue issue) {
        String tenantId = issue.getTenantId();
        String reporterName = users.findById(issue.getReporterUserId())
                .map(com.libranet.identity.model.User::getFullName).orElse("Member");
        String reporterNumber = memberships.findById(issue.getReporterMembershipId())
                .map(Membership::getMembershipNumber).orElse(null);
        String assigneeName = null;
        if (issue.getAssigneeMembershipId() != null) {
            assigneeName = memberships.findById(issue.getAssigneeMembershipId())
                    .flatMap(m -> users.findById(m.getUserId()))
                    .map(com.libranet.identity.model.User::getFullName).orElse("Staff");
        }
        long count = messages.countByTenantIdAndIssueId(tenantId, issue.getId());
        return IssueDto.from(issue, reporterName, reporterNumber, assigneeName, count);
    }

    private IssueMessageDto toMessageDto(IssueMessage m, String tenantId) {
        boolean system = m.getAuthorUserId() == null;
        String authorName = system ? "LibraNet assistant"
                : users.findById(m.getAuthorUserId())
                        .map(com.libranet.identity.model.User::getFullName).orElse("Member");
        boolean staffAuthored = !system && m.getAuthorMembershipId() != null
                && memberships.findById(m.getAuthorMembershipId())
                        .map(mm -> STAFF.contains(mm.getRole().name())).orElse(false);
        return new IssueMessageDto(m.getId(), m.getAuthorUserId(), authorName, staffAuthored,
                m.isInternal(), system, m.getBody(), m.getCreatedAt());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String code) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(code,
                    "Must be one of: " + String.join(", ", java.util.Arrays.stream(type.getEnumConstants())
                            .map(Enum::name).toList()));
        }
    }
}
