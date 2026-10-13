package com.jotheca.common.datasource;

import com.jotheca.common.tenant.PlaneContext;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Routes each acquired connection to the tenant or the platform-read datasource according to
 * {@link PlaneContext}. The target map (and the safe default = tenant) is wired by {@link PlatformPlaneConfig}.
 *
 * <p>There is deliberately NO platform-write datasource. Platform writes run on the tenant plane
 * (jotheca_app, NOBYPASSRLS) with the write context armed to the one library being modified, so they
 * stay confined by RLS. A dedicated BYPASSRLS write role would be a cross-tenant write primitive.
 */
public class PlaneRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return PlaneContext.get();
    }
}
