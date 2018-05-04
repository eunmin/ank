package arrow.ank

import arrow.Kind
import arrow.core.FunctionK
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.data.ListK
import arrow.core.Try
import arrow.data.fix
import arrow.data.k
import arrow.typeclasses.MonadError
import com.github.born2snipe.cli.ProgressBarPrinter
import org.intellij.markdown.MarkdownElementTypes.CODE_FENCE
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.accept
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.ast.visitors.RecursiveVisitor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

val extensionMappings = mapOf(
        "java" to "java",
        "kotlin" to "kts"
)

@Suppress("UNCHECKED_CAST")
inline fun <reified F> MonadError<F, Throwable>.ankMonadErrorInterpreter(): FunctionK<ForAnkOps, F> =
        object : FunctionK<ForAnkOps, F> {
            override fun <A> invoke(fa: Kind<ForAnkOps, A>): Kind<F, A> {
                val op = fa.fix()
                return when (op) {
                    is AnkOps.CreateTarget -> catch { createTargetImpl(op.source, op.target) }
                    is AnkOps.GetFileCandidates -> catch { getFileCandidatesImpl(op.target) }
                    is AnkOps.ReadFile -> catch { readFileImpl(op.source) }
                    is AnkOps.ParseMarkdown -> catch { parseMarkDownImpl(op.markdown) }
                    is AnkOps.ExtractCode -> catch { extractCodeImpl(op.source, op.tree) }
                    is AnkOps.CompileCode -> catch { compileCodeImpl(op.snippets, op.compilerArgs) }
                    is AnkOps.ReplaceAnkToLang -> catch { replaceAnkToLangImpl(op.compilationResults) }
                    is AnkOps.GenerateFiles -> catch { generateFilesImpl(op.candidates, op.newContents) }
                } as Kind<F, A>
            }
        }

val SupportedMarkdownExtensions: Set<String> = setOf(
        "markdown",
        "mdown",
        "mkdn",
        "md",
        "mkd",
        "mdwn",
        "mdtxt",
        "mdtext",
        "text",
        "Rmd"
)

fun createTargetImpl(source: File, target: File): File {
    source.copyRecursively(target, overwrite = true)
    return target
}

fun getFileCandidatesImpl(target: File): ListK<File> =
        ListK(target.walkTopDown().filter {
            SupportedMarkdownExtensions.contains(it.extension.toLowerCase())
        }.toList())

fun readFileImpl(source: File): String =
        source.readText()

fun parseMarkDownImpl(markdown: String): ASTNode =
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)

abstract class NoStackTrace(msg: String) : Throwable(msg, null, false, false)

data class CompilationException(
        val file: File,
        val snippet: Snippet,
        val underlying: Throwable,
        val msg: String) : NoStackTrace(msg) {
    override fun toString(): String = msg
}

data class CompiledMarkdown(val origin: File, val snippets: ListK<Snippet>)

data class Snippet(
        val fence: String,
        val lang: String,
        val silent: Boolean,
        val startOffset: Int,
        val endOffset: Int,
        val code: String,
        val result: Option<String> = None)

fun extractCodeImpl(source: String, tree: ASTNode): ListK<Snippet> {
    val sb = mutableListOf<Snippet>()
    tree.accept(object : RecursiveVisitor() {
        override fun visitNode(node: ASTNode) {
            if (node.type == CODE_FENCE) {
                val fence = node.getTextInNode(source)
                val lang = fence.takeWhile { it != ':' }.toString().replace("```", "")
                if (fence.startsWith("```$lang$AnkBlock")) {
                    val code = fence.split("\n").drop(1).dropLast(1).joinToString("\n")
                    sb.add(Snippet(fence.toString(), lang, fence.startsWith("```$lang$AnkSilentBlock"), node.startOffset, node.endOffset, code))
                }
            }
            super.visitNode(node)
        }
    })
    return sb.k()
}

fun compileCodeImpl(snippets: Map<File, ListK<Snippet>>, classpath: ListK<String>): ListK<CompiledMarkdown> {
    println(colored(ANSI_PURPLE, AnkHeader))
    val sortedSnippets = snippets.toList()
    val result = sortedSnippets.mapIndexed { n, (file, codeBlocks) ->
        val pb = ProgressBarPrinter(if (codeBlocks.isEmpty()) 1 else codeBlocks.size)
        pb.setBarCharacter(colored(ANSI_PURPLE, "\u25A0"))
        pb.setBarSize(40)
        pb.setEmptyCharacter("\u25A0")
        pb.println("${file.parentFile.name}/${file.name} [${n + 1} of ${snippets.size}]")

        if (codeBlocks.isEmpty()) pb.step()

        val classLoader = URLClassLoader(classpath.map { URL(it) }.fix().list.toTypedArray())
        val seManager = ScriptEngineManager(classLoader)
        val engineCache: Map<String, ScriptEngine> =
                codeBlocks.list
                        .distinctBy { it.lang }
                        .map {
                            it.lang to seManager.getEngineByExtension(extensionMappings.getOrDefault(it.lang, "kts"))
                        }
                        .toMap()
        val evaledSnippets: ListK<Snippet> = codeBlocks.mapIndexed { blockIndex, snippet ->
            val result: Any? = Try {
                val engine: ScriptEngine = engineCache.k().getOrElse(
                        snippet.lang,
                        {
                            throw CompilationException(
                                    file = file,
                                    snippet = snippet,
                                    underlying = IllegalStateException("No engine configured for `${snippet.lang}`"),
                                    msg = colored(ANSI_RED, "ΛNK compilation failed [ ${file.parentFile.name}/${file.name} ]"))
                        })
                val result = engine.eval(snippet.code)
                pb.step()
                result
            }.fold({
                println("\n\n")
                println(colored(ANSI_RED, "ΛNK compilation failed [ ${file.parentFile.name}/${file.name} ]"))
                throw CompilationException(file, snippet, it, msg = "\n" + """
                    |
                    |```
                    |${snippet.code}
                    |```
                    |${colored(ANSI_RED, it.localizedMessage)}
                    """.trimMargin())
            }, { it })
            if (snippet.silent) {
                snippet
            } else {
                val resultString: Option<String> = Option.fromNullable(result).fold({ None }, { Some("// $it") })
                snippet.copy(result = resultString)
            }
        }.k()
        CompiledMarkdown(file, evaledSnippets)
    }.k()
    return result
}

fun replaceAnkToLangImpl(compiledMarkdown: CompiledMarkdown): String =
        compiledMarkdown.snippets.fold(compiledMarkdown.origin.readText(), { content, snippet ->
            snippet.result.fold(
                    { content.replace(snippet.fence, "```${snippet.lang}\n" + snippet.code + "\n```") },
                    { content.replace(snippet.fence, "```${snippet.lang}\n" + snippet.code + "\n" + it + "\n```") }
            )
        })

fun generateFilesImpl(candidates: ListK<File>, newContents: ListK<String>): ListK<File> =
        ListK(candidates.mapIndexed { n, file ->
            file.printWriter().use {
                it.print(newContents.list[n])
            }
            file
        })
