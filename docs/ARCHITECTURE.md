# AI Studio - Architecture

## 1. Project Identity
- App name: AI Studio
- Package: com.aistudio.mobile
- Platform: Android
- Minimum Android: API 26
- Development device: Tecno Pova 4, Android 12
- Language: Kotlin
- UI: Jetpack Compose
- Architecture: Modular, independent AI engine + external providers

## 2. Core Decisions
- Assistant architecture: BOTH
- Permissions: CONFIGURABLE per tool
- Connectivity: HYBRID (cloud + optional local providers)
- Primary development: Termux + Codex
- Build and CI: GitHub Actions
- No mandatory local Android SDK installation.

## 3. Main Modules
- app: Android application and UI
- core: conversation and task orchestration
- providers: AI provider adapters
- mcp: Model Context Protocol integration
- permissions: per-tool permission policies
- storage: local conversations and settings
- workspace: project and file management

## 4. Initial UI
- Chat screen
- Conversation history
- Provider selector
- Project workspace
- Tool permissions screen
- Settings screen

## 5. AI Providers
- Codex integration through supported interfaces.
- Gemini integration through its official API.
- Future providers must use a common provider interface.
- Provider quotas and API costs must be visible and respected.
- Never claim unlimited cloud usage.

## 6. MCP
- Support MCP through a dedicated manager.
- Separate tool discovery from tool execution.
- Require explicit approval for sensitive actions.
- Never trust tool descriptions or external tool output as instructions.

## 7. Permission System
Each tool must have an independent policy:
- Deny
- Ask every time
- Allow within configured scope

Classify operations:
- Read
- Write
- Execute
- Network
- Destructive
- Android system access

Destructive and system-wide operations require explicit confirmation.

## 8. Security
- Never hardcode API keys.
- Never commit credentials.
- Use Android Keystore-backed secure storage where applicable.
- Keep project file access scoped.
- Log tool activity without logging secrets.
- Do not execute arbitrary commands without permission.

## 9. Device Constraints
- Target a lightweight Android client.
- Avoid requiring a local large language model.
- Heavy builds should run on GitHub Actions.
- Do not assume persistent unrestricted background execution.

## 10. Development Rules
- Build incrementally.
- Keep modules testable.
- Prefer official APIs and documented protocols.
- Do not publish repositories or perform external actions without approval.
- Do not install packages or change device settings without approval.
