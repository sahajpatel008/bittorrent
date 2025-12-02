import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  // Get port from environment variable, default to 5173
  const port = parseInt(process.env.PORT || process.env.VITE_PORT || "5173");
  
  // Get backend port from environment variable for proxy target
  // VITE_BACKEND_PORT should be the backend server port (e.g., 8081, 8082, etc.)
  const backendPort = process.env.VITE_BACKEND_PORT || "8081";
  const proxyTarget = `http://localhost:${backendPort}`;
  
  return {
    plugins: [react()],
    server: {
      port: port,
      proxy: {
        "/api": {
          target: proxyTarget,
          changeOrigin: true
        }
      }
    }
  };
});
