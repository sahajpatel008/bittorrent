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
		// Convert wildcard patterns like "http://localhost:*" to regex patterns
		List<String> patterns = allowedOrigins.stream()
			.map(origin -> origin.contains("*") ? origin.replace("*", "[0-9]+") : origin)
			.collect(Collectors.toList());
		
		registry.addMapping("/**")
			.allowedOriginPatterns(patterns.toArray(String[]::new))
			.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
			.allowedHeaders("*")
			.maxAge(3600);
	}

}
