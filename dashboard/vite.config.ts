import { defineConfig } from 'vite';

// In production Firebase Hosting forwards /api/** to Cloud Run; locally, to uvicorn.
export default defineConfig({
  server: { proxy: { '/api': 'http://localhost:8080' } },
});
