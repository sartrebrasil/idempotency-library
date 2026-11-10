package com.example.idempotency.spring.store.jdbc;

import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;

import java.sql.DatabaseMetaData;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Adds {@code classpath:db/idempotency/<vendor>} to the application's Flyway locations, so the
 * {@code idempotency_record} migration runs without the application listing it. Leaves the
 * configuration alone when the location is already there (applications that list it themselves).
 */
public class IdempotencyFlywayLocationCustomizer implements FlywayConfigurationCustomizer {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFlywayLocationCustomizer.class);

    @Override
    public void customize(FluentConfiguration configuration) {
        String vendor = vendor(configuration);
        if (vendor == null) {
            log.warn("Could not detect the database vendor; add classpath:db/idempotency/<vendor> to "
                    + "spring.flyway.locations yourself.");
            return;
        }
        Location ours = new Location("classpath:db/idempotency/" + vendor);
        Location[] current = configuration.getLocations();
        boolean present = Arrays.stream(current)
                .anyMatch(location -> location.isClassPath() && location.getPath().equals(ours.getPath()));
        if (!present) {
            configuration.locations(Stream.concat(Arrays.stream(current), Stream.of(ours)).toArray(Location[]::new));
        }
    }

    private static String vendor(FluentConfiguration configuration) {
        if (configuration.getDataSource() == null) {
            return null;
        }
        try {
            String url = JdbcUtils.extractDatabaseMetaData(configuration.getDataSource(), DatabaseMetaData::getURL);
            DatabaseDriver driver = DatabaseDriver.fromJdbcUrl(url);
            return driver == DatabaseDriver.UNKNOWN ? null : driver.getId();
        } catch (MetaDataAccessException ex) {
            throw new IllegalStateException("Could not read the database URL for the idempotency migration", ex);
        }
    }
}
