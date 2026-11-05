package com.jotheca.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.jotheca.discovery.config.GlobalDiscoveryProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DiscoverySchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired GlobalDiscoveryProperties properties;

    @Test
    void projectionSchemaAndPrivateSourceConstraintsExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT UPPER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE UPPER(TABLE_SCHEMA) = 'DISCOVERY'",
                String.class);
        assertThat(tables).contains("EDITIONS", "HOLDINGS", "HOLDING_SOURCES");

        Integer sourceIdentityConstraint = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                WHERE UPPER(CONSTRAINT_SCHEMA) = 'DISCOVERY'
                  AND UPPER(CONSTRAINT_NAME) = 'UQ_DISCOVERY_SOURCE_IDENTITY'
                """, Integer.class);
        assertThat(sourceIdentityConstraint).isEqualTo(1);

        Integer holdingLibraryConstraint = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                WHERE UPPER(CONSTRAINT_SCHEMA) = 'DISCOVERY'
                  AND UPPER(CONSTRAINT_NAME) = 'UQ_DISCOVERY_HOLDING_EDITION_LIBRARY'
                """, Integer.class);
        assertThat(holdingLibraryConstraint).isEqualTo(1);
    }

    @Test
    void globalDiscoveryIsDisabledByDefault() {
        assertThat(properties.isGlobalEnabled()).isFalse();
    }
}
