# AnzongFIMCompletion

[![JetBrains Plugin](https://img.shields.io/badge/JetBrains-Plugin-blue.svg)](https://plugins.jetbrains.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%20%2F%2021-orange.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**AnzongFIMCompletion** 是一个为 IntelliJ IDEA 打造的生产级、高性能本地/远程 AI 辅助代码补全插件（FIM - Fill-In-the-Middle）。它旨在提供媲美 GitHub Copilot 或 Cursor 的流畅幽灵文本（Ghost Text）补全体验，完美契合自托管大模型生态。

---

## 🚀 核心特性 (Key Features)

- **🧠 深度 PSI 语义感知 (AST-based Context)**：
    - 利用 IntelliJ 强大的程序结构接口（PSI），自动提取当前光标所在的方法名、类名、局部变量及文件 `import` 列表。
    - **框架智能嗅探**：自动识别 Flutter、Spring Boot、React、Vue、Django、Gin、SwiftUI 等主流技术栈，向大模型注入精准的背景提示。
- **⚡ 毫秒级异步与并发防抖**：
    - 基于 Kotlin Coroutines (`flow`) 与 OkHttp 构建。
    - **智能中断机制**：当用户持续敲击键盘或删除字符时，旧的网络请求会被瞬间掐断，绝不卡死 IDE 主线程。
- **🔌 双协议完美兼容**：
    - 原生支持 **Ollama**（通过底层 `prompt` + `suffix` 及 `raw=true` 路由，支持各类 GGUF 代码模型）。
    - 支持所有标准 **OpenAI 兼容接口**（如 vLLM、LM Studio 等的 `/v1/completions` 端点）。
- **🛡️ 智能括号平衡与防幻觉 (Smart Closure Balancing)**：
    - 内置后处理清洗管道，自动消除大模型常见的“双括号”、“双分号”及越界闭合幻觉，采纳后直接编译通过。
- **🌍 全语种统御 (Multi-Language Support)**：
    - 自适应安全注释适配器 (`buildCommentLine`)。无论编写 Python、Vue、React、Go、Rust、Java、Kotlin、Swift 还是 CSS、HTML，提示词头部都会使用对应语言的严格注释符号，绝不破坏语法。

---

## 📂 插件架构 (Project Structure)

```text
.
├── src/main/kotlin/gov/anzong/fim/completion/
│   ├── client/          # 网络请求构建器 (Ollama / OpenAI 适配与 Stop Tokens)
│   ├── provider/        # 核心内联补全引擎 (事件拦截与幽灵文本渲染)
│   ├── settings/        # 异步模型拉取与配置持久化 UI
│   ├── startup/         # 全局黑客级事件注入与环境初始化
│   └── util/            # PSI 上下文提取与代码后处理清洗管道
├── src/main/resources/
│   └── META-INF/        # 插件描述文件 (plugin.xml) 与矢量图标 (pluginIcon.svg)
├── build.gradle.kts     # Gradle 构建脚本 (jvmToolchain 21)
└── gradle.properties    # 版本与平台配置
```

---

## ⚙️ 配置与使用 (Configuration)

1. 安装并启动你的本地或远程推理服务（如 Ollama，推荐模型：`qwen2.5-coder`）。
2. 在 IntelliJ IDEA 中打开：`Settings` -> `Tools` -> `Anzong FIM Completion`。
3. 填写相关参数：
* **API Type**: 选择 `OLLAMA` 或 `OPENAI_COMPATIBLE`。
* **Base API URL**: 填写服务根地址（插件会自动对齐 `/api/generate` 或 `/v1/completions`）。
* **Model Name**: 点击下拉菜单可自动异步拉取可用模型列表，亦可手动输入。
* **Max Context / Max Tokens**: 自定义上下文长度与最大输出 Token 数。


4. 点击 **`Test FIM Support`** 按钮，验证大模型的 FIM 补全响应能力。
5. 勾选 **`Enable AnzongFIM Completion`**，保存配置即可在编辑器中享受丝滑补全。

---

## 🛠️ 本地开发与调试 (Development)

本项目基于 **IntelliJ Platform SDK (2024.2.1)** 开发，使用 **Java 21 Toolchain**。

运行以下 Gradle 任务启动沙箱 IDE 进行调试：

```bash
# 启动带有该插件的测试版 IDEA 沙箱
./gradlew runIde

# 运行代码检查与验证
./gradlew verifyPlugin

```

---

## 💡 键盘交互说明

* **正常打字**：输入单字符时自动触发防抖（250ms），异步渲染幽灵文本。
* **按 Tab 键**：采纳当前灰色幽灵文本，并自动触发连续联想。
* **退格删除 (Backspace)**：支持智能重算，删除字符后自动刷新补全上下文。

---

## 📄 License

本项目基于 [MIT License](https://www.google.com/search?q=LICENSE&utm_source=gemini) 开源，欢迎提交 Issue 与 Pull Request 共同完善！