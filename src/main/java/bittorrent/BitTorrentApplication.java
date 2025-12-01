// package bittorrent;

// import org.springframework.boot.SpringApplication;
// import org.springframework.boot.WebApplicationType;
// import org.springframework.boot.autoconfigure.SpringBootApplication;
// import java.util.HexFormat;

// import bittorrent.service.BitTorrentService;

// @SpringBootApplication
// public class BitTorrentApplication {

// 	// Keep utilities accessible for the entire application
// 	public static final HexFormat HEX_FORMAT = HexFormat.of();
// 	public static final boolean DEBUG = true; 

// 	public static void main(String[] args) {
// 		// Always disable web environment for the Client Application
// 		SpringApplication app = new SpringApplication(BitTorrentApplication.class);
// 		app.setWebApplicationType(WebApplicationType.NONE); 
		
// 		if (args.length > 0) {
// 			var context = app.run(args);
// 			var service = context.getBean(BitTorrentService.class);
			
// 			try {
// 				service.run(args);
// 			} catch (Exception e) {
// 				e.printStackTrace();
// 			}
			
// 			// Close context after CLI command finishes
// 			// Note: If the client spins up background threads (like listener), we might not want to exit immediately.
// 			// But for 'download' command which blocks, this is fine.
// 			System.exit(0);
// 		} else {
// 			System.out.println("Usage: java -jar java_bittorrent.jar <command> <args>");
// 		}
// 	}
// }

package bittorrent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.HexFormat;

@SpringBootApplication
public class BitTorrentApplication {
	public static final boolean DEBUG = true; 
	public static final HexFormat HEX_FORMAT = HexFormat.of();
    public static void main(String[] args) {
        SpringApplication.run(BitTorrentApplication.class, args);
    }
}
