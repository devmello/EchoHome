package app.vesper.feature.settings

import app.vesper.core.model.InMemorySecretStore
import app.vesper.core.model.Secrets
import app.vesper.core.model.fake.FakeAlarmStore
import app.vesper.feature.settings.http.HttpResponse
import app.vesper.feature.settings.http.HttpServer
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Not a test: serves the web app with sample data (one setting of every type, two alarms) on
 * 127.0.0.1 so the page can be previewed and screenshotted on the Mac. Opt in:
 *
 *     SETTINGS_DEVSERVER=8099 ./gradlew :feature:settings:testDebugUnitTest --tests '*DevServer*'
 *
 * `/dev-login` pairs the browser without a code; `SETTINGS_DEVSERVER_SECONDS` (default 600) ends it.
 */
class DevServer {
    @Test fun serve() {
        val port = System.getenv("SETTINGS_DEVSERVER")?.toIntOrNull()
        assumeTrue("set SETTINGS_DEVSERVER=<port> to run", port != null)
        val settings = FakeSettingsStore().apply {
            putString("weather.location", "37.6819,-121.768")
            putString("weather.location.place", "Livermore, California")
            putBoolean("clock.seconds", true)
            putString("clock.color.digital", "#FF9F0A")
        }
        val secrets = InMemorySecretStore().apply { set(Secrets.ANTHROPIC_API_KEY, "sk-ant-sample") }
        val clock = longArrayOf(1_000_000L)
        val alarms = FakeAlarmStore(demo = true)
        val world = TestWorld(secrets, alarms).also { w ->
            w.playSomething()
            w.timers.start(8 * 60_000L, "Pasta")
            w.timers.start(25 * 60_000L)
        }
        val api = newApi(settings, secrets, clock, builtIn = Secrets(immichUrl = "http://immich.local:2283"),
            alarms = alarms, world = world)
        val server = HttpServer({ r ->
            if (r.path == "/dev-login") {
                clock[0] += 10_000
                val token = pair(api)
                api.pairing.dismissPrompt()
                HttpResponse(302, headers = listOf("Location" to "/", "Set-Cookie" to "vesper_session=$token; Path=/; HttpOnly; SameSite=Lax"))
            } else if (r.path == "/dev-slow") {
                // Holds the frame page's load event, so headless Chrome screenshots after the scroll.
                Thread.sleep(r.query["ms"]?.toLongOrNull() ?: 3500)
                HttpResponse(200, "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'/>".toByteArray(), "image/svg+xml")
            } else if (r.path == "/dev-frame") {
                // Headless Chrome can't make a window narrower than ~500 px: frame the page at phone width.
                val w = r.query["w"] ?: "390"
                val h = r.query["h"] ?: "844"
                val src = r.query["src"] ?: "/"
                val y = r.query["y"]?.toIntOrNull() ?: 0
                val theme = r.query["theme"]
                // Same origin as the page: set its theme / advanced switch first, then click and scroll.
                val script = "<script>localStorage.setItem('theme','${theme ?: "dark"}');" +
                    "localStorage.setItem('advanced','${r.query["advanced"] ?: "0"}');" +
                    "const f=document.querySelector('iframe');f.onload=()=>setTimeout(()=>{" +
                    (r.query["click"]?.let { "f.contentDocument.querySelector('$it')?.click();" } ?: "") +
                    "setTimeout(()=>f.contentWindow.scrollTo(0,$y),300)},2200);f.src='$src'</script><img src=\"/dev-slow\" style=\"position:absolute;left:-9px\">"
                val res = HttpResponse(200, ("<!doctype html><body style=\"margin:0;background:#222\">" +
                    "<iframe style=\"border:0;width:${w}px;height:${h}px;display:block\"></iframe>$script").toByteArray(), "text/html")
                if (r.query["login"] != null) {
                    clock[0] += 10_000
                    val token = pair(api)
                    api.pairing.dismissPrompt()
                    res.with("Set-Cookie" to "vesper_session=$token; Path=/; HttpOnly; SameSite=Lax")
                } else res
            } else {
                // The real page forbids framing; allow it here for the frame above.
                val res = api.handle(r)
                HttpResponse(res.status, res.body, res.contentType,
                    res.headers.filterNot { it.first == "X-Frame-Options" || it.first == "Content-Security-Policy" }, res.stream)
            }
        }, ports = listOf(port!!))
        server.start()
        println("settings dev server on http://127.0.0.1:$port/ (pair: /dev-login)")
        Thread.sleep((System.getenv("SETTINGS_DEVSERVER_SECONDS")?.toLongOrNull() ?: 600) * 1000)
        server.stop()
    }
}
