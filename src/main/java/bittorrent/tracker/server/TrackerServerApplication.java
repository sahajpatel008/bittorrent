package bittorrent.tracker.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
    "bittorrent.tracker.server", 
    "bittorrent.bencode" // Scan bencode package if it has components (currently utils, but good practice)
})
public class TrackerServerApplication {

    public static void main(String[] args) {
        // Set default port to 8080 if not configured, or let properties handle it
        // We can pass different properties or profiles here.
        SpringApplication app = new SpringApplication(TrackerServerApplication.class);
        // app.setDefaultProperties(Collections.singletonMap("server.port", "8080"));
        app.run(args);
    }
}



