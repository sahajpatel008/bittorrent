package bittorrent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import java.util.HexFormat;

@SpringBootApplication
@ComponentScan(
    basePackages = "bittorrent",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX, 
        pattern = "bittorrent\\.tracker\\.server\\..*|bittorrent\\.loadbalancer\\..*"
    )
)
public class BitTorrentApplication {

	// Keep utilities accessible for the entire application
	public static final HexFormat HEX_FORMAT = HexFormat.of();
	public static final boolean DEBUG = true; 

	public static void main(String[] args) {
		SpringApplication.run(BitTorrentApplication.class, args);
	}

}
