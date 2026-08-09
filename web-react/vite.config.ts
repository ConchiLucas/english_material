import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  const backendTarget = process.env.VITE_BACKEND_TARGET || env.VITE_BACKEND_TARGET || 'http://127.0.0.1:18744';

  return {
    plugins: [react()],
    server: {
      port: 19638,
      host: '0.0.0.0',
      proxy: {
        '/api': {
          target: backendTarget,
          changeOrigin: true,
        },
      },
    },
  };
});
