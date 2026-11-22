package com.nexus.cli

import kotlinx.coroutines.CancellationException

class ShellEngine(
    private val ctx: ShellContext,
    private val registry: CommandRegistry = CommandRegistry()
) {

    suspend fun execute(raw: String): ShellResult {
        val tokens = tokenize(raw)
        if (tokens.isEmpty()) return ShellResult()

        val expanded = expandAlias(tokens)
        if (expanded.isEmpty()) return ShellResult()

        val name = expanded.first()
        val args = expanded.drop(1)

        val command = registry.find(name)
            ?: return ShellResult.error("nexus: $name: command not found. Ketik 'help'.")

        return try {
            command.run(ctx, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShellResult.error("$name: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun expandAlias(tokens: List<String>): List<String> {
        val replacement = ctx.fs.aliasOf(tokens.first()) ?: return tokens
        return tokenize(replacement) + tokens.drop(1)
    }

    private fun tokenize(input: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        for (ch in input.trim()) {
            when {
                quote != null -> if (ch == quote) quote = null else sb.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (sb.isNotEmpty()) {
                        out.add(sb.toString())
                        sb.setLength(0)
                    }
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}