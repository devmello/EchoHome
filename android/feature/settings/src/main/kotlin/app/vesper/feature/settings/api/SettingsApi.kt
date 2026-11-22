package app.vesper.feature.settings.api

import app.vesper.core.model.SecretStore
import app.vesper.core.model.Secrets
import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsSchema
import app.vesper.core.model.SettingsStore
import app.vesper.feature.settings.http.HttpRequest
import app.vesper.feature.settings.http.HttpResponse
import app.vesper.feature.settings.http.Lan
import app.vesper.feature.settings.pair.PairResult
import app.vesper.feature.settings.pair.PairedBrowser
import app.vesper.feature.settings.pair.Pairing
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The settings web app's routes.
 *
 * Public: the static page (it shows "Pair with Echo Show" until paired), `GET /api/hello`,
 * `POST /api/pair/start`, `POST /api/pair`. Everything else needs the pairing cookie.
 *
 *  - `GET /api/schema`: sections, specs and current values (secrets: set / not set only)
 *  - `PUT /api/settings` `{key: value, …}`: validated against each spec, all or nothing
 *  - `POST /api/action/<key>`: runs an ACTION, returns `{message}`
 *  - `GET /api/events`: SSE, `change` / `schema` / `pairs` events
 *  - `GET /api/pairs`, `DELETE /api/pairs/<id>`: paired browsers
 *  - `/api/alarms`: the alarm list ([AlarmsApi])
 *
 * Defences: LAN-only sockets (in the server), a local Host header (DNS rebinding), same-origin
 * Origin on writes, JSON-only bodies (forces a CORS preflight we never answer), a
 * SameSite=Lax HttpOnly cookie. Values are never logged.
 */
class SettingsApi(
    private val schema: SettingsSchema,
    private val settings: () -> SettingsStore,
    private val secrets: () -> SecretStore,
    private val effective: () -> Secrets,
    private val deviceName: () -> String,
    val pairing: Pairing,
    val events: EventHub,
    private val assets: Assets,
    /** Runs an ACTION's onAction (on the main thread on the Echo) and returns its message. */
    private val runAction: (SettingSpec) -> String?,
    /** The alarm list (`/api/alarms`), when an AlarmStore is available. */
    private val alarms: AlarmsApi? = null,
) {
    fun handle(req: HttpRequest): HttpResponse {
        if (!Lan.isLocalHost(req.header("host"))) return HttpResponse.error(403, "Open the Echo by its IP address or .local name")
        val p = req.path
        if (!p.startsWith("/api/")) return static(req)

        val write = req.method != "GET" && req.method != "HEAD"
        if (write) {
            val origin = req.header("origin")
            if (origin != null && origin != "null" && origin.substringAfter("://") != req.header("host")) {
                return HttpResponse.error(403, "Cross-site request")
            }
            if (req.body.isNotEmpty() && req.header("content-type")?.startsWith("application/json") != true) {
                return HttpResponse.error(415, "JSON only")
            }
        }
        val me = pairing.authenticate(req.cookies[Pairing.COOKIE])

        when {
            p == "/api/hello" && req.method == "GET" ->
                return ok(JSONObject().put("name", deviceName()).put("paired", me != null))
            p == "/api/pair/start" && req.method == "POST" -> return pairStart(req)
            p == "/api/pair" && req.method == "POST" -> return pairSubmit(req)
        }
        if (me == null) return HttpResponse.error(401, "Not paired")

        return when {
            p == "/api/schema" && req.method == "GET" -> ok(schemaJson())
            p == "/api/settings" && (req.method == "PUT" || req.method == "POST") -> putSettings(req)
            p.startsWith("/api/action/") && req.method == "POST" -> action(p.removePrefix("/api/action/"))
            p == "/api/events" && req.method == "GET" -> HttpResponse(200, contentType = "text/event-stream; charset=utf-8",
                headers = listOf("Cache-Control" to "no-store", "X-Accel-Buffering" to "no"),
                stream = { out -> events.serve(out, me.tokenHash) })
            p == "/api/pairs" && req.method == "GET" -> ok(pairsJson(me))
            (p == "/api/alarms" || p.startsWith("/api/alarms/")) && alarms != null -> alarms.handle(req)
            p.startsWith("/api/pairs/") && req.method == "DELETE" -> forget(p.removePrefix("/api/pairs/"), me)
            p.startsWith("/api/") -> HttpResponse.error(404, "No such API")
            else -> HttpResponse.error(404, "Not found")
        }
    }

    fun schemaJson(): JSONObject = SchemaJson.schema(schema.sections.value, settings(), secrets(), effective(), deviceName())

    /** The `change` event for a key that changed on the Echo (or from a page), or null if not on the page. */
    fun changeJson(key: String): JSONObject? = SchemaJson.change(schema::spec, key, settings(), secrets(), effective())

    private fun putSettings(req: HttpRequest): HttpResponse {
        val body = try { JSONObject(req.bodyText()) } catch (e: JSONException) { return HttpResponse.error(400, "Expected a JSON object") }
        if (body.length() == 0) return HttpResponse.error(400, "Nothing to change")
        val writes = ArrayList<Write>()
        val keys = body.keys().asSequence().toList()
        for (k in keys) {
            val spec = schema.spec(k) ?: return HttpResponse.error(400, "Unknown setting: ${k.take(80)}")
            try { writes += Validator.validate(spec, body.opt(k)) } catch (e: ValidationException) {
                return HttpResponse.json(422, JSONObject().put("error", e.message).put("key", k).toString())
            }
        }
        Writer.apply(writes, settings(), secrets())
        val values = JSONObject()
        for (k in keys) changeJson(k)?.let { values.put(k, it) }
        return ok(JSONObject().put("ok", true).put("values", values))
    }

    private fun action(key: String): HttpResponse {
        val spec = schema.spec(key)
        if (spec == null || spec.type != SettingType.ACTION) return HttpResponse.error(404, "No such action")
        val msg = try { runAction(spec) } catch (e: Exception) { return HttpResponse.error(500, "${spec.title} failed") }
        return ok(JSONObject().put("ok", true).putOpt("message", msg))
    }

    private fun pairStart(req: HttpRequest): HttpResponse =
        when (val r = pairing.start(Pairing.browserName(req.header("user-agent")))) {
            is PairResult.Started -> ok(JSONObject().put("pairId", r.pairId).put("expiresIn", r.expiresInMs / 1000))
            is PairResult.Failed -> HttpResponse.error(r.status, r.message)
            is PairResult.Paired -> HttpResponse.error(500, "Unexpected")
        }

    private fun pairSubmit(req: HttpRequest): HttpResponse {
        val body = try { JSONObject(req.bodyText()) } catch (e: JSONException) { return HttpResponse.error(400, "Expected JSON") }
        val code = body.optString("code").filter { it.isDigit() }
        return when (val r = pairing.submit(body.optString("pairId"), code, Pairing.browserName(req.header("user-agent")))) {
            is PairResult.Paired -> ok(JSONObject().put("ok", true).put("id", r.browser.id))
                .with("Set-Cookie" to "${Pairing.COOKIE}=${r.token}; Path=/; Max-Age=315360000; HttpOnly; SameSite=Lax")
            is PairResult.Failed -> HttpResponse.json(r.status, JSONObject().put("error", r.message).putOpt("attemptsLeft", r.attemptsLeft).toString())
            is PairResult.Started -> HttpResponse.error(500, "Unexpected")
        }
    }

    fun pairsJson(me: PairedBrowser?): JSONObject {
        val arr = JSONArray()
        for (b in pairing.browsers.value.sortedByDescending { it.lastSeenAt }) {
            arr.put(JSONObject().put("id", b.id).put("name", b.name).put("createdAt", b.createdAt)
                .put("lastSeenAt", b.lastSeenAt).put("current", b.id == me?.id))
        }
        return JSONObject().put("browsers", arr)
    }

    private fun forget(id: String, me: PairedBrowser): HttpResponse {
        val target = pairing.browsers.value.firstOrNull { it.id == id } ?: return HttpResponse.error(404, "No such browser")
        pairing.forget(id)
        events.closeFor(target.tokenHash)
        val res = ok(JSONObject().put("ok", true).put("self", id == me.id))
        return if (id == me.id) res.with("Set-Cookie" to "${Pairing.COOKIE}=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax") else res
    }

    private fun static(req: HttpRequest): HttpResponse {
        if (req.method != "GET" && req.method != "HEAD") return HttpResponse.error(405, "GET only")
        val name = when (req.path) { "/", "/index.html" -> "index.html"; else -> req.path.removePrefix("/") }
        val a = assets.get(name) ?: return HttpResponse(404, "Not found".toByteArray(), "text/plain; charset=utf-8")
        val common = mutableListOf(
            "ETag" to a.etag,
            "Cache-Control" to "no-cache",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Vary" to "Accept-Encoding",
        )
        if (name.endsWith(".html")) {
            common += "Content-Security-Policy" to CSP
            common += "X-Frame-Options" to "DENY"
        }
        if (req.header("if-none-match") == a.etag) return HttpResponse(304, headers = common)
        val gzip = a.gzip != null && req.header("accept-encoding")?.contains("gzip") == true
        if (gzip) common += "Content-Encoding" to "gzip"
        return HttpResponse(200, if (gzip) a.gzip!! else a.bytes, a.mime, common)
    }

    private fun ok(o: JSONObject) = HttpResponse.json(200, o.toString())

    companion object {
        const val CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
            "connect-src 'self' https://geocoding-api.open-meteo.com; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
    }
}
