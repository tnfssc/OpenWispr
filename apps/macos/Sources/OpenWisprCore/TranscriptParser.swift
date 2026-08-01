import Foundation

public enum TranscriptParser {
  public static func parseSTTResponse(data: Data) -> String? {
    guard let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      return nil
    }

    if let text = payload["text"] as? String,
      !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    {
      return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    if let transcript = payload["transcript"] as? String,
      !transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    {
      return transcript.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    if let nested = payload["result"] as? [String: Any],
      let text = nested["text"] as? String,
      !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    {
      return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    return nil
  }

  public static func parseLLMResponse(data: Data) -> String? {
    guard let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      return nil
    }

    if let outputText = payload["output_text"] as? String,
      !outputText.isEmpty
    {
      return normalize(outputText)
    }

    if let output = payload["output"] as? [[String: Any]] {
      var combined = ""
      for item in output {
        guard let content = item["content"] as? [[String: Any]] else {
          continue
        }

        for part in content {
          if let text = part["text"] as? String {
            combined += text
          }
        }
      }

      if !combined.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        return normalize(combined)
      }
    }

    if let choices = payload["choices"] as? [[String: Any]],
      let first = choices.first
    {
      if let message = first["message"] as? [String: Any],
        let content = message["content"] as? String,
        !content.isEmpty
      {
        return normalize(content)
      }

      if let text = first["text"] as? String,
        !text.isEmpty
      {
        return normalize(text)
      }
    }

    return nil
  }

  public static func normalize(_ value: String) -> String {
    var output = value.trimmingCharacters(in: .whitespacesAndNewlines)

    if output.hasPrefix("```") {
      var lines = output.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
      if !lines.isEmpty {
        lines.removeFirst()
      }
      if lines.last?.trimmingCharacters(in: .whitespacesAndNewlines) == "```" {
        _ = lines.popLast()
      }
      output = lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    if output.hasPrefix("\"") && output.hasSuffix("\"") && output.count >= 2 {
      output = String(output.dropFirst().dropLast()).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    return output
  }
}
