package gov.anzong.fim.completion.util

object CompletionPostProcessor {

    fun cleanCompletion(rawCode: String, suffix: String): String {
        var text = rawCode
            .replace("<|fim_middle|>", "")
            .replace("<|endoftext|>", "")
            .replace(Regex("^```[a-zA-Z]*\\n"), "")
            .replace(Regex("```$"), "")
            .removeSuffix("\n```")

        if (text.isBlank()) return ""

        // 1. 基础字符串重叠消除 (防完全一致的代码段重叠)
        val cleanSuffix = suffix.trimStart()
        if (cleanSuffix.isNotEmpty()) {
            val maxOverlapCheck = minOf(text.length, cleanSuffix.length, 50)
            for (len in maxOverlapCheck downTo 1) {
                val textTail = text.takeLast(len)
                val suffixHead = cleanSuffix.take(len)
                if (textTail == suffixHead) {
                    text = text.dropLast(len)
                    break
                }
            }
        }

        // 2. 核心优化：智能符号闭合平衡 (Smart Closure Balancing)
        // 专门对付模型无视下文，强行自己补全 } 或 ) 或 ; 的顽疾
        text = trimExcessClosures(text, suffix)

        // 3. 截断极其离谱的多余结构闭合 (防兜底)
        val doubleBraceIndex = text.indexOf("\n}\n}\n")
        if (doubleBraceIndex != -1) {
            text = text.substring(0, doubleBraceIndex + 2)
        }

        // 如果清洗完之后只剩下空行或空格了，直接返回空串，不渲染毫无意义的灰色方块
        if (text.trim().isEmpty()) {
            return ""
        }

        return text
    }

    private fun trimExcessClosures(generatedCode: String, suffix: String): String {
        var text = generatedCode
        val suffixTrimmed = suffix.trimStart()

        // --- A. 处理大括号 } 的幻觉 ---
        // 统计生成代码中大括号的“净闭合数”。
        // 如果 netBraces < 0，说明模型越界了，它试图闭合本不该由它闭合的外部代码块。
        var netBraces = text.count { it == '{' } - text.count { it == '}' }

        // 探测下文本来就有多少个连续的 }
        var suffixBraceMatchCount = 0
        var tempSuffix = suffixTrimmed
        while (tempSuffix.startsWith("}")) {
            suffixBraceMatchCount++
            tempSuffix = tempSuffix.substring(1).trimStart()
        }

        // 如果模型生成了多余的 }，且真实下文本来就有 }，果断把模型生成的 } 吃掉！
        while (netBraces < 0 && suffixBraceMatchCount > 0 && text.trimEnd().endsWith("}")) {
            val lastBraceIndex = text.lastIndexOf('}')
            if (lastBraceIndex != -1) {
                text = text.substring(0, lastBraceIndex)
                netBraces++
                suffixBraceMatchCount--
            } else {
                break
            }
        }

        // --- B. 处理圆括号 ) 的幻觉 ---
        // 常见于 if (xxx<光标>) 或 println(xxx<光标>) 的情况
        var netParens = text.count { it == '(' } - text.count { it == ')' }
        var suffixParenMatchCount = 0
        tempSuffix = suffixTrimmed
        while (tempSuffix.startsWith(")")) {
            suffixParenMatchCount++
            tempSuffix = tempSuffix.substring(1).trimStart()
        }

        while (netParens < 0 && suffixParenMatchCount > 0 && text.trimEnd().endsWith(")")) {
            val lastParenIndex = text.lastIndexOf(')')
            if (lastParenIndex != -1) {
                text = text.substring(0, lastParenIndex)
                netParens++
                suffixParenMatchCount--
            } else {
                break
            }
        }

        // --- C. 处理分号 ; 的幻觉 ---
        // 常见于 int a = 10<光标>;
        if (text.trimEnd().endsWith(";") && suffixTrimmed.startsWith(";")) {
            text = text.substring(0, text.lastIndexOf(";"))
        }

        return text
    }
}