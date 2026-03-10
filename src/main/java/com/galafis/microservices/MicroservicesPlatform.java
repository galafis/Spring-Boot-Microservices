package com.galafis.microservices;

import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Spring Boot Microservices Platform
 * Demonstrates microservice patterns: API Gateway, Service Registry,
 * Circuit Breaker, Load Balancing, and Health Monitoring.
 *
 * @author Gabriel Demetrios Lafis
 * @version 2.0.0
 */
public class MicroservicesPlatform {

    private static final Logger LOGGER = Logger.getLogger(MicroservicesPlatform.class.getName());

    // ---- Service Registry ----

    public static class ServiceRegistry {
        private final Map<String, List<ServiceInstance>> registry = new ConcurrentHashMap<>();

        public void register(ServiceInstance instance) {
            registry.computeIfAbsent(instance.getServiceName(), k -> new CopyOnWriteArrayList<>())
                    .add(instance);
            LOGGER.info("Registered: " + instance.getServiceName() + " at " + instance.getHost() + ":" + instance.getPort());
        }

        public void deregister(String serviceName, String instanceId) {
            List<ServiceInstance> instances = registry.get(serviceName);
            if (instances != null) {
                instances.removeIf(i -> i.getInstanceId().equals(instanceId));
            }
        }

        public List<ServiceInstance> getInstances(String serviceName) {
            return registry.getOrDefault(serviceName, Collections.emptyList());
        }

        public Map<String, Integer> getServiceCounts() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            registry.forEach((name, instances) -> counts.put(name, instances.size()));
            return counts;
        }
    }

    public static class ServiceInstance {
        private final String instanceId;
        private final String serviceName;
        private final String host;
        private final int port;
        private final LocalDateTime registeredAt;
        private volatile boolean healthy;

        public ServiceInstance(String serviceName, String host, int port) {
            this.instanceId = serviceName + "-" + UUID.randomUUID().toString().substring(0, 6);
            this.serviceName = serviceName;
            this.host = host;
            this.port = port;
            this.registeredAt = LocalDateTime.now();
            this.healthy = true;
        }

        public String getInstanceId() { return instanceId; }
        public String getServiceName() { return serviceName; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }
    }

    // ---- API Gateway ----

    public static class ApiGateway {
        private final ServiceRegistry registry;
        private final LoadBalancer loadBalancer;
        private final CircuitBreaker circuitBreaker;
        private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

        public ApiGateway(ServiceRegistry registry) {
            this.registry = registry;
            this.loadBalancer = new LoadBalancer();
            this.circuitBreaker = new CircuitBreaker(5, Duration.ofSeconds(30));
        }

        public GatewayResponse routeRequest(String serviceName, String path, Map<String, String> headers) {
            // Rate limiting
            RateLimiter limiter = rateLimiters.computeIfAbsent(serviceName,
                    k -> new RateLimiter(100, Duration.ofMinutes(1)));
            if (!limiter.tryAcquire()) {
                return new GatewayResponse(429, "Rate limit exceeded", null);
            }

            // Circuit breaker check
            if (!circuitBreaker.allowRequest()) {
                return new GatewayResponse(503, "Service unavailable (circuit open)", null);
            }

            // Load balance
            List<ServiceInstance> instances = registry.getInstances(serviceName);
            if (instances.isEmpty()) {
                return new GatewayResponse(404, "Service not found: " + serviceName, null);
            }

            ServiceInstance target = loadBalancer.select(instances);
            if (target == null) {
                return new GatewayResponse(503, "No healthy instances", null);
            }

            // Simulate forwarding
            String url = "http://" + target.getHost() + ":" + target.getPort() + path;
            LOGGER.info("Routing " + serviceName + path + " -> " + url);
            circuitBreaker.recordSuccess();

            Map<String, Object> responseData = new LinkedHashMap<>();
            responseData.put("service", serviceName);
            responseData.put("instance", target.getInstanceId());
            responseData.put("path", path);
            responseData.put("timestamp", LocalDateTime.now().toString());

            return new GatewayResponse(200, "OK", responseData);
        }
    }

    public static class GatewayResponse {
        private final int statusCode;
        private final String message;
        private final Map<String, Object> data;

        public GatewayResponse(int statusCode, String message, Map<String, Object> data) {
            this.statusCode = statusCode;
            this.message = message;
            this.data = data;
        }

        public int getStatusCode() { return statusCode; }
        public String getMessage() { return message; }
        public Map<String, Object> getData() { return data; }

        @Override
        public String toString() {
            return "HTTP " + statusCode + " - " + message + (data != null ? " " + data : "");
        }
    }

    // ---- Load Balancer (Round Robin) ----

    public static class LoadBalancer {
        private final Map<String, Integer> counters = new ConcurrentHashMap<>();

        public ServiceInstance select(List<ServiceInstance> instances) {
            List<ServiceInstance> healthy = new ArrayList<>();
            for (ServiceInstance inst : instances) {
                if (inst.isHealthy()) healthy.add(inst);
            }
            if (healthy.isEmpty()) return null;

            String key = healthy.get(0).getServiceName();
            int idx = counters.merge(key, 1, Integer::sum) - 1;
            return healthy.get(idx % healthy.size());
        }
    }

    // ---- Circuit Breaker ----

    public static class CircuitBreaker {
        public enum State { CLOSED, OPEN, HALF_OPEN }

        private final int failureThreshold;
        private final Duration resetTimeout;
        private volatile State state = State.CLOSED;
        private int failureCount = 0;
        private long lastFailureTime = 0;

        public CircuitBreaker(int failureThreshold, Duration resetTimeout) {
            this.failureThreshold = failureThreshold;
            this.resetTimeout = resetTimeout;
        }

        public synchronized boolean allowRequest() {
            if (state == State.CLOSED) return true;
            if (state == State.OPEN) {
                if (System.currentTimeMillis() - lastFailureTime > resetTimeout.toMillis()) {
                    state = State.HALF_OPEN;
                    return true;
                }
                return false;
            }
            return true; // HALF_OPEN allows one request
        }

        public synchronized void recordSuccess() {
            failureCount = 0;
            state = State.CLOSED;
        }

        public synchronized void recordFailure() {
            failureCount++;
            lastFailureTime = System.currentTimeMillis();
            if (failureCount >= failureThreshold) {
                state = State.OPEN;
            }
        }

        public State getState() { return state; }
    }

    // ---- Rate Limiter (Token Bucket) ----

    public static class RateLimiter {
        private final int maxTokens;
        private final Duration refillPeriod;
        private int tokens;
        private long lastRefill;

        public RateLimiter(int maxTokens, Duration refillPeriod) {
            this.maxTokens = maxTokens;
            this.refillPeriod = refillPeriod;
            this.tokens = maxTokens;
            this.lastRefill = System.currentTimeMillis();
        }

        public synchronized boolean tryAcquire() {
            refill();
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            if (now - lastRefill >= refillPeriod.toMillis()) {
                tokens = maxTokens;
                lastRefill = now;
            }
        }
    }

    // ---- Health Monitor ----

    public static class HealthMonitor {
        private final ServiceRegistry registry;
        private final Map<String, Map<String, Object>> healthReports = new ConcurrentHashMap<>();

        public HealthMonitor(ServiceRegistry registry) {
            this.registry = registry;
        }

        public Map<String, Object> checkHealth() {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("timestamp", LocalDateTime.now().toString());
            report.put("services", registry.getServiceCounts());

            Map<String, String> instanceHealth = new LinkedHashMap<>();
            registry.getServiceCounts().keySet().forEach(service -> {
                registry.getInstances(service).forEach(inst -> {
                    instanceHealth.put(inst.getInstanceId(),
                            inst.isHealthy() ? "UP" : "DOWN");
                });
            });
            report.put("instances", instanceHealth);
            return report;
        }
    }

    // ---- Demo ----

    public static void main(String[] args) {
        System.out.println("=== Spring Boot Microservices Platform ===\n");

        ServiceRegistry registry = new ServiceRegistry();
        ApiGateway gateway = new ApiGateway(registry);
        HealthMonitor monitor = new HealthMonitor(registry);

        // Register services
        registry.register(new ServiceInstance("user-service", "localhost", 8081));
        registry.register(new ServiceInstance("user-service", "localhost", 8082));
        registry.register(new ServiceInstance("order-service", "localhost", 8091));
        registry.register(new ServiceInstance("order-service", "localhost", 8092));
        registry.register(new ServiceInstance("payment-service", "localhost", 8101));
        registry.register(new ServiceInstance("notification-service", "localhost", 8111));

        System.out.println("\nService Registry: " + registry.getServiceCounts());

        // Route requests through API Gateway
        System.out.println("\n--- API Gateway Routing ---");
        String[] services = {"user-service", "order-service", "payment-service", "notification-service"};
        String[] paths = {"/api/users/1", "/api/orders", "/api/payments/process", "/api/notify"};

        for (int i = 0; i < services.length; i++) {
            GatewayResponse resp = gateway.routeRequest(services[i], paths[i], Collections.emptyMap());
            System.out.println("  " + resp);
        }

        // Load balancing demonstration
        System.out.println("\n--- Load Balancing (Round Robin) ---");
        for (int i = 0; i < 4; i++) {
            GatewayResponse resp = gateway.routeRequest("user-service", "/api/users", null);
            System.out.println("  Request " + (i + 1) + ": routed to " + resp.getData().get("instance"));
        }

        // Health check
        System.out.println("\n--- Health Monitor ---");
        Map<String, Object> health = monitor.checkHealth();
        System.out.println("  " + health);

        System.out.println("\nPlatform running successfully!");
    }
}
