package bittorrent.tracker.server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@EnableScheduling
public class TrackerServerApplication {

    public static void main(String[] args) {
        // Allow passing port as a command line argument --server.port=0 (for random port)
        SpringApplication.run(TrackerServerApplication.class, args);
    }
}

@Component
class TrackerRegistration implements ApplicationListener<ApplicationReadyEvent> {

    @Autowired
    private Environment environment;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        String port = environment.getProperty("local.server.port");
        String loadBalancerUrl = "http://localhost:8080/register?port=" + port;
        
        System.out.println("Tracker: Registering self (port " + port + ") with Load Balancer...");
        
        try {
            new RestTemplate().postForObject(loadBalancerUrl, null, String.class);
            System.out.println("Tracker: Registration successful!");
        } catch (Exception e) {
            System.err.println("Tracker: Failed to register with Load Balancer at 8080. Is it running? " + e.getMessage());
        }
    }
}
