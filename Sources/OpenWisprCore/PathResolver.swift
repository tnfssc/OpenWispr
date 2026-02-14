import Foundation

public enum PathResolver {
  public static func expand(_ path: String) -> String {
    (path as NSString).expandingTildeInPath
  }

  public static func resolveExecutable(_ path: String) -> String {
    let expanded = expand(path)
    if expanded.contains("/") {
      return expanded
    }

    for candidate in ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"] {
      let full = "\(candidate)/\(expanded)"
      if FileManager.default.fileExists(atPath: full) {
        return full
      }
    }

    return expanded
  }
}
