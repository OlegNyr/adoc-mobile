package ru.example.adoc

// tag::render[]
suspend fun render(source: String): String {
    val json = Json.encodeToString(source)
    return engine.evaluate("Asciidoctor.convert($json)")
}
// end::render[]

object Engine {
    val instance: JsEngine by lazy { JsEngine.create() }
}
