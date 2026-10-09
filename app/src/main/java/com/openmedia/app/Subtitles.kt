package com.openmedia.app

data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** Parses .srt, .vtt and .ass/.ssa into plain-text cues. */
object SubtitleParser {
    private val TIME = Regex(
        "(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})"
    )
    private val TAGS = Regex("<[^>]*>")
    private val BRACES = Regex("\\{[^}]*\\}")
    private val SPACES = Regex("\\s+")

    fun parse(raw: String, name: String): List<Cue> {
        val t = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val isAss = name.endsWith(".ass", true) || name.endsWith(".ssa", true) || t.contains("[Events]")
        val list = if (isAss) ass(t) else srtVtt(t)
        return list.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }
    }

    private fun ms(h: String, m: String, s: String, f: String): Long {
        val hh = if (h.isEmpty()) 0L else h.toLong()
        return ((hh * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + f.padEnd(3, '0').take(3).toLong()
    }

    private fun clean(s: String): String = s.replace(TAGS, "").replace(BRACES, "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace(SPACES, " ").trim()

    private fun srtVtt(t: String): List<Cue> {
        val out = ArrayList<Cue>()
        for (block in t.split(Regex("\n{2,}"))) {
            val lines = block.split('\n')
            val idx = lines.indexOfFirst { it.contains("-->") }
            if (idx < 0) continue
            val g = (TIME.find(lines[idx]) ?: continue).groupValues
            val text = clean(lines.drop(idx + 1).joinToString(" "))
            out += Cue(ms(g[1], g[2], g[3], g[4]), ms(g[5], g[6], g[7], g[8]), text)
        }
        return out
    }

    private fun ass(t: String): List<Cue> {
        val out = ArrayList<Cue>()
        var inEvents = false
        var iS = 1
        var iE = 2
        var iT = 9
        var n = 10
        for (line in t.split('\n')) {
            val l = line.trim()
            if (l.startsWith("[")) {
                inEvents = l.equals("[Events]", true)
                continue
            }
            if (!inEvents) continue
            if (l.startsWith("Format:", true)) {
                val cols = l.substring(7).split(',').map { it.trim().lowercase() }
                n = cols.size
                iS = cols.indexOf("start")
                iE = cols.indexOf("end")
                iT = cols.indexOf("text")
            } else if (l.startsWith("Dialogue:", true)) {
                val p = l.substring(9).trim().split(',', limit = n)
                if (p.size < n || iS < 0 || iE < 0 || iT < 0) continue
                val text = p[iT].replace("\\N", " ").replace("\\n", " ").replace("\\h", " ")
                out += Cue(assTime(p[iS]), assTime(p[iE]), clean(text))
            }
        }
        return out
    }

    private fun assTime(s: String): Long {
        val a = s.trim().split(':')
        if (a.size != 3) return 0L
        val sec = a[2].split('.')
        return ms(a[0], a[1], sec[0], sec.getOrElse(1) { "0" })
    }
}
