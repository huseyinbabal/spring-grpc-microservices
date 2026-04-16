package com.cargo.tracking;

import com.cargo.tracking.api.TrackingGrpcService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: verifies the Tracking Spring Boot context boots against
 * a real Postgres and the gRPC service bean is wired. Persistence +
 * real RPCs land in T4.3 / T4.4 / T4.5.
 */
@SpringBootTest
@Testcontainers
class TrackingApplicationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("tracking")
                    .withUsername("tracking")
                    .withPassword("tracking");

    @Autowired
    private TrackingGrpcService trackingGrpcService;

    @Test
    void context_loads_with_tracking_grpc_service_bean_present() {
        assertThat(trackingGrpcService).isNotNull();
    }
}
