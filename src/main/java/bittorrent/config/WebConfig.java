package bittorrent.config;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configures global CORS so the React frontend (default: http://localhost:5173)
 * can talk to the Spring Boot API without browser errors. Customize the allowed
 * origins via the `app.cors.allowed-origins` property (comma separated list).
 * Use "http://localhost:*" to allow any port from localhost.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final List<String> allowedOrigins;

	public WebConfig(@Value("${app.cors.allowed-origins:http://localhost:5173}") String origins) {
		this.allowedOrigins = Arrays.stream(origins.split(","))
			.map(String::trim)
			.filter(origin -> !origin.isEmpty())
			.collect(Collectors.toList());
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		// Check if we need to allow all localhost ports
		boolean allowAllLocalhostPorts = allowedOrigins.stream()
			.anyMatch(origin -> origin.contains("localhost") && origin.contains("*"));
		
		if (allowAllLocalhostPorts) {
			// For development: allow all localhost origins with any port
			// Spring Boot's allowedOriginPatterns supports wildcard patterns
			// Pattern format: http://localhost:* matches any port
			registry.addMapping("/**")
				.allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*", "http://[::1]:*")
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
				.allowedHeaders("*")
				.allowCredentials(false)
				.maxAge(3600);
		} else {
			// Use specific origins from configuration
			registry.addMapping("/**")
				.allowedOriginPatterns(allowedOrigins.toArray(String[]::new))
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
				.allowedHeaders("*")
				.allowCredentials(false)
				.maxAge(3600);
		}
	}

}
