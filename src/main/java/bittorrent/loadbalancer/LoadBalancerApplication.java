package bittorrent.loadbalancer;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootApplication
@RestController
public class LoadBalancerApplication {

    // Dynamic list of backend tracker URLs
    private final List<String> trackerServers = new CopyOnWriteArrayList<>();

    private final AtomicInteger counter = new AtomicInteger(0);
    private final RestTemplate restTemplate = new RestTemplate();

    public static void main(String[] args) {
        // Load Balancer runs on port 8080 by default
        SpringApplication.run(LoadBalancerApplication.class, args);
    }
    
    @PostMapping("/register")
    public String registerTracker(@RequestParam("port") int port) {
        String url = "http://localhost:" + port;
        if (!trackerServers.contains(url)) {
            trackerServers.add(url);
            System.out.println("LoadBalancer: Registered new tracker at " + url);
            return "Registered " + url;
        }
        return "Already registered " + url;
    }

    @GetMapping("/announce")
    public ResponseEntity<byte[]> proxyAnnounce(HttpServletRequest request) {
        String backendUrl = getNextTrackerUrl();
        
        // Build the target URI with original query parameters
        URI targetUri = UriComponentsBuilder.fromHttpUrl(backendUrl)
                .path("/announce")
                .query(request.getQueryString())
                .build(true)
                .toUri();

        System.out.println("LoadBalancer: Forwarding to " + targetUri);

        try {
            HttpHeaders headers = new HttpHeaders();
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                headers.add(headerName, request.getHeader(headerName));
            }
            
            // Add X-Forwarded-For
            String clientIp = request.getRemoteAddr();
            headers.add("X-Forwarded-For", clientIp);

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            
            return restTemplate.exchange(targetUri, HttpMethod.GET, entity, byte[].class);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(502).body(("Bad Gateway: " + e.getMessage()).getBytes());
        }
    }

    private String getNextTrackerUrl() {
        if (trackerServers.isEmpty()) {
            throw new IllegalStateException("No tracker servers registered yet. Start a tracker first!");
        }
        int index = counter.getAndIncrement() % trackerServers.size();
        return trackerServers.get(Math.abs(index));
    }
}
