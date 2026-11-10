package com.example.idempotency.spring.store.jdbc;

import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotencyFlywayLocationCustomizerTest {

    private final IdempotencyFlywayLocationCustomizer customizer = new IdempotencyFlywayLocationCustomizer();

    @Test
    void appendsTheVendorLocation() throws Exception {
        FluentConfiguration configuration = postgres().locations("classpath:db/migration");

        customizer.customize(configuration);

        assertThat(descriptors(configuration)).containsExactlyInAnyOrder("classpath:db/migration", "classpath:db/idempotency/postgresql");
    }

    @Test
    void leavesAnAlreadyListedLocationAlone() throws Exception {
        FluentConfiguration configuration = postgres().locations("classpath:db/migration", "classpath:/db/idempotency/postgresql");

        customizer.customize(configuration);

        assertThat(configuration.getLocations()).hasSize(2);
    }

    private static FluentConfiguration postgres() throws Exception {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getURL()).thenReturn("jdbc:postgresql://localhost/app");
        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(metaData);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return new FluentConfiguration().dataSource(dataSource);
    }

    private static String[] descriptors(FluentConfiguration configuration) {
        return Arrays.stream(configuration.getLocations()).map(Location::getDescriptor).toArray(String[]::new);
    }
}
