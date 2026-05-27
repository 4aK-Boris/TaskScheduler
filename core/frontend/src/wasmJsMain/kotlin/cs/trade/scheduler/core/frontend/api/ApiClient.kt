package cs.trade.scheduler.core.frontend.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Shared Ktor HTTP client for the dashboard. Same-origin in prod, webpack-proxied in dev
 * (see DESIGN.md section 15.6).
 *
 * Json config mirrors DESIGN.md section 22.9 — `ignoreUnknownKeys=true` lets old clients
 * survive new server-side fields.
 */
public object ApiClient {

    public val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        classDiscriminator = "_type"
    }

    public val http: HttpClient = HttpClient(Js) {
        install(ContentNegotiation) {
            json(json)
        }
        install(WebSockets)
    }
}
