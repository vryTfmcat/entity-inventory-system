package com.obsidiancodx.entityinventory.data

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

data class MarkdownDocument(
    val frontmatter: LinkedHashMap<String, Any?>,
    val body: String
) {
    fun render(): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
            indicatorIndent = 0
            width = 120
        }
        val yaml = Yaml(options).dump(frontmatter).trimEnd()
        return "---\n$yaml\n---\n${body.trimStart()}"
    }

    companion object {
        private val yaml = Yaml()

        fun parse(raw: String): MarkdownDocument {
            val normalized = raw.replace("\r\n", "\n")
            require(normalized.startsWith("---\n")) { "Markdown 缺少 YAML frontmatter" }
            val end = normalized.indexOf("\n---\n", startIndex = 4)
            require(end >= 0) { "Markdown frontmatter 未闭合" }
            val header = normalized.substring(4, end)
            val parsed = yaml.load<Any?>(header)
            val map = linkedMapOf<String, Any?>()
            if (parsed is Map<*, *>) parsed.forEach { (key, value) -> map[key.toString()] = value }
            return MarkdownDocument(map, normalized.substring(end + 5))
        }
    }
}

internal fun Map<String, Any?>.string(key: String): String = this[key]?.toString().orEmpty()
internal fun Map<String, Any?>.int(key: String, fallback: Int = 0): Int =
    (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: fallback
internal fun Map<String, Any?>.doubleOrNull(key: String): Double? =
    (this[key] as? Number)?.toDouble() ?: this[key]?.toString()?.toDoubleOrNull()
internal fun Map<String, Any?>.stringSet(key: String): Set<String> = when (val value = this[key]) {
    is Iterable<*> -> value.mapNotNull { it?.toString() }.toSet()
    is String -> value.removePrefix("[").removeSuffix("]")
        .split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }.toSet()
    else -> emptySet()
}
