#!/usr/bin/env bash
# install.sh — qtsurfer-mcp installer for Linux and macOS
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/QTSurfer/mcp-java/main/install.sh | bash
#
# Environment variables:
#   VERSION      — pin a release (default: latest)
#   INSTALL_DIR  — binary destination (default: ~/.local/bin or ~/bin)

set -euo pipefail

REPO="QTSurfer/mcp-java"
BINARY="qtsurfer-mcp"

# ── colours ──────────────────────────────────────────────────────────────────
if [[ -t 1 ]]; then
  RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BOLD='\033[1m'; NC='\033[0m'
else
  RED=''; GREEN=''; YELLOW=''; BOLD=''; NC=''
fi
info()    { echo -e "${BOLD}$*${NC}"; }
success() { echo -e "${GREEN}✓${NC} $*"; }
warn()    { echo -e "${YELLOW}!${NC} $*"; }
die()     { echo -e "${RED}error:${NC} $*" >&2; exit 1; }

# ── platform detection ────────────────────────────────────────────────────────
detect_os() {
  case "$(uname -s)" in
    Linux)  echo linux  ;;
    Darwin) echo macos  ;;
    *)      die "Unsupported OS: $(uname -s). Use the fat JAR on Windows — see README." ;;
  esac
}

detect_arch() {
  case "$(uname -m)" in
    x86_64|amd64)   echo amd64 ;;
    arm64|aarch64)  echo arm64 ;;
    *)              die "Unsupported architecture: $(uname -m)." ;;
  esac
}

# Returns the asset name for a native binary, or empty string if none exists.
native_asset() {
  local os=$1 arch=$2
  case "${os}-${arch}" in
    linux-amd64)  echo "${BINARY}-linux-amd64"  ;;
    macos-arm64)  echo "${BINARY}-macos-arm64"  ;;
    *)            echo "" ;;
  esac
}

# ── java detection ────────────────────────────────────────────────────────────
# Prints the path to a java 21+ executable, or returns 1.
find_java() {
  local candidates=()
  # Prefer JAVA_HOME if set
  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME/bin/java")
  # Pick up whatever is on PATH
  local path_java
  path_java=$(command -v java 2>/dev/null) && candidates+=("$path_java")
  # Common install locations
  candidates+=(
    /usr/lib/jvm/temurin-21/bin/java
    /usr/lib/jvm/java-21-openjdk-amd64/bin/java
    /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java
  )

  for cmd in "${candidates[@]}"; do
    # Expand globs (macOS paths)
    for resolved in $cmd; do
      [[ -x "$resolved" ]] || continue
      local ver
      ver=$("$resolved" -version 2>&1 | grep -oE '"[0-9]+' | head -1 | tr -d '"')
      if [[ -n "$ver" && "$ver" -ge 21 ]]; then
        echo "$resolved"
        return 0
      fi
    done
  done
  return 1
}

# ── version resolution ────────────────────────────────────────────────────────
get_latest_version() {
  curl -fsSLo /dev/null -w '%{url_effective}' \
    "https://github.com/$REPO/releases/latest" \
    | grep -o '[^/]*$'
}

# ── install directory ─────────────────────────────────────────────────────────
resolve_install_dir() {
  if [[ -n "${INSTALL_DIR:-}" ]]; then
    echo "$INSTALL_DIR"; return
  fi
  if [[ -d "$HOME/.local/bin" ]]; then
    echo "$HOME/.local/bin"
  elif [[ -d "$HOME/bin" ]]; then
    echo "$HOME/bin"
  else
    echo "$HOME/.local/bin"
  fi
}

# ── installers ────────────────────────────────────────────────────────────────
install_native() {
  local version=$1 asset=$2 dest=$3 os=$4
  local url="https://github.com/$REPO/releases/download/$version/$asset"
  info "Downloading $asset ${version}..."
  curl -fsSL "$url" -o "$dest"
  chmod +x "$dest"
  if [[ "$os" == "macos" ]]; then
    xattr -d com.apple.quarantine "$dest" 2>/dev/null || true
  fi
  success "Installed native binary → $dest"
}

install_jar() {
  local version=$1 java_cmd=$2 bin_dir=$3
  local jar_name="${BINARY}-java-${version}.jar"
  local url="https://github.com/$REPO/releases/download/$version/$jar_name"
  local lib_dir="$HOME/.local/lib/qtsurfer-mcp"
  local jar_dest="$lib_dir/$jar_name"
  local wrapper="$bin_dir/$BINARY"

  mkdir -p "$lib_dir"
  info "Downloading fat JAR ${version}..."
  curl -fsSL "$url" -o "$jar_dest"
  success "JAR saved → $jar_dest"

  info "Writing wrapper script → $wrapper"
  cat > "$wrapper" <<EOF
#!/usr/bin/env bash
exec "$java_cmd" -jar "$jar_dest" "\$@"
EOF
  chmod +x "$wrapper"
  success "Installed wrapper → $wrapper"
}

# ── sdkman java installer ─────────────────────────────────────────────────────
install_java_sdkman() {
  local sdkman_init="$HOME/.sdkman/bin/sdkman-init.sh"

  if [[ ! -f "$sdkman_init" ]]; then
    info "Installing SDKMAN..."
    curl -fsSL "https://get.sdkman.io" | bash
  fi

  # shellcheck source=/dev/null
  source "$sdkman_init"
  info "Installing Java 21 (Temurin) via SDKMAN..."
  sdk install java 21-tem </dev/tty
  success "Java 21 installed via SDKMAN."
  # Re-source so find_java picks up the new PATH
  source "$sdkman_init"
}

# ── main ──────────────────────────────────────────────────────────────────────
main() {
  local os arch version install_dir
  os=$(detect_os)
  arch=$(detect_arch)

  echo ""
  info "qtsurfer-mcp installer"
  echo "  Platform : $os/$arch"

  version="${VERSION:-$(get_latest_version)}"
  echo "  Version  : $version"

  install_dir=$(resolve_install_dir)
  mkdir -p "$install_dir"
  echo "  Install  : $install_dir"
  echo ""

  local asset
  asset=$(native_asset "$os" "$arch")

  if [[ -n "$asset" ]]; then
    install_native "$version" "$asset" "$install_dir/$BINARY" "$os"
  else
    warn "No native binary for $os/$arch — falling back to fat JAR (requires Java 21+)."
    local java_cmd
    if java_cmd=$(find_java); then
      success "Found Java: $java_cmd ($("$java_cmd" -version 2>&1 | head -1))"
      install_jar "$version" "$java_cmd" "$install_dir"
    else
      warn "Java 21+ not found."
      local install_java="n"
      # /dev/tty works even when the script is piped from curl
      if [[ -t 0 ]] || [[ -e /dev/tty ]]; then
        printf "  Install Java 21 via SDKMAN? [y/N] "
        read -r install_java </dev/tty || true
      fi
      if [[ "${install_java,,}" == "y" ]]; then
        install_java_sdkman
        java_cmd=$(find_java) || die "Java 21+ still not found after SDKMAN install. Open a new shell and re-run."
        install_jar "$version" "$java_cmd" "$install_dir"
      else
        die "Java 21+ required. Install it (e.g. via SDKMAN: https://sdkman.io) then re-run."
      fi
    fi
  fi

  echo ""
  # PATH reminder
  case ":$PATH:" in
    *":$install_dir:"*) ;;
    *)
      warn "$install_dir is not in your PATH. Add it:"
      echo "    export PATH=\"$install_dir:\$PATH\""
      echo ""
      warn "Then add to your shell profile (~/.bashrc, ~/.zshrc, etc.) to persist it."
      echo ""
      ;;
  esac

  success "Done. Test with:  $BINARY --help"
}

main "$@"
