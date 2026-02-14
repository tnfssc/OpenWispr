import SwiftUI

struct SettingsView: View {
  @EnvironmentObject var appState: AppState
  @ObservedObject var settings: SettingsStore

  var body: some View {
    Form {
      Section("Recording And Shortcuts") {
        LabeledContent("Toggle Shortcut") {
          TextField("<Control><Option>R", text: $settings.toggleShortcut)
            .multilineTextAlignment(.trailing)
            .textFieldStyle(.roundedBorder)
            .frame(width: 210)
        }

        Toggle("Enable Hold To Speak", isOn: $settings.holdToSpeakEnabled)

        LabeledContent("Hold Shortcut") {
          TextField("RightOption", text: $settings.holdShortcut)
            .multilineTextAlignment(.trailing)
            .textFieldStyle(.roundedBorder)
            .frame(width: 210)
            .disabled(!settings.holdToSpeakEnabled)
        }

        Text("For single-key hold-to-talk, use `RightOption`.")
          .font(.caption)
          .foregroundStyle(.secondary)
      }

      Section("Output") {
        Toggle("Auto Paste", isOn: $settings.autoPasteEnabled)
        Toggle("Notifications", isOn: $settings.notificationsEnabled)

        Toggle("Start At Login", isOn: $settings.startAtLoginEnabled)
          .disabled(!appState.startAtLoginSupported)

        if !appState.startAtLoginSupported {
          Text("Start at login is available only when running from a bundled .app.")
            .font(.caption)
            .foregroundStyle(.secondary)
        }

        Button("Prompt Accessibility Permission") {
          appState.requestAccessibilityPrompt()
        }
      }

      Section("Local Tools") {
        LabeledContent("whisper-cli Path") {
          TextField("/opt/homebrew/bin/whisper-cli", text: $settings.whisperBinaryPath)
            .textFieldStyle(.roundedBorder)
            .frame(minWidth: 420)
        }

        LabeledContent("Local Model Path") {
          TextField("~/openwispr/models/ggml-base.en.bin", text: $settings.localModelPath)
            .textFieldStyle(.roundedBorder)
            .frame(minWidth: 420)
        }

        LabeledContent("ffmpeg Path") {
          TextField("/opt/homebrew/bin/ffmpeg", text: $settings.ffmpegBinaryPath)
            .textFieldStyle(.roundedBorder)
            .frame(minWidth: 420)
        }

        Toggle("Trim Silence (ffmpeg)", isOn: $settings.silenceTrimEnabled)

        if settings.silenceTrimEnabled {
          LabeledContent("Silence Threshold") {
            TextField("-35dB", text: $settings.silenceThreshold)
              .multilineTextAlignment(.trailing)
              .textFieldStyle(.roundedBorder)
              .frame(width: 120)
          }

          LabeledContent("Silence Duration") {
            TextField(
              "0.25",
              value: $settings.silenceDuration,
              format: .number.precision(.fractionLength(2))
            )
            .multilineTextAlignment(.trailing)
            .textFieldStyle(.roundedBorder)
            .frame(width: 120)
          }
        }
      }

      Section("Speech To Text") {
        Picker("Provider", selection: $settings.sttProvider) {
          ForEach(STTProvider.allCases) { provider in
            Text(provider.label).tag(provider)
          }
        }
        .pickerStyle(.segmented)

        switch settings.sttProvider {
        case .local:
          Text("Using local whisper-cli with the paths configured in Local Tools.")
            .font(.caption)
            .foregroundStyle(.secondary)

        case .openai:
          LabeledContent("Endpoint") {
            TextField(
              "https://api.openai.com/v1/audio/transcriptions", text: $settings.sttOpenAIEndpoint
            )
            .textFieldStyle(.roundedBorder)
            .frame(minWidth: 420)
          }

          LabeledContent("Model") {
            TextField("whisper-1", text: $settings.sttOpenAIModel)
              .multilineTextAlignment(.trailing)
              .textFieldStyle(.roundedBorder)
              .frame(width: 220)
          }

          LabeledContent("API Key") {
            SecureField("sk-...", text: $settings.sttOpenAIApiKey)
              .textFieldStyle(.roundedBorder)
              .frame(minWidth: 320)
          }

        case .groq:
          LabeledContent("Endpoint") {
            TextField(
              "https://api.groq.com/openai/v1/audio/transcriptions", text: $settings.sttGroqEndpoint
            )
            .textFieldStyle(.roundedBorder)
            .frame(minWidth: 420)
          }

          LabeledContent("Model") {
            TextField("whisper-large-v3", text: $settings.sttGroqModel)
              .multilineTextAlignment(.trailing)
              .textFieldStyle(.roundedBorder)
              .frame(width: 220)
          }

          LabeledContent("API Key") {
            SecureField("gsk_...", text: $settings.sttGroqApiKey)
              .textFieldStyle(.roundedBorder)
              .frame(minWidth: 320)
          }
        }
      }

      Section("LLM Cleanup") {
        Toggle("Enable LLM Cleanup", isOn: $settings.llmFilterEnabled)

        if settings.llmFilterEnabled {
          Picker("Provider", selection: $settings.llmProvider) {
            ForEach(LLMProvider.allCases) { provider in
              Text(provider.label).tag(provider)
            }
          }
          .pickerStyle(.segmented)

          switch settings.llmProvider {
          case .openai:
            LabeledContent("Endpoint") {
              TextField(
                "https://api.openai.com/v1/chat/completions", text: $settings.llmOpenAIEndpoint
              )
              .textFieldStyle(.roundedBorder)
              .frame(minWidth: 420)
            }

            LabeledContent("Model") {
              TextField("gpt-4o-mini", text: $settings.llmOpenAIModel)
                .multilineTextAlignment(.trailing)
                .textFieldStyle(.roundedBorder)
                .frame(width: 220)
            }

            LabeledContent("API Key") {
              SecureField("sk-...", text: $settings.llmOpenAIApiKey)
                .textFieldStyle(.roundedBorder)
                .frame(minWidth: 320)
            }

          case .groq:
            LabeledContent("Endpoint") {
              TextField(
                "https://api.groq.com/openai/v1/chat/completions", text: $settings.llmGroqEndpoint
              )
              .textFieldStyle(.roundedBorder)
              .frame(minWidth: 420)
            }

            LabeledContent("Model") {
              TextField("llama-3.1-8b-instant", text: $settings.llmGroqModel)
                .multilineTextAlignment(.trailing)
                .textFieldStyle(.roundedBorder)
                .frame(width: 220)
            }

            LabeledContent("API Key") {
              SecureField("gsk_...", text: $settings.llmGroqApiKey)
                .textFieldStyle(.roundedBorder)
                .frame(minWidth: 320)
            }
          }

          Text("Cleanup Prompt")
            .font(.subheadline)
            .foregroundStyle(.secondary)

          TextEditor(text: $settings.llmCleanupPrompt)
            .font(.system(.body, design: .monospaced))
            .frame(minHeight: 140)
            .overlay(
              RoundedRectangle(cornerRadius: 8)
                .stroke(Color.secondary.opacity(0.25), lineWidth: 1)
            )
        }
      }

      Section("Diagnostics") {
        ForEach(appState.dependencyChecks(), id: \.self) { line in
          HStack(spacing: 8) {
            Image(
              systemName: line.hasPrefix("[ok]")
                ? "checkmark.circle.fill" : "exclamationmark.triangle.fill"
            )
            .foregroundStyle(line.hasPrefix("[ok]") ? .green : .orange)

            Text(line)
              .font(.system(.caption, design: .monospaced))
          }
        }
      }
    }
    .formStyle(.grouped)
    .padding(.top, 8)
    .frame(minWidth: 760, minHeight: 760)
  }
}
