package com.jotheca.common.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jotheca.audit.AuditService;
import com.jotheca.common.tenant.PlaneContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for the platform-plane routing DECISION logic (Phase A). Least privilege: there is NO
 * cross-tenant write role — platform WRITES route to the TENANT plane (jotheca_app, RLS-confined to the
 * armed target library) and only platform GETs use the read-only jotheca_readall plane. Authorization of
 * {@code /api/v1/platform/**} (SUPER_ADMIN) is enforced by SecurityConfig and covered by
 * {@code TenantIsolationTest}; DB-level enforcement is covered by {@code RlsTenantIsolationTest}.
 */
class PlaneRoutingTest {

    @AfterEach
    void cleanup() {
        PlaneContext.clear();
    }

    @Test
    @DisplayName("PlaneContext defaults to TENANT, never the read-all plane, and clears")
    void planeContextDefaultsToTenantAndClears() {
        assertThat(PlaneContext.get()).isEqualTo(PlaneContext.TENANT);
        assertThat(PlaneContext.peek()).isNull();
        PlaneContext.set(PlaneContext.PLATFORM_READ);
        assertThat(PlaneContext.get()).isEqualTo(PlaneContext.PLATFORM_READ);
        PlaneContext.clear();
        assertThat(PlaneContext.peek()).isNull();
        assertThat(PlaneContext.get()).isEqualTo(PlaneContext.TENANT);
    }

    @Test
    @DisplayName("routing datasource lookup key follows PlaneContext")
    void routingDataSourceUsesPlaneContext() {
        PlaneRoutingDataSource ds = new PlaneRoutingDataSource();
        for (String plane : new String[]{PlaneContext.TENANT, PlaneContext.PLATFORM_READ}) {
            PlaneContext.set(plane);
            assertThat(ds.determineCurrentLookupKey()).isEqualTo(plane);
        }
    }

    @Test
    @DisplayName("GET /api/v1/platform/** routes to the read-only plane")
    void platformGetRoutesToReadPlane() {
        PlaneRoutingInterceptor i = new PlaneRoutingInterceptor(mock(AuditService.class));
        i.preHandle(new MockHttpServletRequest("GET", "/api/v1/platform/stats"),
                new MockHttpServletResponse(), new Object());
        assertThat(PlaneContext.peek()).isEqualTo(PlaneContext.PLATFORM_READ);
    }

    @Test
    @DisplayName("non-GET /api/v1/platform/** routes to the TENANT plane (least privilege, no write-all role)")
    void platformWriteRoutesToTenantPlane() {
        PlaneRoutingInterceptor i = new PlaneRoutingInterceptor(mock(AuditService.class));
        i.preHandle(new MockHttpServletRequest("POST", "/api/v1/platform/libraries"),
                new MockHttpServletResponse(), new Object());
        assertThat(PlaneContext.peek()).isEqualTo(PlaneContext.TENANT);
    }

    @Test
    @DisplayName("tenant traffic routes to the TENANT plane (never the read-all plane)")
    void tenantTrafficRoutesToTenantPlane() {
        PlaneRoutingInterceptor i = new PlaneRoutingInterceptor(mock(AuditService.class));
        i.preHandle(new MockHttpServletRequest("GET", "/api/v1/tenant/members"),
                new MockHttpServletResponse(), new Object());
        assertThat(PlaneContext.peek()).isEqualTo(PlaneContext.TENANT);
    }

    @Test
    @DisplayName("platform access (GET) is audited and the plane is cleared (no cross-request leakage)")
    void platformGetAccessAuditedAndPlaneCleared() {
        AuditService audit = mock(AuditService.class);
        PlaneRoutingInterceptor i = new PlaneRoutingInterceptor(audit);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/platform/stats");
        MockHttpServletResponse res = new MockHttpServletResponse();
        i.preHandle(req, res, new Object());
        i.afterCompletion(req, res, new Object(), null);
        verify(audit).log(any(), anyString(), anyString(), any());
        assertThat(PlaneContext.peek()).isNull();
    }

    @Test
    @DisplayName("platform WRITE access is also audited and the plane is cleared")
    void platformWriteAccessAuditedAndPlaneCleared() {
        AuditService audit = mock(AuditService.class);
        PlaneRoutingInterceptor i = new PlaneRoutingInterceptor(audit);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/platform/libraries");
        MockHttpServletResponse res = new MockHttpServletResponse();
        i.preHandle(req, res, new Object());
        i.afterCompletion(req, res, new Object(), null);
        verify(audit).log(any(), anyString(), anyString(), any());
        assertThat(PlaneContext.peek()).isNull();
    }

    @Test
    @DisplayName("plane is cleared even when the handler threw (exception path)")
    void planeClearedOnException() {
        AuditService audit = mock(AuditService.class);
        PlaneRoutingInterceptor i = new PlaneRoutingInterceptor(audit);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/platform/stats");
        MockHttpServletResponse res = new MockHttpServletResponse();
        i.preHandle(req, res, new Object());
        i.afterCompletion(req, res, new Object(), new RuntimeException("boom"));
        assertThat(PlaneContext.peek()).isNull();
    }

    @Test
    @DisplayName("tenant traffic is NOT audited as platform access")
    void tenantTrafficNotAuditedAsPlatform() {
        AuditService audit = mock(AuditService.class);
        PlaneRoutingInterceptor i = new PlaneRoutingInterceptor(audit);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/tenant/members");
        MockHttpServletResponse res = new MockHttpServletResponse();
        i.preHandle(req, res, new Object());
        i.afterCompletion(req, res, new Object(), null);
        verify(audit, never()).log(any(), anyString(), anyString(), any());
        assertThat(PlaneContext.peek()).isNull();
    }
}
