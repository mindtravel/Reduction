package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.host.ToolApprovalHost
import com.mindtravel.reduction.core.settings.AppSettings
import com.mindtravel.reduction.core.settings.SettingsGateway
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.data.HistoryRepository
import com.mindtravel.reduction.feature.article.ArticleBuilder
import com.mindtravel.reduction.feature.memo.MemoCard
import com.mindtravel.reduction.feature.memo.MemoView
import com.mindtravel.reduction.feature.memo.data.MemoStore
import com.mindtravel.reduction.feature.sync.DataSync
import com.mindtravel.reduction.feature.chat.model.ChatSession
import com.mindtravel.reduction.feature.chat.model.tools.AgentLedger
import com.mindtravel.reduction.feature.chat.model.tools.AgentRuns
import com.mindtravel.reduction.feature.chat.model.tools.ChatToolFactory
import com.mindtravel.reduction.feature.chat.ui.AgentWatch
import com.mindtravel.reduction.feature.chat.ui.ChatController
import com.mindtravel.reduction.feature.chat.ui.Conversation
import com.mindtravel.reduction.feature.voice.Cue
import com.mindtravel.reduction.feature.voice.EndWords
import com.mindtravel.reduction.feature.voice.ReplyStreamer
import com.mindtravel.reduction.feature.voice.SpeechController
import com.mindtravel.reduction.feature.voice.SpokenCues
import com.mindtravel.reduction.hardware.audio.SpeechOutput
import com.mindtravel.reduction.hardware.audio.WavPcm
import com.mindtravel.reduction.hardware.pc.PcGateway
import com.mindtravel.reduction.hardware.pc.PcInfo
import com.mindtravel.reduction.hardware.pc.PcProfile
import com.mindtravel.reduction.model.DeepSeekHttp
import com.mindtravel.reduction.model.ModelGraph
import com.mindtravel.reduction.model.chat.ChatMessage
import com.mindtravel.reduction.model.chat.ChatRequest
import com.mindtravel.reduction.model.chat.ChatStreamEvent
import com.mindtravel.reduction.model.chat.Role
import com.mindtravel.reduction.model.voice.TtsCache
import com.mindtravel.reduction.model.voice.TtsSynthesizer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Reduction on a computer. The conversation — history, prompt, tools, cards, sub agents,
 * speech — is the very code the phone runs; this file is only its host: a small web
 * server on this machine alone, and a browser page as the screen, the speaker and the
 * microphone.
 *
 *   page ──POST /bridge/<method>──▶ the calls the phone's page makes to its app
 *   page ◀──GET /events (stream)── the events the app sends its page, and speech as PCM
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 8780
    val home = File(System.getProperty("user.home"))
    val data = File(home, ".local/share/reduction").apply { mkdirs() }
    val desktop = Desktop(data, File(home, ".config/reduction/settings.json"))

    val server = try {
        HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    } catch (e: java.net.BindException) {
        System.err.println("端口 $port 已被占用。换一个端口启动，比如：./desktop.sh ${port + 1}")
        return
    }
    server.executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    server.createContext("/") { exchange ->
        val headers = exchange.requestHeaders
        if (!LocalOrigin.allows(port, headers.getFirst("Host"), headers.getFirst("Origin"))) {
            // Addressed to some other name: a page that had its name pointed here, not this computer's own.
            System.err.println("Refused ${exchange.requestMethod} ${exchange.requestURI.path}: Host ${headers.getFirst("Host")}, Origin ${headers.getFirst("Origin")}")
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
            return@createContext
        }
        runCatching { desktop.handle(exchange) }.onFailure {
            // Said in the log: a request that fails without a word leaves nothing to go on.
            System.err.println("${exchange.requestMethod} ${exchange.requestURI} failed: $it")
            exchange.close()
        }
    }
    server.start()
    println("Reduction 已启动：http://localhost:$port")
    println(if (desktop.usesSystemAudio) "声音和麦克风：这台电脑自己的设备" else "声音和麦克风：没找到系统音频工具，由打开页面的浏览器代劳（运行 desktop/install.sh 可装上）")
    println("数据在 ${data.path}，设置在 ${desktop.settingsFile.path}")
    Thread.currentThread().join()
}

/** Settings in a file only its owner can read: it holds the API key. */
class DesktopSettings(private val file: File) : SettingsGateway {
    @Synchronized
    override fun load(): AppSettings {
        val saved = runCatching { AppSettings.fromJson(file.readText()) }.getOrElse { AppSettings.fromJson("{}") }
        return saved.copy(
            apiKey = saved.apiKey.ifBlank { System.getenv("DEEPSEEK_API_KEY").orEmpty() },
            // Sub agents run on this very machine.
            pcHost = "localhost",
            pcUser = System.getProperty("user.name"),
            wakeWordEnabled = false
        )
    }

    @Synchronized
    fun save(settings: AppSettings) {
        file.parentFile.mkdirs()
        file.writeText(settings.toJson().toString())
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }
}

/** Runs commands on this machine, where the phone would reach its computer over SSH. */
class LocalShell : PcGateway {
    override fun exec(profile: PcProfile, command: String): String = run(command, null)
    override fun send(profile: PcProfile, command: String, input: ByteArray): String = run(command, input)

    private fun run(command: String, input: ByteArray?): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        process.outputStream.use { if (input != null) it.write(input) }
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    override fun connect(profile: PcProfile) = Unit
    override fun isConnected() = true
    override fun info(profile: PcProfile): PcInfo = throw UnsupportedOperationException()
    override fun watchUpdates(profile: PcProfile, manifestUrl: String, onLine: (String) -> Unit) = AutoCloseable { }
    override fun publicKey() = ""
    override fun close() = Unit
}

/** Everything the page is told, sent to every open page as a stream of JSON lines. */
class PageLink : EventSink {
    private val pages = CopyOnWriteArrayList<OutputStream>()

    /** Told of every event as well: there is more to the application than its page. */
    @Volatile
    var onEvent: (function: String, args: List<Any?>) -> Unit = { _, _ -> }

    override fun emit(function: String, vararg args: Any?) {
        runCatching { onEvent(function, args.toList()) }
        send(JSONObject().put("fn", function).put("args", JSONArray(args.map { it ?: JSONObject.NULL })))
    }

    /** Something for the page's own machinery rather than for the app's page code. */
    fun signal(kind: String, fields: JSONObject = JSONObject()) = send(fields.put("signal", kind))

    private fun send(message: JSONObject) {
        val line = "data: $message\n\n".toByteArray(Charsets.UTF_8)
        for (page in pages) {
            try {
                synchronized(page) {
                    page.write(line)
                    page.flush()
                }
            } catch (t: Throwable) {
                pages.remove(page)
            }
        }
    }

    /** Keeps [exchange] open as a page's event stream until the page goes away. */
    fun serve(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.responseBody
        pages += out
        try {
            while (out in pages) {
                Thread.sleep(15_000)
                synchronized(out) {
                    out.write(": still here\n\n".toByteArray())
                    out.flush()
                }
            }
        } catch (t: Throwable) {
            pages.remove(out)
        }
    }
}

/** Speech goes to the page as PCM; the page plays it and says when it has finished. */
class PageSpeech(private val page: PageLink, private val onMain: (() -> Unit) -> Unit) : SpeechOutput {
    private val sessions = AtomicLong()
    private val ending = ConcurrentHashMap<Long, () -> Unit>()

    @Volatile
    private var current = 0L

    @Volatile
    private var started = false
    private var onStart: () -> Unit = {}

    override fun start(onStart: () -> Unit, onDone: () -> Unit) {
        stop()
        current = sessions.incrementAndGet()
        started = false
        this.onStart = onStart
        ending[current] = onDone
    }

    override suspend fun send(pcm: ByteArray): Boolean {
        val session = current
        if (session == 0L) return false
        play(session, pcm)
        if (!started) {
            started = true
            onMain { if (session == current) onStart() }
        }
        return true
    }

    override fun finish() {
        page.signal("audioEnd", JSONObject().put("session", current))
    }

    override fun stop() {
        if (current != 0L) {
            ending.remove(current)
            page.signal("audioStop")
        }
        current = 0L
    }

    /** A sound on its own, such as a cue: played after whatever is already sounding. */
    fun play(session: Long, pcm: ByteArray) {
        page.signal("audio", JSONObject().put("session", session).put("pcm", Base64.getEncoder().encodeToString(pcm)))
    }

    /** The page has played everything of [session]. */
    fun played(session: Long) {
        val done = ending.remove(session) ?: return
        onMain {
            if (session == current) current = 0L
            done()
        }
    }
}

class Desktop(data: File, val settingsFile: File) : ToolApprovalHost {
    private val main = Executors.newSingleThreadExecutor { Thread(it, "reduction-main").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + main.asCoroutineDispatcher())

    private val page = PageLink()
    private val settings = DesktopSettings(settingsFile)
    private val cards = CardRepository(File(data, "cards"))
    /** To-dos, kept as the phone keeps them; a computer has no alarms, so the review card is the reminder. */
    private val memos = MemoStore(File(data, "memos.json"))
    /** REDUCTION_API_BASE points the models somewhere else, which is how this is tested without spending anything. */
    private val models = ModelGraph(System.getenv("REDUCTION_API_BASE")?.takeIf { it.isNotBlank() }?.let { DeepSeekHttp(baseUrl = it) } ?: DeepSeekHttp())
    private val ttsCache = TtsCache(File(data, "tts_cache"))
    private val synthesizer = TtsSynthesizer(models.audioGateway, ttsCache)
    private val onMain: (() -> Unit) -> Unit = { block -> scope.launch { block() } }

    /** The machine's own loudspeakers and microphone; null when they cannot be reached, and the page stands in. */
    private val audio = SystemAudio.find(data)
    val usesSystemAudio: Boolean get() = audio != null
    private val pageSpeech = PageSpeech(page, onMain)
    private val speech = SpeechController(
        settings, ReplyStreamer(synthesizer, audio?.Speech(onMain) ?: pageSpeech, scope), ttsCache, page
    )
    private val cues = SpokenCues(
        settingsRepository = settings,
        synthesizer = synthesizer,
        bundled = mapOf(
            Cue.OK to bundled("ack_done.wav"), Cue.RETRYING to bundled("cue_retrying.wav"),
            Cue.NO_NETWORK to bundled("cue_no_network.wav"), Cue.FAILED to bundled("cue_failed.wav"),
            Cue.NOT_HEARD to bundled("cue_not_heard.wav")
        ),
        sampleRate = SAMPLE_RATE,
        scope = scope
    )
    private val agentRuns = AgentRuns(File(data, "agent-runs.json"))
    /** Sets cards as articles: for the assistant's tool, and for the page's preview, which asks for the template in use. */
    private val articles = ArticleBuilder(
        asset = { resourceText(it) },
        assetBytes = { path -> javaClass.classLoader.getResourceAsStream(path)!!.use { it.readBytes() } },
        card = { cards.read(it) }
    )
    private val cardFiles = CardFiles(cards, File(data, "exports"))
    private val toolFactory = ChatToolFactory(
        searchGateway = models.searchGateway,
        pcGateway = LocalShell(),
        emitter = page,
        cards = cards,
        memos = memos,
        agentLauncherScript = { resourceText("harness/run-agent.sh") },
        sessionStatusScript = { resourceText("harness/session-status.py") },
        agentRuns = agentRuns,
        articles = articles,
        hostTools = { listOf(CardTool(cards, cardFiles, it)) },
        renderArticleScript = { resourceText("harness/render-article.sh") }
    )
    private val chat = ChatController(
        settingsRepository = settings,
        chatEngine = ChatSession(models.chatGateway),
        toolFactory = toolFactory,
        speech = speech,
        say = { cue -> audio?.play(cues.sound(cue)) ?: pageSpeech.play(0, cues.sound(cue)) },
        emitter = page,
        scope = scope
    )
    private val conversation = Conversation(
        settingsRepository = settings,
        history = HistoryRepository(File(data, "chat_history.json")),
        cards = cards,
        chat = chat,
        emitter = page,
        today = { LocalDate.now().toString() },
        place = "电脑"
    )

    /** What this computer shares with the user's phone; the phone comes to it. */
    private val sync = DataSync(cards, memos, conversation, hub = true)

    // ── the pet: the small figure on the desktop that stands for all this ────
    @Volatile private var petSpeaking = false
    @Volatile private var petListening = false
    @Volatile private var petLine = ""
    @Volatile private var petLineId = 0L

    @Volatile private var petKind = "say"

    /** [kind] is "say" for something to read, "dictation" for text the pet is to put on the clipboard. */
    private fun petSays(line: String, kind: String = "say") {
        petLine = line.trim()
        petKind = kind
        petLineId++
    }

    /** What the pet should look like now, and the last thing worth a speech bubble. */
    private fun petState(): String = JSONObject()
        .put("state", when {
            petListening -> "listening"
            petSpeaking -> "speaking"
            chat.isBusy() -> "thinking"
            else -> "idle"
        })
        .put("line", petLine).put("lineId", petLineId).put("kind", petKind)
        .put("ready", settings.load().apiKey.isNotBlank())
        .toString()

    /** Talking to the pet directly: hold the space bar, speak, let go. No page is needed. */
    private fun petTalk(action: String, dictate: Boolean = false) {
        val mic = audio ?: return petSays("这台电脑上用不了麦克风")
        when (action) {
            "start" -> {
                speech.stop(notify = false)
                petListening = mic.startRecording()
            }
            "cancel" -> {
                petListening = false
                mic.cancelRecording()
            }
            "stop" -> {
                petListening = false
                val wav = mic.stopRecording() ?: return petSays("没有录到声音")
                // Every step says where it is: letting go is always answered by something.
                petSays("识别中…")
                scope.launch(Dispatchers.IO) {
                    val key = settings.load().apiKey
                    val heard = runCatching { models.audioGateway.transcribe(key, wav) }
                    heard.onFailure { System.err.println("Pet: speech not recognised: $it") }
                    val text = heard.getOrDefault("").let { (EndWords.strip(it) ?: it).trim() }
                    when {
                        heard.isFailure -> petSays("识别失败：" + (heard.exceptionOrNull()?.message ?: "网络不通"))
                        text.isBlank() -> petSays("没听清，再说一遍？")
                        dictate -> {
                            petSays("整理中…")
                            val written = runCatching { tidy(key, text) }
                            written.onFailure { System.err.println("Pet: dictation not put in order: $it") }
                            // What was said is still worth having when it could not be put in order.
                            petSays(written.getOrNull()?.takeIf { it.isNotBlank() } ?: text, kind = "dictation")
                        }
                        else -> {
                            petSays("“$text”")
                            scope.launch { conversation.submit(text) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Dictation: what was said, as one would have written it. It is not part of the conversation — no
     * history, no cards, no tools — and nothing in it is answered.
     */
    private fun tidy(key: String, spoken: String): String {
        val written = StringBuilder()
        models.chatGateway.stream(
            ChatRequest(
                apiKey = key,
                model = settings.load().model,
                messages = listOf(ChatMessage(Role.SYSTEM, DICTATION), ChatMessage(Role.USER, spoken)),
                tools = emptyList(),
                temperature = 0.2
            )
        ) { event ->
            when (event) {
                is ChatStreamEvent.Content -> written.append(event.text)
                is ChatStreamEvent.Failure -> throw IllegalStateException(event.message)
                else -> Unit
            }
        }
        return written.toString().trim().replace(Regex("\\s*\\n+\\s*"), " ")
    }

    private val approvals = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val approvalIds = AtomicLong()

    init {
        val writeLedger = { cards.writeProgramCard(AgentLedger.CARD, AgentLedger.render(agentRuns.all()), AgentLedger.PURPOSE) }
        agentRuns.onChange = { writeLedger() }
        writeLedger()
        // The review card for to-dos, written from the store as the phone writes it.
        val writeMemos = { cards.writeProgramCard(MemoCard.CARD, MemoCard.render(memos), MemoCard.PURPOSE) }
        memos.onChange = {
            writeMemos()
            page.emit("onMemosChanged", "")
        }
        writeMemos()
        cards.onChange = { page.emit("onCardsChanged", "") }
        page.onEvent = { function, args ->
            when (function) {
                "onTtsStart" -> petSpeaking = true
                "onTtsDone", "onTtsStopped", "onTtsError", "onChatAborted" -> petSpeaking = false
                "onChatDone" -> petSays(args.getOrNull(1)?.toString().orEmpty())
                "onChatError" -> {
                    petSpeaking = false
                    petSays(args.getOrNull(1)?.toString().orEmpty())
                }
                "onToolStarted" -> petSays(args.getOrNull(2)?.toString().orEmpty() + "…")
            }
        }
        AgentWatch(
            finished = {
                toolFactory.endedRuns(settings.load()).map { ended ->
                    AgentWatch.Report(
                        "任务 ${ended.number} 结束了",
                        "【任务 ${ended.number} 已结束】下面是它交回的结果，请向用户汇报。\n\n${ended.report}",
                        ended.images
                    )
                }
            },
            quiet = { !chat.isBusy() },
            deliver = { report -> conversation.submit(report.text, report.title, report.images) },
            scope = scope
        ).start()
        cues.prepare()
    }

    override fun requestToolApproval(title: String, message: String, callback: (Boolean) -> Unit) {
        val id = approvalIds.incrementAndGet().toString()
        approvals[id] = callback
        page.signal("approval", JSONObject().put("id", id).put("title", title).put("message", message))
    }

    // ── the web server ──────────────────────────────────────────────────────

    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        if (exchange.requestMethod == "GET" && path == "/events") return page.serve(exchange)
        if (exchange.requestMethod == "GET" && path == "/pet") return reply(exchange, 200, petState())
        if (exchange.requestMethod == "GET") return serveFile(exchange, path)
        // Only this page may give orders: a request from any other site cannot carry this header.
        if (exchange.requestHeaders.getFirst("X-Reduction") != "1") return reply(exchange, 403, "{}")
        val body = exchange.requestBody.readBytes()
        val query = exchange.requestURI.query.orEmpty()
        when {
            path.startsWith("/bridge/") -> {
                val args = runCatching { JSONArray(String(body, Charsets.UTF_8)) }.getOrDefault(JSONArray())
                val result = runCatching { bridge(path.removePrefix("/bridge/"), args) }
                    .onFailure { Logs.add("bridge ${path.removePrefix("/bridge/")} failed: ${it.message}") }.getOrNull()
                reply(exchange, 200, JSONObject().put("result", result ?: JSONObject.NULL).toString())
            }
            path == "/pet/talk" -> {
                val asked = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
                petTalk(asked["action"].orEmpty(), dictate = asked["mode"] == "dictate")
                reply(exchange, 200, petState())
            }
            path == "/sync" -> {
                // The phone's half of an exchange: its state is merged into this computer's, and the answer
                // is what this computer holds afterwards, with the pictures and sound the phone lacks.
                val theirs = JSONObject(String(body, Charsets.UTF_8))
                val changed = sync.merge(theirs)
                if (changed) System.err.println("Sync: took in changes from the phone")
                reply(exchange, 200, sync.export().put("files", sync.filesFor(theirs)).toString())
            }
            path == "/audio-done" -> {
                pageSpeech.played(query.removePrefix("session=").toLongOrNull() ?: 0)
                reply(exchange, 200, "{}")
            }
            path == "/approve" -> {
                val answer = JSONObject(String(body, Charsets.UTF_8))
                approvals.remove(answer.optString("id"))?.invoke(answer.optBoolean("allowed"))
                reply(exchange, 200, "{}")
            }
            path == "/asr" -> {
                reply(exchange, 200, "{}")
                transcribe(query.removePrefix("id="), body)
            }
            else -> reply(exchange, 404, "{}")
        }
    }

    /** The calls the phone's page makes to its app, answered here. */
    private fun bridge(method: String, args: JSONArray): Any? = when (method) {
        "loadSettings" -> settings.load().toJson().toString()
        "saveSettings" -> {
            settings.save(AppSettings.fromJson(args.getString(0)))
            cues.prepare()
            true
        }
        "loadHistory" -> conversation.historyJson()
        "clearHistory" -> conversation.clear()
        "submitText" -> conversation.submit(args.getString(0))
        "submitPictures" -> conversation.submitWithPictures(args.getString(0), args.getString(1))
        "listCards" -> cards.directoryJson()
        "readCard" -> cards.read(args.getString(0)).orEmpty()
    private fun revealCardPath(path: String): Boolean {
        if (path.any { it.code < 32 } || path.startsWith("//")) return false
        val local = when {
            path.startsWith("/") -> File(path)
            Regex("""^[A-Za-z]:[\\/].*""").matches(path) ->
                File("/mnt/" + path[0].lowercaseChar() + "/" + path.substring(3).replace('\\', '/'))
            else -> return false
        }
        if (!local.exists()) return false
        val convert = ProcessBuilder("wslpath", "-w", local.canonicalPath).start()
        val winPath = convert.inputStream.bufferedReader().readText().trimEnd()
        if (convert.waitFor() != 0 || winPath.isBlank()) return false
        ProcessBuilder("/mnt/c/Windows/explorer.exe", "/select,", winPath).start()
        return true
    }

        "cardPurpose" -> cards.purpose(args.getString(0))
        "saveCard" -> cards.save(args.getString(0), args.getString(2), args.getString(1))
        "deleteCard" -> cards.delete(args.getString(0))
        "renameCard" -> cards.rename(args.getString(0), args.getString(1))
        "lockCard" -> cards.setLocked(args.getString(0), args.getBoolean(1))
        "saveCardMedia" -> runCatching { cards.saveMedia(Base64.getDecoder().decode(args.getString(0)), args.getString(1)) }.getOrNull().orEmpty()
        "articleTemplate" -> JSONObject().put("template", articles.template()).put("style", articles.style())
            .put("problem", articles.templateProblem() ?: JSONObject.NULL).toString()
        "saveCardPage" -> runCatching { cards.savePage(args.getString(0), args.getInt(1), Base64.getDecoder().decode(args.getString(2))) }.getOrDefault(false)
        "cardPages" -> cards.pages(args.getString(0)).size
        "speak" -> speech.speak(args.getString(0), args.getString(1))
        "cardDraftSnapshot" -> cardFiles.draft(args)
        "revealCardPath" -> revealCardPath(args.getString(0))
        "stopSpeaking" -> speech.stop(notify = true)
        "clearAudioCache" -> speech.clearCache()
        "getAppInfo" -> "Reduction 电脑端 · 朗读缓存 %.1f MB".format(speech.cacheSizeMb())
        "autosaveCard" -> cards.autosave(args.getString(0), args.getString(1), args.getString(2), args.getBoolean(3), args.getString(4), args.getString(5)).also { if (it == "saved") cardFiles.clearDraft(args.getString(0)) }
        // Sound and microphone belong to this machine when it has them; the page asks before using its own.
        "nativeAudio" -> audio != null
        "requestMicPermission" -> page.emit("onMicPermission", true)
        "startRecording" -> {
            val id = args.getString(0)
            if (audio?.startRecording() == true) page.emit("onRecordingStart", id) else page.emit("onAsrResult", id, false, "用不了麦克风")
        }
        "stopAndTranscribe" -> {
            val id = args.getString(0)
            val wav = audio?.stopRecording()
            if (wav == null) page.emit("onAsrResult", id, false, "没有录到声音，请再试一次") else scope.launch(Dispatchers.IO) { transcribe(id, wav) }
        }
        "cancelRecording" -> audio?.cancelRecording()
        // To-dos as the review card shows them. A computer has no alarms, so nothing here is degraded.
        "memoView" -> MemoView.json(memos, exact = true, notify = true)
        "setMemoDone" -> memos.setDone(args.getInt(0), args.getBoolean(1)) != null
        "pcState" -> "connected"
        "pcConnected" -> true
        "voiceState" -> """{"state":"IDLE"}"""
        "updateState" -> "idle"
        "updateVersion", "floatingCard", "pcPublicKey" -> ""
        "floatCard", "setWakeWordEnabled" -> false
        else -> null
    }

    /** A push-to-talk recording from the page: turned into words and handed back as the phone does. */
    private fun transcribe(id: String, wav: ByteArray) {
        val key = settings.load().apiKey
        if (key.isBlank()) return page.emit("onAsrResult", id, false, "请先在设置里填写 API Key")
        page.emit("onStatus", id, "识别中")
        try {
            val raw = models.audioGateway.transcribe(key, wav)
            val text = (EndWords.strip(raw) ?: raw).trim()
            if (text.isBlank()) page.emit("onAsrResult", id, false, "没有识别到内容，请重说") else page.emit("onAsrResult", id, true, text)
        } catch (t: Throwable) {
            page.emit("onAsrResult", id, false, t.message ?: "识别失败")
        }
    }

    private fun serveFile(exchange: HttpExchange, path: String) {
        if (path.startsWith("/card-media/")) {
            // Pictures and sound kept with the cards, by their id.
            val file = cards.heldFile(path.removePrefix("/card-media/")) ?: return reply(exchange, 404, "")
            exchange.responseHeaders.add("Content-Type", TYPES[file.extension] ?: "application/octet-stream")
            exchange.sendResponseHeaders(200, file.length())
            exchange.responseBody.use { out -> file.inputStream().use { it.copyTo(out) } }
            return
        }
        val name = if (path == "/") "index.html" else path.removePrefix("/")
        val bytes = if (name.contains("..")) null else javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
        if (bytes == null) return reply(exchange, 404, "")
        val content = if (name == "index.html") {
            // The phone's page, with the computer's stand-in for the app loaded ahead of it.
        if (path.startsWith("/card-media/file/")) {
            val (blob, meta) = cardFiles.attachedFile(path.substringAfterLast('/')) ?: return reply(exchange, 404, "")
            exchange.responseHeaders.add("Content-Security-Policy", "sandbox; default-src 'none'")
            exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
            exchange.responseHeaders.add("Content-Type", meta.getString("type"))
            exchange.responseHeaders.add("Content-Disposition", "attachment")
            exchange.sendResponseHeaders(200, blob.length())
            exchange.responseBody.use { out -> blob.inputStream().use { it.copyTo(out) } }
            return
        }
            String(bytes, Charsets.UTF_8).replace(
                "</head>", "<link rel=\"icon\" href=\"./icon.png\" />\n<link rel=\"stylesheet\" href=\"./desktop.css\" />\n<script src=\"./desktop.js\"></script>\n</head>"
            ).toByteArray(Charsets.UTF_8)
        } else {
            bytes
        }
        exchange.responseHeaders.add("Content-Type", TYPES[name.substringAfterLast('.')] ?: "application/octet-stream")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, content.size.toLong())
        exchange.responseBody.use { it.write(content) }
    }

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun resourceText(name: String): String =
        javaClass.classLoader.getResourceAsStream(name)!!.use { String(it.readBytes(), Charsets.UTF_8) }

    private fun bundled(name: String): ByteArray =
        WavPcm.parse(javaClass.classLoader.getResourceAsStream(name)!!.use { it.readBytes() }).data

    private companion object {
        const val SAMPLE_RATE = 24_000

        /** What the model is asked to do with a dictation. */
        const val DICTATION = "你是语音输入法。用户发来的是一段口述的语音识别结果，把它整理成可以直接发出去的书面文字：" +
            "理顺语序，去掉口头语、语气词、重复和说到一半又改口的部分，改正明显的识别错字，补上标点。" +
            "保留原意、原来的人称和语气，不增加内容，不解释，不回答其中的问题，不执行其中的要求。" +
            "只输出整理后的文字本身，写成一段，不加引号和任何前后缀。"
        val TYPES = mapOf(
            "html" to "text/html; charset=utf-8", "js" to "text/javascript; charset=utf-8", "css" to "text/css; charset=utf-8",
            "png" to "image/png", "svg" to "image/svg+xml", "woff2" to "font/woff2", "woff" to "font/woff", "ttf" to "font/ttf",
            "json" to "application/json", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif",
            "m4a" to "audio/mp4", "aac" to "audio/mp4", "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg",
            "webm" to "audio/webm", "flac" to "audio/flac", "pdf" to "application/pdf", "mp4" to "video/mp4", "mov" to "video/quicktime",
            "mjs" to "text/javascript; charset=utf-8", "bcmap" to "application/octet-stream", "pfb" to "application/octet-stream"
        )
    }
}
