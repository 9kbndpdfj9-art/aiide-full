package com.aiide

import android.util.Log

class PredictiveEditEngine {

    companion object {
        private const val TAG = "PredictiveEditEngine"
        private const val MAX_PREDICTIONS = 10
        private const val MIN_CONFIDENCE = 0.3f
    }

    private val closingPairs = mapOf(
        '(' to ')',
        '[' to ']',
        '{' to '}',
        '"' to '"',
        '\'' to '\'',
        '`' to '`'
    )

    private val keywordCompletions = mapOf(
        "con" to listOf("const ", "continue", "console.log(", "constructor("),
        "fun" to listOf("function ", "fun "),
        "ret" to listOf("return "),
        "imp" to listOf("import ", "implements "),
        "exp" to listOf("export ", "extends "),
        "asy" to listOf("async "),
        "awa" to listOf("await "),
        "int" to listOf("interface ", "internal "),
        "typ" to listOf("type ", "typeof "),
        "enu" to listOf("enum "),
        "cla" to listOf("class "),
        "swi" to listOf("switch "),
        "cas" to listOf("case "),
        "def" to listOf("default:", "def "),
        "whi" to listOf("while "),
        "els" to listOf("else ", "else if "),
        "eli" to listOf("else if "),
        "try" to listOf("try "),
        "cat" to listOf("catch "),
        "fin" to listOf("finally "),
        "thr" to listOf("throw ", "throws "),
        "new" to listOf("new "),
        "thi" to listOf("this."),
        "sup" to listOf("super("),
        "pri" to listOf("private ", "println(", "print("),
        "pub" to listOf("public "),
        "pro" to listOf("protected "),
        "sta" to listOf("static "),
        "get" to listOf("get ", "getAttribute("),
        "set" to listOf("set ", "setAttribute("),
        "doc" to listOf("document.", "DOMContentLoaded"),
        "win" to listOf("window."),
        "use" to listOf("useState", "useEffect", "useCallback", "useMemo", "useRef")
    )

    fun predict(
        filePath: String,
        cursorLine: Int,
        cursorCol: Int,
        prefix: String
    ): List<Prediction> {
        val predictions = mutableListOf<Prediction>()

        try {
            if (cursorLine < 0 || cursorCol < 0) return emptyList()

            val extension = filePath.substringAfterLast('.', "")

            addBracketCompletion(prefix, predictions)
            addSnippetPredictions(prefix, extension, predictions)
            addKeywordPredictions(prefix, predictions)
            addContextPredictions(prefix, extension, predictions)

            predictions.sortByDescending { it.confidence }

            return predictions
                .filter { it.confidence >= MIN_CONFIDENCE }
                .distinctBy { it.text }
                .take(MAX_PREDICTIONS)
        } catch (e: Exception) {
            Log.e(TAG, "Prediction failed", e)
            return emptyList()
        }
    }

    private fun addBracketCompletion(prefix: String, predictions: MutableList<Prediction>) {
        if (prefix.isEmpty()) return

        val lastChar = prefix.last()
        val closing = closingPairs[lastChar]
        if (closing != null) {
            predictions.add(Prediction(
                text = closing.toString(),
                confidence = 0.95f,
                type = "bracket",
                source = "auto_close"
            ))
        }

        val openCount = mutableMapOf<Char, Int>()
        for (c in prefix) {
            when (c) {
                '(', '[', '{' -> openCount[c] = (openCount[c] ?: 0) + 1
                ')', ']', '}' -> {
                    val open = when (c) {
                        ')' -> '('
                        ']' -> '['
                        '}' -> '{'
                        else -> return
                    }
                    openCount[open] = maxOf(0, (openCount[open] ?: 0) - 1)
                }
            }
        }

        val unclosed = openCount.filterValues { it > 0 }
        val maxSuggestions = 3
        var suggestionsAdded = 0
        unclosed.entries.sortedByDescending { it.key }.forEach { (openChar, count) ->
            if (suggestionsAdded >= maxSuggestions) return@forEach
            val closeChar = closingPairs[openChar]
            if (closeChar != null) {
                val effectiveCount = minOf(count, maxSuggestions - suggestionsAdded)
                repeat(effectiveCount) {
                    predictions.add(Prediction(
                        text = closeChar.toString(),
                        confidence = 0.8f - (suggestionsAdded * 0.1f),
                        type = "bracket",
                        source = "auto_close"
                    ))
                }
                suggestionsAdded += effectiveCount
            }
        }
    }

    data class SnippetPattern(
        val name: String,
        val trigger: Regex,
        val template: String,
        val type: String,
        val confidence: Float
    )

    private val snippetPatterns = listOf(
        SnippetPattern(
            "for_loop",
            Regex("""for\s*\($""),
            " (let i = 0; i < \${length}; i++) {\n\t\n}",
            "snippet",
            0.85f
        ),
        SnippetPattern(
            "for_loop_py",
            Regex("""for\s+\w+\s+in\s*$"""),
            " range(\${length}):\n\t\n",
            "snippet",
            0.85f
        ),
        SnippetPattern(
            "if_statement",
            Regex("""if\s*\($""),
            " (\${condition}) {\n\t\n}",
            "snippet",
            0.8f
        ),
        SnippetPattern(
            "if_statement_py",
            Regex("""if\s+.+:$"""),
            " \${condition}:\n\t\n",
            "snippet",
            0.8f
        ),
        SnippetPattern(
            "function_decl",
            Regex("""function\s+\w*\s*\($""),
            " (\${params}) {\n\t\n}",
            "snippet",
            0.75f
        ),
        SnippetPattern(
            "function_decl_py",
            Regex("""def\s+\w*\s*\($""),
            " (\${params}):\n\t\n",
            "snippet",
            0.75f
        ),
        SnippetPattern(
            "try_catch",
            Regex("""try\s*\{$"""),
            " {\n\t\n} catch (\${error}) {\n\t\n}",
            "snippet",
            0.7f
        ),
        SnippetPattern(
            "return_statement",
            Regex("""return\s*$"""),
            " \${value};",
            "snippet",
            0.65f
        ),
        SnippetPattern(
            "class_decl",
            Regex("""class\s+\w*\s*\{$"""),
            " \${name} {\n\tconstructor(\${params}) {\n\t\t\n\t}\n}",
            "snippet",
            0.7f
        ),
        SnippetPattern(
            "import_statement",
            Regex("""import\s*$"""),
            " \${module} from '\${path}';",
            "snippet",
            0.75f
        ),
        SnippetPattern(
            "fetch_api",
            Regex("""fetch\s*\($""),
            " ('\${url}')\n\t.then(response => response.json())\n\t.then(data => \${handler})\n\t.catch(error => \${errorHandler});",
            "snippet",
            0.8f
        )
    )

    private fun addSnippetPredictions(prefix: String, extension: String, predictions: MutableList<Prediction>) {
        val trimmedPrefix = prefix.trimEnd()
        if (trimmedPrefix.isEmpty()) return

        val relevantPatterns = snippetPatterns.filter { pattern ->
            when {
                extension in listOf("js", "jsx", "ts", "tsx") -> true
                extension == "py" && pattern.name in listOf("for_loop_py", "if_statement_py", "function_decl_py", "try_catch", "return_statement", "class_decl", "import_statement", "fetch_api") -> true
                extension in listOf("kt", "java") && pattern.name in listOf("for_loop", "if_statement", "function_decl", "try_catch", "return_statement", "class_decl", "import_statement") -> true
                extension in listOf("html", "htm") && pattern.name in listOf("fetch_api") -> true
                else -> false
            }
        }

        for (pattern in relevantPatterns) {
            if (pattern.trigger.containsMatchIn(trimmedPrefix)) {
                val template = pattern.template
                    .replace("\${length}", "array.length")
                    .replace("\${condition}", "condition")
                    .replace("\${value}", "value")
                    .replace("\${module}", "module")
                    .replace("\${name}", "name")
                    .replace("\${url}", "url")
                    .replace("\${params}", "params")
                    .replace("\${handler}", "data => console.log(data)")
                    .replace("\${errorHandler}", "error => console.error(error)")
                    .replace("\${error}", "error")
                    .replace("\${path}", "./module")

                predictions.add(Prediction(
                    text = template,
                    confidence = pattern.confidence,
                    type = pattern.type,
                    source = "snippet:${pattern.name}"
                ))
            }
        }
    }

    private fun addKeywordPredictions(prefix: String, predictions: MutableList<Prediction>) {
        val trimmedPrefix = prefix.trim()
        if (trimmedPrefix.isEmpty()) return

        val lastWord = trimmedPrefix.split(Regex("\\s+")).lastOrNull() ?: return
        if (lastWord.length < 2) return

        val lowerWord = lastWord.lowercase()

        keywordCompletions[lowerWord]?.forEach { completion ->
            predictions.add(Prediction(
                text = completion,
                confidence = 0.75f,
                type = "keyword",
                source = "keyword_completion"
            ))
        }

        for ((key, completions) in keywordCompletions) {
            if (key.startsWith(lowerWord) && key != lowerWord) {
                completions.forEach { completion ->
                    predictions.add(Prediction(
                        text = completion,
                        confidence = 0.6f,
                        type = "keyword",
                        source = "fuzzy_keyword"
                    ))
                }
            }
        }
    }

    private fun addContextPredictions(prefix: String, extension: String, predictions: MutableList<Prediction>) {
        val lines = prefix.lines()
        val currentLine = lines.lastOrNull() ?: return

        if (currentLine.trim().startsWith("//") || currentLine.trim().startsWith("#") ||
            currentLine.trim().startsWith("/*") || currentLine.trim().startsWith("*")) {
            predictions.add(Prediction(
                text = "\n${currentLine.trim().take(2)}",
                confidence = 0.5f,
                type = "comment_continuation",
                source = "context"
            ))
        }

        if (currentLine.trim().startsWith("import ") || currentLine.trim().startsWith("from ")) {
            val moduleName = currentLine.substringAfter("import ").substringBefore(" from").trim()
            if (moduleName.isNotEmpty()) {
                predictions.add(Prediction(
                    text = "export { \${moduleName} }",
                    confidence = 0.4f,
                    type = "context",
                    source = "import_context"
                ))
            }
        }

        val openBrackets = currentLine.count { it in "([{" } - currentLine.count { it in ")]}" }
        if (openBrackets > 0) {
            val closing = when {
                currentLine.contains('{') -> "}"
                currentLine.contains('(') -> ")"
                currentLine.contains('[') -> "]"
                else -> ""
            }
            if (closing.isNotEmpty()) {
                predictions.add(Prediction(
                    text = "$closing;",
                    confidence = 0.6f,
                    type = "context",
                    source = "auto_close"
                ))
            }
        }
    }

    data class Prediction(
        val text: String,
        val confidence: Float,
        val type: String,
        val source: String
    )

    fun predictNextEdit(
        filePath: String,
        currentContent: String,
        editHistory: List<EditRecord>,
        cursorLine: Int,
        cursorCol: Int
    ): PredictionResult {
        if (cursorLine < 0 || cursorCol < 0) {
            return PredictionResult(
                bestPrediction = Prediction("", 0.0f, "none", "invalid_cursor"),
                alternativePredictions = emptyList(),
                contextualEdits = emptyList(),
                contextScore = 0.0,
                predictionBasis = "invalid cursor position"
            )
        }

        val predictions = mutableListOf<Prediction>()
        val allLines = currentContent.lines()
        val currentLineText = if (cursorLine in allLines.indices) allLines[cursorLine] else ""
        val prefix = currentLineText.take(cursorCol)
        val suffix = currentLineText.drop(cursorCol)

        val contextWindow = buildContextWindow(allLines, cursorLine, windowSize = 15)
        val extension = filePath.substringAfterLast('.', "")

        val bracketPrediction = analyzeBracketContext(prefix, suffix, allLines, cursorLine)
        if (bracketPrediction != null) {
            predictions.add(bracketPrediction)
        }

        val editPatternPredictions = analyzeEditPatterns(editHistory, currentLineText, prefix, extension)
        predictions.addAll(editPatternPredictions)

        val structuralPredictions = analyzeStructureContext(contextWindow, extension)
        predictions.addAll(structuralPredictions)

        val semanticPredictions = analyzeSemanticContext(prefix, allLines, cursorLine, extension)
        predictions.addAll(semanticPredictions)

        val keywordPredictions = generateKeywordPredictions(prefix, allLines, cursorLine)
        predictions.addAll(keywordPredictions)

        predictions.sortByDescending { it.confidence }

        val topPrediction = predictions.firstOrNull() ?: Prediction(
            text = "",
            confidence = 0.0f,
            type = "none",
            source = "no_context"
        )

        val contextualEdits = mutableListOf<ContextualEdit>()
        if (prefix.isEmpty() && suffix.isEmpty() && isBlockStartContext(allLines, cursorLine, extension)) {
            contextualEdits.add(createBlockInsertEdit(extension, cursorLine))
        }

        return PredictionResult(
            bestPrediction = topPrediction,
            alternativePredictions = predictions.distinctBy { it.text }.take(MAX_PREDICTIONS),
            contextualEdits = contextualEdits,
            contextScore = computeContextScore(contextWindow, editHistory),
            predictionBasis = computePredictionBasis(editHistory, contextWindow, prefix)
        )
    }

    data class EditRecord(
        val timestamp: Long = System.currentTimeMillis(),
        val filePath: String,
        val line: Int,
        val oldText: String,
        val newText: String,
        val editType: String = "insert"
    )

    data class ContextualEdit(
        val position: Int,
        val column: Int,
        val suggestedAction: String,
        val editTemplate: String,
        val confidence: Float
    )

    data class PredictionResult(
        val bestPrediction: Prediction,
        val alternativePredictions: List<Prediction>,
        val contextualEdits: List<ContextualEdit>,
        val contextScore: Double,
        val predictionBasis: String
    )

    private fun buildContextWindow(allLines: List<String>, cursorLine: Int, windowSize: Int): List<String> {
        val halfWindow = windowSize / 2.0
        val start = maxOf(0, (cursorLine - halfWindow).toInt())
        val end = minOf(allLines.size, (cursorLine + halfWindow + 1).toInt())
        return allLines.subList(start, end)
    }

    private fun analyzeBracketContext(
        prefix: String,
        suffix: String,
        allLines: List<String>,
        cursorLine: Int
    ): Prediction? {
        if (prefix.isNotEmpty()) {
            val lastChar = prefix.last()
            closingPairs[lastChar]?.let { closing ->
                if (suffix.isEmpty() || suffix.first() != closing) {
                    return Prediction(
                        text = closing.toString(),
                        confidence = 0.95f,
                        type = "bracket",
                        source = "bracket_context"
                    )
                }
            }
        }

        var openCount = 0
        var closeCount = 0
        for (i in 0..cursorLine) {
            if (i in allLines.indices) {
                val line = allLines[i]
                val partToAnalyze = if (i == cursorLine) prefix else line
                openCount += partToAnalyze.count { it in "([{" }
                closeCount += partToAnalyze.count { it in ")]}" }
            }
        }

        if (openCount > closeCount) {
            val needed = minOf(openCount - closeCount, 5)
            val closestOpen = findUnclosedBracket(prefix)
            closestOpen?.let { openBracket ->
                val closingChar = closingPairs[openBracket]
                if (closingChar != null) {
                    return Prediction(
                        text = closingChar.toString().repeat(needed),
                        confidence = (0.8f - (needed * 0.05f)).coerceAtLeast(0.3f),
                        type = "bracket",
                        source = "bracket_balance"
                    )
                }
            }
        }

        return null
    }

    private fun findUnclosedBracket(prefix: String): Char? {
        val stack = mutableListOf<Char>()
        val opening = setOf('(', '[', '{')
        val closingMap = mapOf(')' to '(', ']' to '[', '}' to '{')

        for (char in prefix) {
            when {
                char in opening -> stack.add(char)
                char in closingMap -> {
                    if (stack.isNotEmpty() && stack.last() == closingMap[char]) {
                        stack.removeAt(stack.lastIndex)
                    }
                }
            }
        }
        return stack.lastOrNull()
    }

    private fun analyzeEditPatterns(
        editHistory: List<EditRecord>,
        currentLineText: String,
        prefix: String,
        extension: String
    ): List<Prediction> {
        if (editHistory.isEmpty()) return emptyList()

        val predictions = mutableListOf<Prediction>()
        val recentEdits = editHistory.takeLast(20)
        val lastEdit = recentEdits.lastOrNull() ?: return emptyList()

        if (lastEdit.newText.isEmpty()) return emptyList()

        val similarEdits = recentEdits.filter { edit ->
            edit.filePath == lastEdit.filePath &&
            edit.editType == lastEdit.editType &&
            edit.newText.isNotEmpty() &&
            edit.newText.length in (lastEdit.newText.length * 0.5)..(lastEdit.newText.length * 2.0)
        }

        if (similarEdits.size >= 3) {
            val pattern = extractEditPattern(similarEdits)
            if (pattern != null) {
                predictions.add(Prediction(
                    text = pattern,
                    confidence = 0.7f,
                    type = "pattern",
                    source = "edit_history"
                ))
            }
        }

        val sameLineEdits = recentEdits.filter { it.line == lastEdit.line && it.newText.isNotEmpty() }
        if (sameLineEdits.size >= 2) {
            val continuation = inferContinuation(sameLineEdits, prefix)
            if (continuation != null) {
                predictions.add(Prediction(
                    text = continuation,
                    confidence = 0.65f,
                    type = "continuation",
                    source = "same_line_pattern"
                ))
            }
        }

        return predictions
    }

    private fun extractEditPattern(edits: List<EditRecord>): String? {
        val commonPrefix = findCommonPrefix(edits.map { it.newText })
        val commonSuffix = findCommonSuffix(edits.map { it.newText })

        if (commonPrefix.length > 3 || commonSuffix.length > 3) {
            return if (commonPrefix.isNotEmpty()) commonPrefix else commonSuffix
        }
        return null
    }

    private fun findCommonPrefix(strings: List<String>): String {
        if (strings.isEmpty()) return ""
        val first = strings.first()
        for (i in first.indices) {
            val char = first[i]
            for (str in strings.drop(1)) {
                if (i >= str.length || str[i] != char) {
                    return first.substring(0, i)
                }
            }
        }
        return first
    }

    private fun findCommonSuffix(strings: List<String>): String {
        if (strings.isEmpty()) return ""
        val first = strings.first()
        val minLen = strings.minOf { it.length }
        if (minLen == 0) return ""
        for (i in 1..minLen) {
            val char = first[first.length - i]
            for (j in 1 until strings.size) {
                val str = strings[j]
                if (str[str.length - i] != char) {
                    return first.substring(first.length - i + 1)
                }
            }
        }
        return first.substring(first.length - minLen)
    }

    private fun inferContinuation(edits: List<EditRecord>, prefix: String): String? {
        val newTexts = edits.map { it.newText }.distinct()
        if (newTexts.size < 2) return null

        val lastText = newTexts.last()
        if (lastText.startsWith(prefix)) {
            return lastText.substring(prefix.length)
        }
        return null
    }

    private fun analyzeStructureContext(contextWindow: List<String>, extension: String): List<Prediction> {
        val predictions = mutableListOf<Prediction>()

        var indentLevel = 0
        var inFunction = false
        var inClass = false
        var bracketDepth = 0

        for (line in contextWindow) {
            val trimmed = line.trim()
            if (trimmed.startsWith("fun ") || trimmed.startsWith("function ") || trimmed.startsWith("def ")) {
                inFunction = true
                inClass = false
            }
            if (trimmed.startsWith("class ") || trimmed.startsWith("interface ")) {
                inClass = true
                inFunction = false
            }
            val indentChars = line.takeWhile { it == ' ' || it == '\t' }
            val tabCount = indentChars.count { it == '\t' }
            val spaceCount = indentChars.count { it == ' ' }
            indentLevel = (tabCount * 4 + spaceCount) / 4
            bracketDepth += trimmed.count { it in "([{" } - trimmed.count { it in ")]}" }
            if (bracketDepth <= 0 && (trimmed.isEmpty() || trimmed.startsWith("}") || trimmed.startsWith(")"))) {
                if (inFunction && indentLevel == 0) inFunction = false
                if (inClass && indentLevel == 0) inClass = false
            }
        }

        val functionKeywords = when (extension) {
            "js", "jsx", "ts", "tsx" -> listOf("return ", "const ", "let ", "if (", "for (", "while (")
            "py" -> listOf("return ", "if ", "for ", "while ", "try:")
            "kt", "java" -> listOf("return ", "val ", "var ", "if (", "for (", "when (")
            else -> listOf("return ", "if ", "for ")
        }

        if (inFunction && indentLevel > 0) {
            functionKeywords.forEach { keyword ->
                predictions.add(Prediction(
                    text = keyword,
                    confidence = 0.45f,
                    type = "structural",
                    source = "function_body"
                ))
            }
        }

        if (inClass && indentLevel == 1) {
            val memberKeywords = when (extension) {
                "js", "jsx", "ts", "tsx" -> listOf("constructor(", "async ", "static ")
                "py" -> listOf("def __init__(", "def ")
                "kt", "java" -> listOf("fun ", "var ", "val ", "init {")
                else -> listOf("function ", "var ")
            }
            memberKeywords.forEach { keyword ->
                predictions.add(Prediction(
                    text = keyword,
                    confidence = 0.4f,
                    type = "structural",
                    source = "class_body"
                ))
            }
        }

        return predictions
    }

    private fun analyzeSemanticContext(
        prefix: String,
        allLines: List<String>,
        cursorLine: Int,
        extension: String
    ): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        val words = prefix.split(Regex("[\\s\\W]+")).filter { it.length >= 3 }
        val lastWord = words.lastOrNull() ?: return emptyList()

        val symbolCompletions = findSymbolCompletions(lastWord, allLines, cursorLine)
        symbolCompletions.forEach { completion ->
            predictions.add(Prediction(
                text = completion.substring(lastWord.length),
                confidence = 0.55f,
                type = "symbol",
                source = "semantic_context"
            ))
        }

        val importContext = findImportContext(allLines, extension)
        if (importContext != null) {
            predictions.add(importContext)
        }

        return predictions
    }

    private fun findSymbolCompletions(
        prefix: String,
        allLines: List<String>,
        cursorLine: Int
    ): List<String> {
        val symbolPattern = Regex("""(?:fun|function|def|class|val|var|const|let)\\s+(\\w+)""")
        val completions = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        outer@ for ((i, line) in allLines.withIndex()) {
            if (i == cursorLine) continue
            for (match in symbolPattern.findAll(line)) {
                val symbol = match.groupValues[1]
                if (symbol.startsWith(prefix, ignoreCase = true) && symbol.length > prefix.length && !seen.contains(symbol)) {
                    seen.add(symbol)
                    completions.add(symbol)
                    if (completions.size >= 5) break@outer
                }
            }
        }
        return completions
    }

    private fun findImportContext(allLines: List<String>, extension: String): Prediction? {
        val importLines = allLines.filter { line ->
            val trimmed = line.trim()
            when (extension) {
                "js", "jsx", "ts", "tsx" -> trimmed.startsWith("import ") || trimmed.startsWith("export ")
                "py" -> trimmed.startsWith("import ") || trimmed.startsWith("from ")
                "kt", "java" -> trimmed.startsWith("import ")
                else -> trimmed.startsWith("import ")
            }
        }

        if (importLines.isNotEmpty()) {
            val lastImport = importLines.last()
            return Prediction(
                text = "\n$lastImport",
                confidence = 0.35f,
                type = "import",
                source = "import_context"
            )
        }
        return null
    }

    private fun generateKeywordPredictions(prefix: String, allLines: List<String>, cursorLine: Int): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        val trimmedPrefix = prefix.trim()
        if (trimmedPrefix.isEmpty()) return emptyList()

        val lastWord = trimmedPrefix.split(Regex("\\s+")).lastOrNull() ?: return emptyList()
        if (lastWord.length < 2) return emptyList()

        val lowerWord = lastWord.lowercase()

        keywordCompletions[lowerWord]?.forEach { completion ->
            predictions.add(Prediction(
                text = completion,
                confidence = 0.65f,
                type = "keyword",
                source = "keyword_completion"
            ))
        }

        for ((key, completions) in keywordCompletions) {
            if (key.startsWith(lowerWord) && key != lowerWord) {
                completions.forEach { completion ->
                    predictions.add(Prediction(
                        text = completion,
                        confidence = 0.5f,
                        type = "keyword",
                        source = "fuzzy_keyword"
                    ))
                }
            }
        }

        return predictions
    }

    private fun isBlockStartContext(allLines: List<String>, cursorLine: Int, extension: String): Boolean {
        if (cursorLine >= allLines.size) return false
        val currentLine = allLines[cursorLine].trim()
        val isEmpty = currentLine.isEmpty() || currentLine.all { it.isWhitespace() }

        if (!isEmpty) return false

        val hasPrevLine = cursorLine > 0
        if (!hasPrevLine) return false

        val prevLine = allLines[cursorLine - 1].trim()
        return when (extension) {
            "js", "jsx", "ts", "tsx" -> prevLine.endsWith("{") || prevLine.endsWith("(") || prevLine.endsWith("=>")
            "py" -> prevLine.endsWith(":")
            "kt", "java" -> prevLine.endsWith("{") || prevLine.endsWith("(") || prevLine.endsWith(")")
            else -> prevLine.endsWith("{") || prevLine.endsWith(":")
        }
    }

    private fun createBlockInsertEdit(extension: String, cursorLine: Int): ContextualEdit {
        val template = when (extension) {
            "js", "jsx", "ts", "tsx" -> "\t// TODO: implement\n"
            "py" -> "\tpass\n"
            "kt", "java" -> "\t// TODO: implement\n"
            else -> "\t// TODO\n"
        }
        return ContextualEdit(
            position = cursorLine,
            column = 0,
            suggestedAction = "insert_block_body",
            editTemplate = template,
            confidence = 0.6f
        )
    }

    private fun computeContextScore(contextWindow: List<String>, editHistory: List<EditRecord>): Double {
        val contextWeight = minOf(1.0, contextWindow.size / 15.0)
        val historyWeight = minOf(1.0, editHistory.size / 10.0)
        return (contextWeight * 0.6 + historyWeight * 0.4)
    }

    private fun computePredictionBasis(editHistory: List<EditRecord>, contextWindow: List<String>, prefix: String): String {
        val basisParts = mutableListOf<String>()
        if (editHistory.isNotEmpty()) basisParts.add("${editHistory.size} edit(s) in history")
        if (contextWindow.isNotEmpty()) basisParts.add("${contextWindow.size} line context window")
        if (prefix.isNotEmpty()) basisParts.add("prefix: '$prefix'")
        if (basisParts.isEmpty()) basisParts.add("no context available")
        return basisParts.joinToString("; ")
    }
}