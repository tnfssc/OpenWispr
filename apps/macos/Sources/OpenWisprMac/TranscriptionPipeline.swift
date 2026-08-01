import Foundation
import OpenWisprCore

enum PipelineError: LocalizedError {
  case missingFile(String)
  case invalidURL(String)
  case emptyResponse(String)
  case missingCredentials(String)
  case remoteHTTP(provider: String, code: Int, detail: String)

  var errorDescription: String? {
    switch self {
    case .missingFile(let value),
      .invalidURL(let value),
      .emptyResponse(let value),
      .missingCredentials(let value):
      return value
    case .remoteHTTP(let provider, let code, let detail):
      if detail.isEmpty {
        return "\(provider) STT request failed with HTTP \(code)"
      }
      return "\(provider) STT request failed with HTTP \(code): \(detail)"
    }
  }
}

struct TranscriptionPipeline {
  func run(inputURL: URL, settings: SettingsSnapshot) async throws -> String {
    let preparedInput = try await trimSilenceIfNeeded(inputURL: inputURL, settings: settings)
    let cleanupURLs = cleanupCandidates(originalInput: inputURL, preparedInput: preparedInput)
    defer { cleanupTemporaryFiles(cleanupURLs) }

    var transcript = try await transcribe(inputURL: preparedInput, settings: settings)
    transcript = transcript.trimmingCharacters(in: .whitespacesAndNewlines)

    guard !transcript.isEmpty else {
      return ""
    }

    if settings.llmFilterEnabled {
      do {
        transcript = try await cleanupTranscript(transcript, settings: settings)
      } catch {
        // Fallback to raw transcript by design.
      }
    }

    return transcript.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  private func cleanupCandidates(originalInput: URL, preparedInput: URL) -> [URL] {
    var urls: [URL] = [originalInput]

    if preparedInput.path != originalInput.path {
      urls.append(preparedInput)
    }

    urls.append(URL(fileURLWithPath: originalInput.path + ".txt"))
    urls.append(URL(fileURLWithPath: preparedInput.path + ".txt"))
    return urls
  }

  private func cleanupTemporaryFiles(_ urls: [URL]) {
    let temporaryRoot = FileManager.default.temporaryDirectory.standardizedFileURL.path

    for fileURL in urls {
      let path = fileURL.standardizedFileURL.path
      guard path.hasPrefix(temporaryRoot) else {
        continue
      }

      try? FileManager.default.removeItem(at: fileURL)
    }
  }

  private func trimSilenceIfNeeded(inputURL: URL, settings: SettingsSnapshot) async throws -> URL {
    guard settings.silenceTrimEnabled else {
      return inputURL
    }

    let ffmpegPath = PathResolver.resolveExecutable(settings.ffmpegBinaryPath)
    guard FileManager.default.fileExists(atPath: ffmpegPath) else {
      return inputURL
    }

    let trimmedURL = FileManager.default.temporaryDirectory
      .appendingPathComponent("openwispr-trimmed-\(UUID().uuidString).wav")

    let filter =
      "silenceremove=start_periods=1:start_duration=\(settings.silenceDuration):start_threshold=\(settings.silenceThreshold):stop_periods=-1:stop_duration=\(settings.silenceDuration):stop_threshold=\(settings.silenceThreshold)"

    do {
      _ = try await CommandRunner.run(
        executable: ffmpegPath,
        arguments: [
          "-y",
          "-hide_banner",
          "-loglevel", "error",
          "-i", inputURL.path,
          "-af", filter,
          trimmedURL.path,
        ]
      )
    } catch {
      return inputURL
    }

    guard let attrs = try? FileManager.default.attributesOfItem(atPath: trimmedURL.path),
      let size = attrs[.size] as? NSNumber,
      size.intValue > 0
    else {
      return inputURL
    }

    return trimmedURL
  }

  private func transcribe(inputURL: URL, settings: SettingsSnapshot) async throws -> String {
    switch settings.sttProvider {
    case .local:
      return try await transcribeLocal(inputURL: inputURL, settings: settings)
    case .openai:
      let endpoint = settings.sttOpenAIEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
      let model = settings.sttOpenAIModel.trimmingCharacters(in: .whitespacesAndNewlines)
      let apiKey = settings.sttOpenAIApiKey.trimmingCharacters(in: .whitespacesAndNewlines)

      guard !apiKey.isEmpty else {
        throw PipelineError.missingCredentials("Missing OpenAI STT API key")
      }

      return try await transcribeRemote(
        inputURL: inputURL,
        endpoint: endpoint,
        model: model,
        apiKey: apiKey,
        providerName: "OpenAI"
      )
    case .groq:
      let endpoint = settings.sttGroqEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
      let model = settings.sttGroqModel.trimmingCharacters(in: .whitespacesAndNewlines)
      let apiKey = settings.sttGroqApiKey.trimmingCharacters(in: .whitespacesAndNewlines)

      guard !apiKey.isEmpty else {
        throw PipelineError.missingCredentials("Missing Groq STT API key")
      }

      do {
        return try await transcribeRemote(
          inputURL: inputURL,
          endpoint: endpoint,
          model: model,
          apiKey: apiKey,
          providerName: "Groq"
        )
      } catch let error as PipelineError {
        if model == "whisper-large-v3-turbo",
          case .remoteHTTP(let provider, let code, _) = error,
          provider == "Groq",
          code == 400
        {
          return try await transcribeRemote(
            inputURL: inputURL,
            endpoint: endpoint,
            model: "whisper-large-v3",
            apiKey: apiKey,
            providerName: "Groq"
          )
        }

        throw error
      }
    }
  }

  private func transcribeLocal(inputURL: URL, settings: SettingsSnapshot) async throws -> String {
    let whisperPath = PathResolver.resolveExecutable(settings.whisperBinaryPath)
    guard FileManager.default.fileExists(atPath: whisperPath) else {
      throw PipelineError.missingFile("whisper-cli not found at \(whisperPath)")
    }

    let modelPath = PathResolver.expand(settings.localModelPath)
    guard FileManager.default.fileExists(atPath: modelPath) else {
      throw PipelineError.missingFile("Whisper model not found at \(modelPath)")
    }

    _ = try await CommandRunner.run(
      executable: whisperPath,
      arguments: [
        "-m", modelPath,
        "-f", inputURL.path,
        "-otxt",
        "-np",
        "-nt",
      ]
    )

    let transcriptPath = inputURL.path + ".txt"
    guard FileManager.default.fileExists(atPath: transcriptPath) else {
      throw PipelineError.emptyResponse("Local transcript file was not generated")
    }

    let transcript = try String(contentsOfFile: transcriptPath, encoding: .utf8)
    return transcript.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  private func transcribeRemote(
    inputURL: URL,
    endpoint: String,
    model: String,
    apiKey: String,
    providerName: String
  )
    async throws -> String
  {
    guard !model.isEmpty else {
      throw PipelineError.emptyResponse("Missing STT model")
    }

    guard let url = URL(string: endpoint) else {
      throw PipelineError.invalidURL("Invalid STT endpoint: \(endpoint)")
    }

    let boundary = "Boundary-\(UUID().uuidString)"
    let body = try buildMultipartBody(fileURL: inputURL, model: model, boundary: boundary)

    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.httpBody = body
    request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
    request.setValue(
      "multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

    let (data, response) = try await URLSession.shared.data(for: request)
    guard let http = response as? HTTPURLResponse else {
      throw PipelineError.emptyResponse("No HTTP response from STT provider")
    }
    guard (200..<300).contains(http.statusCode) else {
      let responseText =
        String(data: data, encoding: .utf8)?.trimmingCharacters(
          in: .whitespacesAndNewlines
        ) ?? ""

      throw PipelineError.remoteHTTP(
        provider: providerName,
        code: http.statusCode,
        detail: responseText
      )
    }

    if let transcript = TranscriptParser.parseSTTResponse(data: data) {
      return transcript
    }

    throw PipelineError.emptyResponse("STT response did not include transcript text")
  }

  private func cleanupTranscript(_ input: String, settings: SettingsSnapshot) async throws -> String
  {
    let endpoint: String
    let model: String
    let apiKey: String

    switch settings.llmProvider {
    case .openai:
      endpoint = settings.llmOpenAIEndpoint
      model = settings.llmOpenAIModel
      apiKey = settings.llmOpenAIApiKey
    case .groq:
      endpoint = settings.llmGroqEndpoint
      model = settings.llmGroqModel
      apiKey = settings.llmGroqApiKey
    }

    guard !apiKey.isEmpty else {
      throw PipelineError.missingCredentials("Missing LLM API key")
    }
    guard let url = URL(string: endpoint) else {
      throw PipelineError.invalidURL("Invalid LLM endpoint: \(endpoint)")
    }

    let payload: [String: Any] = [
      "model": model,
      "temperature": 0,
      "messages": [
        ["role": "system", "content": settings.llmCleanupPrompt],
        [
          "role": "user",
          "content":
            "Input transcript:\n<transcript>\n\(input)\n</transcript>\n\nReturn only cleaned transcript text.",
        ],
      ],
    ]

    let body = try JSONSerialization.data(withJSONObject: payload)
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.httpBody = body
    request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")

    let (data, response) = try await URLSession.shared.data(for: request)
    guard let http = response as? HTTPURLResponse else {
      throw PipelineError.emptyResponse("No HTTP response from LLM provider")
    }
    guard (200..<300).contains(http.statusCode) else {
      throw PipelineError.emptyResponse("LLM request failed with HTTP \(http.statusCode)")
    }

    return TranscriptParser.parseLLMResponse(data: data) ?? input
  }

  private func buildMultipartBody(fileURL: URL, model: String, boundary: String) throws -> Data {
    let fileData = try Data(contentsOf: fileURL)
    var body = Data()

    body.append("--\(boundary)\r\n".data(using: .utf8)!)
    body.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n".data(using: .utf8)!)
    body.append("\(model)\r\n".data(using: .utf8)!)

    body.append("--\(boundary)\r\n".data(using: .utf8)!)
    body.append(
      "Content-Disposition: form-data; name=\"file\"; filename=\"\(fileURL.lastPathComponent)\"\r\n"
        .data(using: .utf8)!)
    body.append("Content-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
    body.append(fileData)
    body.append("\r\n".data(using: .utf8)!)

    body.append("--\(boundary)--\r\n".data(using: .utf8)!)
    return body
  }
}
