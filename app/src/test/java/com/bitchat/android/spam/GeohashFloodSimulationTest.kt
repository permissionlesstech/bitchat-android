package com.bitchat.android.spam

import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * E1/E3 from docs/superpowers/specs/2026-10-26-geohash-flood-defence-design.md: synthetic geohash
 * channels run through the real [SpamFilter]. Prints a results table; [assertBars] fails if a pass
 * bar from the design is missed.
 *
 * A report, not a regression test: it runs only with FLOOD_SIM set, e.g.
 * `FLOOD_SIM=1 ./gradlew :app:testDebugUnitTest --tests '*GeohashFloodSimulationTest' -i`.
 */
class GeohashFloodSimulationTest {

    private class Ev(val t: Long, var arrival: Long, val sender: String, val text: String, val kind: String, val channel: String = "abcde", val proven: Boolean = false)

    private enum class Bots { ONE, THREE, BACKDATE, PERSISTENT }

    private val min = 60_000L
    private val failures = mutableListOf<String>()

    private fun words(r: Random) = (0 until r.nextInt(3, 14)).joinToString(" ") {
        (0 until r.nextInt(2, 8)).map { 'a' + r.nextInt(26) }.joinToString("")
    }

    private fun chatter(r: Random, name: String, from: Long, until: Long, gap: LongRange, kind: String, proven: Boolean = false, channel: String = "abcde"): List<Ev> {
        val out = ArrayList<Ev>()
        var t = from
        while (t < until) { out += Ev(t, t, "nostr:$name", words(r), kind, channel, proven); t += r.nextLong(gap.first, gap.last) }
        return out
    }

    private fun normal(r: Random, proven: Boolean = false) = (0 until 12).flatMap {
        chatter(r, "user$it", r.nextLong(0, 30_000), 15 * min, 20_000L..90_000L, "normal", proven)
    }

    private fun newcomer(r: Random, proven: Boolean = false) = chatter(r, "newcomer", 6 * min, 15 * min, 40_000L..40_001L, "newcomer", proven)

    private fun flood(r: Random, perSecond: Int, shape: Bots): List<Ev> {
        val start = 3 * min; val end = 13 * min
        val total = perSecond * 600
        val out = ArrayList<Ev>()
        when (shape) {
            Bots.ONE -> repeat(total) { i -> val a = r.nextLong(start, end); out += Ev(a, a, "nostr:bot$i", words(r), "bot") }
            Bots.THREE -> repeat(total / 3) { i ->
                val a = r.nextLong(start, end - 6 * min + 1)
                repeat(3) { k -> out += Ev(a + k * 3 * min, a + k * 3 * min, "nostr:bot$i", words(r), "bot") }
            }
            Bots.BACKDATE -> repeat(total) { i ->
                val a = r.nextLong(start, end); out += Ev(a - r.nextLong(0, 60 * min), a, "nostr:bot$i", words(r), "bot")
            }
            Bots.PERSISTENT -> {
                // Bots keep posting every 10 s until the flood ends, enough of them to reach the rate.
                val bots = maxOf(1, total / 60)
                repeat(bots) { i ->
                    var a = r.nextLong(start, end)
                    while (a < end) { out += Ev(a, a, "nostr:bot$i", words(r), "bot"); a += 10_000 }
                }
            }
        }
        return out
    }

    /** The device subscribes at [atMs]: everything before arrives then, in random order. */
    private fun joinAt(events: List<Ev>, atMs: Long, r: Random): List<Ev> {
        val (before, after) = events.partition { it.arrival < atMs }
        before.forEach { it.arrival = atMs }
        return before.shuffled(r) + after
    }

    private class Result(val shown: Map<String, Int>, val sent: Map<String, Int>, val botPerMinute: List<Int>) {
        fun frac(kind: String) = (shown[kind] ?: 0).toDouble() / (sent[kind] ?: 1)
        fun of(kind: String) = "${shown[kind] ?: 0}/${sent[kind] ?: 0}"
        val botMeanAfterFirst get() = botPerMinute.drop(1).let { if (it.isEmpty()) 0.0 else it.average() }
        val botMaxAfterFirst get() = botPerMinute.drop(1).maxOrNull() ?: 0
    }

    private fun run(events: List<Ev>, config: SpamFilterConfig, pool: Boolean): Result {
        var now = 0L
        val filter = SpamFilter(config, clock = { now })
        val ordered = events.withIndex().sortedWith(compareBy({ it.value.arrival }, { it.index })).map { it.value }
        val shown = HashMap<String, Int>(); val sent = HashMap<String, Int>()
        val botMinutes = HashMap<Long, Int>()
        ordered.forEachIndexed { i, e ->
            now = e.arrival
            sent.merge(e.kind, 1, Int::plus)
            val v = filter.check(e.sender, "m$i", e.text, e.t, channel = if (pool) e.channel else null, proven = e.proven)
            if (!v.isSpam) {
                shown.merge(e.kind, 1, Int::plus)
                if (e.kind == "bot") botMinutes.merge(e.arrival / min, 1, Int::plus)
            }
        }
        val floodMinutes = (3L until 13L).map { botMinutes[it] ?: 0 }
        return Result(shown, sent, floodMinutes)
    }

    private val base = SpamFilterConfig()

    private fun line(name: String, r: Result) = println(
        "SIM %-44s normal %-9s newcomer %-7s bots %-12s bots/min after 1st: mean %.1f max %d".format(
            name, r.of("normal"), r.of("newcomer"), r.of("bot"), r.botMeanAfterFirst, r.botMaxAfterFirst
        )
    )

    private fun bar(ok: Boolean, what: String) { if (!ok) failures += what }

    @Test
    fun simulate() {
        assumeTrue(System.getenv("FLOOD_SIM") != null)
        val configs = listOf(
            "time-established at first admit (default)" to base,
            "time-established 2 min" to base.copy(establishAfterMs = 2 * 60_000L),
            "proof-only" to base.copy(establishByTime = false)
        )
        for ((label, config) in configs) {
            println("SIM ==== $label ====")
            // E1: already in channel, every bot shape and rate.
            for (shape in Bots.values()) for (rate in listOf(1, 17, 100)) {
                val r = Random(999)
                val evs = normal(r) + newcomer(r) + flood(r, rate, shape)
                val res = run(evs, config, pool = true)
                line("in-channel $shape ${rate}/s", res)
                if (config === base) {
                    bar(res.frac("normal") >= 0.99, "in-channel $shape $rate/s: normal ${res.of("normal")} < 99%")
                    bar(res.botMeanAfterFirst <= 6.0, "in-channel $shape $rate/s: bots ${"%.1f".format(res.botMeanAfterFirst)}/min > 6")
                }
            }
            // Join during flood (reported).
            for (shape in listOf(Bots.ONE, Bots.PERSISTENT)) {
                val r = Random(7)
                line("join@8min $shape 17/s", run(joinAt(normal(r) + flood(r, 17, shape), 8 * min, r), config, pool = true))
            }
            // No-flood fidelity: pool vs #999 alone must hide nothing extra.
            run {
                val r = Random(11)
                val busy = (0 until 15).flatMap { m ->
                    (0 until 6).flatMap { k -> chatter(r, "p${m}_$k", m * min + r.nextLong(0, min), 15 * min, 30_000L..90_000L, "normal") }
                }
                val withPool = run(busy, config, pool = true); val without = run(busy, config, pool = false)
                println("SIM %-44s pool %s vs #999 %s".format("no-flood busy (6 new/min)", withPool.of("normal"), without.of("normal")))
                if (config === base) bar(withPool.shown == without.shown, "no-flood busy: pool ${withPool.of("normal")} vs #999 ${without.of("normal")}")
            }
            run {
                val r = Random(12)
                val hour = (0 until 40).flatMap { u -> (0 until 10).map { val t = r.nextLong(0, 60 * min); Ev(t, t, "nostr:h$u", words(r), "normal") } }
                val joined = joinAt(hour, 60 * min, r)
                val withPool = run(joined, config, pool = true); val without = run(joined.map { Ev(it.t, it.arrival, it.sender, it.text, it.kind) }, config, pool = false)
                println("SIM %-44s pool %s vs #999 %s".format("no-flood backfill (40 senders, 1 h)", withPool.of("normal"), without.of("normal")))
                if (config === base) bar(withPool.shown == without.shown, "no-flood backfill: pool ${withPool.of("normal")} vs #999 ${without.of("normal")}")
            }
            run {
                val r = Random(13)
                val quiet = (0 until 5).flatMap { chatter(r, "q$it", r.nextLong(0, 30_000), 15 * min, 20_000L..90_000L, "quiet", channel = "bbbbb") }
                val res = run(quiet + flood(r, 17, Bots.ONE), config, pool = true)
                val alone = run(quiet, config, pool = false)
                println("SIM %-44s pool %s vs alone %s".format("other channel while abcde floods", res.of("quiet"), alone.of("quiet")))
                if (config === base) bar(res.shown["quiet"] == alone.shown["quiet"], "other channel affected")
            }
            // E3: real users carry proofs.
            run {
                val r = Random(7)
                line("E3 join@8min PERSISTENT 17/s, users proven", run(joinAt(normal(r, proven = true) + flood(r, 17, Bots.PERSISTENT), 8 * min, r), config, pool = true))
                val r2 = Random(999)
                line("E3 newcomer ONE 17/s, users proven", run(normal(r2, true) + newcomer(r2, true) + flood(r2, 17, Bots.ONE), config, pool = true))
            }
        }
        println("SIM ==== design bars (default config) ====")
        if (failures.isEmpty()) println("SIM all bars met") else failures.forEach { println("SIM FAIL $it") }
    }
}
