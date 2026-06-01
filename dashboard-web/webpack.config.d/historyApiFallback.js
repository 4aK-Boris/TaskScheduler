// SPA fallback for the dev server: serve index.html for client-side routes (e.g. /jobs/{id})
// so a reload or a shared deep link doesn't 404. Mirrors the standalone-runner's Ktor
// singlePageApplication in production. Asset requests (.wasm/.js) still resolve normally —
// historyApiFallback only rewrites navigation requests that would otherwise 404.
config.devServer = config.devServer || {};
config.devServer.historyApiFallback = true;
