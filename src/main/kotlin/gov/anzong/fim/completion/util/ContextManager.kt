package gov.anzong.fim.completion.util

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import gov.anzong.fim.completion.settings.AnzongSettingsState

object ContextManager {
    data class FIMContext(
        val prefix: String,
        val suffix: String,
        val isSingleLine: Boolean,
        val localPrefix: String,
        val localSuffix: String
    )

    fun buildFIMContext(project: Project, editor: Editor, offset: Int): FIMContext {
        val settings = AnzongSettingsState.instance
        val document = editor.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        val currentText = document.text

        val rawPrefix = currentText.substring(0, offset)
        val rawSuffix = currentText.substring(offset)
        val maxContext = settings.contextLength
        val projectBasePath = project.guessProjectDir()?.path ?: ""

        val lineEnd = currentText.indexOf('\n', offset).let { if (it == -1) currentText.length else it }
        val textAfterCaretInLine = currentText.substring(offset, lineEnd).trim()
        val isSingleLine = textAfterCaretInLine.isNotEmpty()

        // 1. 跨文件上下文 (按比例动态占用)
        val extraContext = StringBuilder()
        val openFiles = FileEditorManager.getInstance(project).openFiles
        for (file in openFiles) {
            if (file == virtualFile || file.length > 500 * 1024) continue
            val doc = FileDocumentManager.getInstance().getDocument(file) ?: continue
            val relPath = file.path.removePrefix("$projectBasePath/")
            val ext = file.extension

            extraContext.append("\n").append(buildCommentLine(ext, "--- Related File: $relPath ---")).append("\n")
            val snippet = if (doc.text.length > 1500) doc.text.substring(0, 1500) + "\n" + buildCommentLine(ext, "...") + "\n" else doc.text
            extraContext.append(snippet).append("\n\n")

            if (extraContext.length > maxContext * 0.4) break
        }

        // 2. 提取当前文件顶部的 Import 语句
        val imports = currentText.lines().take(80).filter { line ->
            val t = line.trimStart()
            t.startsWith("import ") || t.startsWith("using ") || t.startsWith("require") ||
                    t.startsWith("from ") || t.startsWith("use ") || t.startsWith("#include ") ||
                    t.startsWith("#import ") || t.startsWith("@import ")
        }.take(12)

        var languageName = "Unknown"
        var frameworkHint = ""
        var structuralHint = ""
        var balancedSuffix = rawSuffix
        val visibleSymbols = mutableListOf<String>()

        // 3. 基于 PSI 的深度语义提取
        ReadAction.run<Throwable> {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
            if (psiFile != null) {
                languageName = psiFile.language.displayName
                frameworkHint = detectFramework(currentText)

                val elementAtCaret = psiFile.findElementAt(maxOf(0, offset - 1))
                if (elementAtCaret != null) {
                    var parent: PsiElement? = elementAtCaret.parent
                    var scopeEndOffset = -1

                    while (parent != null && parent !is com.intellij.psi.PsiFile) {
                        if (parent.textRange.endOffset > offset + 5) {
                            scopeEndOffset = parent.textRange.endOffset
                            if (parent is PsiNamedElement && structuralHint.isEmpty()) {
                                structuralHint = parent.name ?: ""
                            }
                        }

                        parent.children.forEach { child ->
                            if (child is PsiNamedElement && child.textRange.endOffset <= offset) {
                                child.name?.takeIf { it.isNotBlank() }?.let { visibleSymbols.add(it) }
                            }
                        }
                        parent = parent.parent
                    }

                    if (scopeEndOffset != -1 && scopeEndOffset <= currentText.length) {
                        val proposedSuffix = currentText.substring(offset, scopeEndOffset)
                        if (proposedSuffix.length < maxContext / 3) {
                            balancedSuffix = proposedSuffix
                        }
                    }
                }
            }
        }

        val currentRelPath = virtualFile?.path?.removePrefix("$projectBasePath/") ?: "Snippet"
        val ext = virtualFile?.extension

        // 4. 使用安全的闭合格式注入头部提示词
        val currentFileHeader = StringBuilder().apply {
            append(buildCommentLine(ext, "Language: $languageName")).append("\n")
            append(buildCommentLine(ext, "Path: $currentRelPath")).append("\n")
            if (frameworkHint.isNotEmpty()) append(buildCommentLine(ext, "Framework: $frameworkHint")).append("\n")
            if (structuralHint.isNotEmpty()) append(buildCommentLine(ext, "Context: Inside `$structuralHint`")).append("\n")
            if (imports.isNotEmpty()) append(buildCommentLine(ext, "Imports available: ${imports.joinToString(" | ")}")).append("\n")
            if (visibleSymbols.isNotEmpty()) append(buildCommentLine(ext, "Visible symbols: ${visibleSymbols.distinct().take(20).joinToString(", ")}")).append("\n")
            append(buildCommentLine(ext, "Code begins below:")).append("\n")
        }.toString()

        val combinedPrefix = extraContext.toString() + currentFileHeader + rawPrefix
        val finalPrefix = if (combinedPrefix.length > maxContext) {
            val prefixCode = rawPrefix.takeLast(maxContext - extraContext.length - currentFileHeader.length)
            extraContext.toString() + currentFileHeader + prefixCode
        } else {
            combinedPrefix
        }

        var trimmedSuffix = balancedSuffix.trimStart(' ', '\t', '\n', '\r')
        if (trimmedSuffix.length > maxContext / 4) {
            trimmedSuffix = trimmedSuffix.substring(0, maxContext / 4)
        }

        return FIMContext(finalPrefix, trimmedSuffix, isSingleLine, rawPrefix, rawSuffix)
    }

    // 多语言严格闭合注释适配器
    private fun buildCommentLine(extension: String?, text: String): String {
        return when (extension?.lowercase()) {
            "py", "rb", "sh", "bash", "zsh", "yaml", "yml", "toml", "ini", "properties", "dockerfile", "pl", "r", "ps1", "gd", "gitignore" -> "# $text"
            "html", "htm", "xml", "vue", "svg", "md" -> "<!-- $text -->"
            "css", "scss", "less", "sass" -> "/* $text */"
            "clj", "el", "lisp", "scm", "asm", "s" -> "; $text"
            "sql", "lua", "hs", "vhdl" -> "-- $text"
            "vim" -> "\" $text"
            "erl", "hrl", "tex" -> "% $text"
            // 明确加入 Go, Rust, C, C++, Swift 等
            "go", "rs", "c", "cpp", "cs", "swift", "dart", "java", "kt", "js", "ts", "php", "m", "mm" -> "// $text"
            else -> "// $text"
        }
    }

    private fun detectFramework(text: String): String {
        val lowerText = text.lowercase()
        return when {
            lowerText.contains("import swiftui") -> "SwiftUI"
            lowerText.contains("import uikit") || lowerText.contains("#import <uikit") -> "UIKit (iOS)"
            lowerText.contains("from 'react'") || lowerText.contains("from \"react\"") -> "React.js"
            lowerText.contains("from 'next'") || lowerText.contains("from \"next\"") -> "Next.js"
            lowerText.contains("from 'vue'") || lowerText.contains("import vue") -> "Vue.js"
            lowerText.contains("@angular/core") -> "Angular"
            lowerText.contains("from 'svelte'") -> "Svelte"
            lowerText.contains("django.") || lowerText.contains("import django") -> "Django"
            lowerText.contains("from flask") || lowerText.contains("import flask") -> "Flask"
            lowerText.contains("fastapi") -> "FastAPI"
            lowerText.contains("import torch") || lowerText.contains("import tensorflow") -> "AI/ML (PyTorch/TensorFlow)"
            lowerText.contains("illuminate\\") || lowerText.contains("laravel") -> "Laravel"
            lowerText.contains("symfony\\") -> "Symfony"
            lowerText.contains("think\\") -> "ThinkPHP"
            lowerText.contains("org.springframework") -> "Spring Boot"
            lowerText.contains("androidx.compose") -> "Jetpack Compose"
            lowerText.contains("javax.persistence") || lowerText.contains("jakarta.persistence") -> "JPA/Hibernate"
            lowerText.contains("package:flutter") -> "Flutter"
            lowerText.contains("\"github.com/gin-gonic/gin\"") || lowerText.contains("gin.") -> "Gin (Go)"
            lowerText.contains("express()") || lowerText.contains("require('express')") -> "Express.js"
            lowerText.contains("rocket::") || lowerText.contains("actix_web") -> "Rust Web Framework"
            else -> ""
        }
    }
}