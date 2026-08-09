import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig(function (_a) {
    var mode = _a.mode;
    var env = loadEnv(mode, '.', '');
    var backendTarget = process.env.VITE_BACKEND_TARGET || env.VITE_BACKEND_TARGET || 'http://127.0.0.1:18744';
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
